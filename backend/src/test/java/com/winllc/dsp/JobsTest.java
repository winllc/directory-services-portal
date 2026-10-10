package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.jobs.Job;
import com.winllc.dsp.jobs.JobContext;
import com.winllc.dsp.jobs.JobService;
import com.winllc.dsp.model.SessionUser;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Metrics never wait here, so every computation goes to the background. */
@TestPropertySource(properties = {"portal.metrics.wait-millis=0", "portal.jobs.max-active-per-user=2"})
class JobsTest extends ApiTestSupport {
  @Autowired JobService jobs;

  static final SessionUser ALICE_USER = new SessionUser("alice", ALICE, "Alice", null, List.of(), false, "password", null);

  /** Work that runs until released (or cancelled). */
  static Supplier<Object> blocking(CountDownLatch release) {
    return () -> {
      try {
        while (!release.await(20, TimeUnit.MILLISECONDS)) JobContext.current().checkCancelled();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "done";
    };
  }

  static JobService.Spec spec(String key) {
    return new JobService.Spec("metrics", "Slow", new Job.Target("test", key), "/metrics", key, JobService.Pool.METRICS, Duration.ZERO);
  }

  @Test
  void expensiveMetricsContinueInTheBackgroundAndReportWhenReady() throws Exception {
    Client alice = login("alice");
    Map<String, Object> body = Map.of("returnTo", "/metrics/explore?w=abc",
        "widgets", List.of(Map.of("id", "n", "title", "Staff size", "definitionId", "def-staff", "kind", "count", "chart", "stat")));
    MvcResult started = alice.post("/api/metrics/evaluate", body);
    assertThat(status(started)).isEqualTo(202);
    JsonNode job = body(started).get("job");
    assertThat(body(started).get("pending").asBoolean()).isTrue();
    assertThat(job.get("title").asString()).isEqualTo("Staff size");
    assertThat(job.get("link").asString()).isEqualTo("/metrics/explore?w=abc");
    String id = job.get("id").asString();

    JsonNode result = awaitResult(alice, started);
    assertThat(result.get("n").get("result").get("total").asLong()).isEqualTo(8);

    // Listed as finished and not yet seen (the client shows the "ready" notification), then seen.
    JsonNode listed = body(alice.get("/api/jobs")).get(0);
    assertThat(listed.get("id").asString()).isEqualTo(id);
    assertThat(listed.get("status").asString()).isEqualTo("succeeded");
    assertThat(listed.get("seen").asBoolean()).isFalse();
    assertThat(listed.get("entries").asLong()).isEqualTo(8);
    assertThat(status(alice.post("/api/jobs/seen", Map.of("ids", List.of(id))))).isEqualTo(204);
    assertThat(body(alice.get("/api/jobs/" + id)).get("seen").asBoolean()).isTrue();

    // Asking again shortly after reuses the background result instead of starting over.
    MvcResult again = alice.post("/api/metrics/evaluate", body);
    assertThat(status(again)).isEqualTo(200);
    assertThat(body(again).get("n").get("result").get("total").asLong()).isEqualTo(8);

    // Jobs are private to the user who started them.
    Client bob = login("bob");
    assertThat(status(bob.get("/api/jobs/" + id))).isEqualTo(404);
    assertThat(status(bob.get("/api/jobs/" + id + "/result"))).isEqualTo(404);
    assertThat(body(bob.get("/api/jobs")).size()).isZero();

    // Unsafe notification links are refused.
    Map<String, Object> offsite = Map.of("returnTo", "//evil.example", "widgets", body.get("widgets"));
    assertThat(status(alice.post("/api/metrics/evaluate", offsite))).isEqualTo(400);
  }

  @Test
  void overviewsAndDashboardsAnswerWithTheirJob() throws Exception {
    Client admin = login("admin");
    MvcResult overview = admin.get("/api/metrics/overview/staff?refresh=true");
    assertThat(status(overview)).isEqualTo(202);
    assertThat(body(overview).get("job").get("link").asString()).isEqualTo("/metrics/directories/staff");
    assertThat(awaitResult(admin, overview).get("results").get("ovw-count").get("result").get("total").asLong()).isEqualTo(8);
    MvcResult dash = admin.get("/api/metrics/dashboards/dash-workforce/results?refresh=true");
    assertThat(status(dash)).isEqualTo(202);
    assertThat(body(dash).get("job").get("target").get("id").asString()).isEqualTo("dash-workforce");
    assertThat(awaitResult(admin, dash).get("results").size()).isEqualTo(7);
    // Access is still checked up front.
    assertThat(status(login("bob").get("/api/metrics/overview/staff"))).isEqualTo(403);
  }

  @Test
  void jobsCanBeCancelledJoinedAndLimited() throws Exception {
    Client alice = login("alice");
    CountDownLatch release = new CountDownLatch(1);
    try {
      Job first = jobs.submit(ALICE_USER, spec("same"), blocking(release));
      // The same work for the same user joins the running job.
      assertThat(jobs.submit(ALICE_USER, spec("same"), blocking(release))).isSameAs(first);
      Job second = jobs.submit(ALICE_USER, spec("other"), blocking(release));

      // Two running is alice's limit here.
      MvcResult limited = alice.post("/api/metrics/evaluate",
          Map.of("widgets", List.of(Map.of("title", "x", "definitionId", "def-staff", "kind", "count", "chart", "stat"))));
      assertThat(status(limited)).isEqualTo(429);

      JsonNode running = body(alice.get("/api/jobs/" + first.id()));
      assertThat(running.get("status").asString()).isIn("queued", "running");
      assertThat(status(alice.get("/api/jobs/" + first.id() + "/result"))).isEqualTo(409);

      assertThat(body(alice.post("/api/jobs/" + first.id() + "/cancel", null)).get("id").asString()).isEqualTo(first.id());
      jobs.await(first, Duration.ofSeconds(5));
      assertThat(first.status()).isEqualTo(Job.Status.CANCELLED);

      release.countDown();
      jobs.await(second, Duration.ofSeconds(5));
      assertThat(second.status()).isEqualTo(Job.Status.SUCCEEDED);
      assertThat(status(alice.del("/api/jobs/" + second.id()))).isEqualTo(204);
      assertThat(status(alice.get("/api/jobs/" + second.id()))).isEqualTo(404);
    } finally {
      release.countDown();
    }
  }

  @Test
  void failuresAreReportedOnTheJob() throws Exception {
    Client alice = login("alice");
    Job failing = jobs.submit(ALICE_USER, spec("boom"), () -> {
      throw new IllegalStateException("Directory exploded");
    });
    jobs.await(failing, Duration.ofSeconds(5));
    JsonNode view = body(alice.get("/api/jobs/" + failing.id()));
    assertThat(view.get("status").asString()).isEqualTo("failed");
    assertThat(view.get("error").asString()).isEqualTo("Directory exploded");
    assertThat(status(alice.get("/api/jobs/" + failing.id() + "/result"))).isEqualTo(409);
  }
}
