package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditStore;
import com.winllc.dsp.audit.JdbcAuditStore;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.DatabaseProperties;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.jobs.Job;
import com.winllc.dsp.jobs.JobContext;
import com.winllc.dsp.jobs.JobService;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.LoginThrottle;
import com.winllc.dsp.service.SessionService;
import com.winllc.dsp.store.ConfigStore;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/**
 * The shared database (DSP_DATABASE_URL, PostgreSQL): sessions, the sign-in lockout, the
 * configuration, the audit chain and jobs behave the same whichever replica a request reaches. A
 * second "replica" is simulated with components built on the same database.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedStoreTest extends ApiTestSupport {
  @DynamicPropertySource
  static void sharedDatabase(DynamicPropertyRegistry registry) {
    SharedDatabaseSupport.register(registry);
  }

  @Autowired ApplicationContext context;
  @Autowired Cluster cluster;
  @Autowired JdbcTemplate jdbc;
  @Autowired EnsProperties ens;
  @Autowired PortalProperties props;
  @Autowired DatabaseProperties database;
  @Autowired SessionService sessions;
  @Autowired AuditLog audit;
  @Autowired JobService jobs;

  @AfterEach
  void restore() {
    ens.getSession().setMaxPerUser(0);
  }

  /** Another replica: the same database, its own id. */
  Cluster otherReplica() {
    return new Cluster(context.getBeanProvider(JdbcTemplate.class), context.getBeanProvider(TransactionTemplate.class),
        context.getBeanProvider(com.winllc.dsp.cluster.SharedDocuments.class), context.getBeanProvider(LockProvider.class));
  }

  /** A browser: the session cookie only (Spring Session, no container session). */
  final class Browser {
    Cookie session;

    MvcResult send(MockHttpServletRequestBuilder req) throws Exception {
      if (session != null) req.cookie(session);
      MvcResult r = mvc.perform(req).andReturn();
      Cookie set = r.getResponse().getCookie("DSP_SESSION");
      if (set != null) session = set.getMaxAge() == 0 ? null : set;
      return r;
    }

    JsonNode me() throws Exception {
      return body(send(get("/api/auth/me"))).get("user");
    }
  }

  Browser signIn(String username) throws Exception {
    Browser b = new Browser();
    MvcResult r = b.send(json(post("/api/auth/login"), Map.of("username", username, "password", "password")));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(b.session).isNotNull();
    return b;
  }

  long auditEvents(String action, String text) {
    return jdbc.queryForObject("SELECT count(*) FROM dsp_audit_event WHERE line LIKE ? AND line LIKE ?", Long.class,
        "%\"action\":\"" + action + "\"%", "%" + text + "%");
  }

  @Test
  void sessionsLiveInTheDatabase() throws Exception {
    Browser alice = signIn("alice");
    assertThat(alice.me().get("username").asString()).isEqualTo("alice");
    assertThat(alice.session.isHttpOnly()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL", Long.class)).isPositive();

    // Signing out removes the session for every replica.
    assertThat(status(alice.send(json(post("/api/auth/logout"), Map.of())))).isEqualTo(200);
    assertThat(alice.me().isNull()).isTrue();
  }

  @Test
  void theSessionLimitEndsTheOldestSessionWhereverItWasCreated() throws Exception {
    ens.getSession().setMaxPerUser(1);
    Browser first = signIn("erin");
    Browser second = signIn("erin");
    assertThat(first.me().isNull()).isTrue();
    assertThat(second.me().get("username").asString()).isEqualTo("erin");
    assertThat(sessions.sessionsOf(new SessionUser("erin", "uid=erin,ou=people," + B, null, null, List.of(), false, null, null))).isEqualTo(1);
    assertThat(auditEvents("auth.session-expired", "\"reason\":\"concurrent\"")).isPositive();
  }

  @Test
  void idleSessionsAreRecordedAndRemovedByTheCleanupJob() throws Exception {
    Browser frank = signIn("frank");
    long before = auditEvents("auth.session-expired", "uid=frank");
    jdbc.update("UPDATE spring_session SET expiry_time = 0 WHERE principal_name IS NOT NULL");
    assertThat(sessions.removeExpiredSessions()).isPositive();
    assertThat(auditEvents("auth.session-expired", "uid=frank")).isEqualTo(before + 1);
    assertThat(frank.me().isNull()).isTrue();
    // Run again: nothing left to record.
    assertThat(sessions.removeExpiredSessions()).isZero();
  }

  @Test
  void theLockoutHoldsOnEveryReplica() throws Exception {
    int max = props.getLogin().getMaxFailures();
    for (int i = 0; i < max; i++) {
      MvcResult r = mvc.perform(json(post("/api/auth/login"), Map.of("username", "grace", "password", "wrong"))).andReturn();
      assertThat(status(r)).isEqualTo(401);
    }
    assertThat(status(mvc.perform(json(post("/api/auth/login"), Map.of("username", "grace", "password", "password"))).andReturn())).isEqualTo(429);
    LoginThrottle elsewhere = new LoginThrottle(otherReplica());
    assertThat(elsewhere.locked("127.0.0.1|grace", max, props.getLogin().getLockoutWindow().toMillis())).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dsp_login_failure WHERE throttle_key LIKE '%grace%'", Long.class))
        .as("only hashes are stored").isZero();
    jdbc.update("DELETE FROM dsp_login_failure");
  }

  @Test
  void concurrentAttemptsCannotTryMorePasswordsThanTheLimit() throws Exception {
    int max = props.getLogin().getMaxFailures();
    java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(12);
    try {
      List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
      for (int i = 0; i < 30; i++) {
        results.add(pool.submit(() -> status(mvc.perform(json(post("/api/auth/login"), Map.of("username", "heidi", "password", "wrong"))).andReturn())));
      }
      int tried = 0;
      for (var f : results) {
        int st = f.get();
        assertThat(st).isIn(401, 429);
        if (st == 401) tried++;
      }
      assertThat(tried).isEqualTo(max);
    } finally {
      pool.shutdownNow();
      jdbc.update("DELETE FROM dsp_login_failure");
    }
  }

  @Autowired com.winllc.dsp.integration.IntegrationProperties integration;
  @Autowired com.winllc.dsp.webhooks.Webhooks webhooks;
  @Autowired com.winllc.dsp.webhooks.WebhookService webhookService;

  @Test
  void apiTokensAndWebhookDeliveriesLiveInTheDatabase() throws Exception {
    Browser admin = signIn("admin");
    MvcResult created = admin.send(json(post("/api/admin/api-tokens"), Map.of("name", "db", "scopes", List.of("entries:read"))));
    assertThat(status(created)).isEqualTo(201);
    String id = body(created).get("token").get("id").asString();
    String secret = body(created).get("secret").asString();
    assertThat(jdbc.queryForObject("SELECT content FROM dsp_document WHERE name = 'api-tokens'", String.class)).contains(id).doesNotContain(secret);
    assertThat(status(mvc.perform(get("/api/v1/directories").header("Authorization", "Bearer " + secret)).andReturn())).isEqualTo(200);
    // Revoked on another replica: refused here at once (no session, nothing cached about the token itself).
    new com.winllc.dsp.integration.ApiTokens(props, mapper, otherReplica()).revoke(id, "admin");
    assertThat(status(mvc.perform(get("/api/v1/directories").header("Authorization", "Bearer " + secret)).andReturn())).isEqualTo(401);

    // The outbox: each due delivery is claimed by one replica only.
    integration.getWebhooks().setEnabled(false);
    try {
      var hook = webhooks.create("db", "https://hooks.example/x", List.of("entry.changed"), List.of(), true, "admin");
      assertThat(jdbc.queryForObject("SELECT content FROM dsp_document WHERE name = 'webhooks'", String.class)).contains(hook.webhook().id());
      String delivery = webhookService.ping(webhooks.get(hook.webhook().id()), "admin");
      assertThat(jdbc.queryForObject("SELECT status FROM dsp_webhook_delivery WHERE id = ?", String.class, delivery)).isEqualTo("pending");
      var replicaB = new com.winllc.dsp.webhooks.JdbcDeliveries(otherReplica());
      var claimed = replicaB.claim(java.time.Instant.now(), 10, Duration.ofMinutes(2));
      assertThat(claimed).extracting(d -> d.id()).contains(delivery);
      assertThat(webhookService.deliveries().claim(java.time.Instant.now(), 10, Duration.ofMinutes(2))).extracting(d -> d.id()).doesNotContain(delivery);
      webhooks.delete(hook.webhook().id());
      webhookService.deliveries().cancel(hook.webhook().id(), "test over");
    } finally {
      integration.getWebhooks().setEnabled(true);
    }
  }

  @Test
  void configurationChangesReachEveryReplicaWithoutLosingAny() {
    ConfigStore other = new ConfigStore(mapper, props, otherReplica(), database);
    int forms = store.snapshot().forms.size();
    other.update(d -> d.forms.remove(0));
    assertThat(store.snapshot().forms).hasSize(forms - 1);
    // A change made here starts from the other replica's version, not this one's copy.
    store.update(d -> {
      d.grants.clear();
      return null;
    });
    assertThat(other.snapshot().forms).hasSize(forms - 1);
    assertThat(other.snapshot().grants).isEmpty();
  }

  @Test
  void replicasAppendToOneVerifiableAuditChain() throws Exception {
    JdbcAuditStore other = new JdbcAuditStore(otherReplica(), Files.createTempDirectory("dsp-replica"));
    for (int i = 0; i < 5; i++) {
      signIn("alice");
      other.append(new AuditEvent(java.util.UUID.randomUUID().toString(), 0, java.time.Instant.now().toString(), "directory-portal", "test", "replica-b", null,
          "system", "system.test", "success", AuditEvent.INFO, null, null, null, null, Map.of(), null, null, null, null), null);
    }
    AuditStore.Verification v = audit.store().verify();
    assertThat(v.intact()).as(v.problem()).isTrue();
    assertThat(audit.store().headSeq()).isEqualTo(other.headSeq());
    assertThat(audit.store().readAfter(audit.store().headSeq() - 3, 10)).hasSize(3);
  }

  @Test
  void jobsShowAndCancelFromAnotherReplica() throws Exception {
    SessionUser alice = new SessionUser("alice", ALICE, "Alice", null, List.of(), false, "password", null);
    JobService other = new JobService(props, otherReplica(), mapper);
    try {
      CountDownLatch release = new CountDownLatch(1);
      Job running = jobs.submit(alice, new JobService.Spec("metrics", "Slow", new Job.Target("test", "k1"), "/metrics", "k1",
          JobService.Pool.METRICS, Duration.ZERO), () -> {
            try {
              while (!release.await(20, TimeUnit.MILLISECONDS)) JobContext.current().checkCancelled();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return "done";
          });
      assertThat(other.list(alice)).extracting(Job.View::id).contains(running.id());
      other.cancel(alice, running.id());
      // The replica running it picks the request up on its next sync.
      invokeSync(jobs);
      jobs.await(running, Duration.ofSeconds(5));
      invokeSync(jobs);
      assertThat(other.get(alice, running.id()).view().status()).isEqualTo("cancelled");

      Job done = jobs.submit(alice, new JobService.Spec("metrics", "Quick", new Job.Target("test", "k2"), "/metrics", "k2",
          JobService.Pool.METRICS, Duration.ZERO), () -> Map.of("total", 8));
      jobs.await(done, Duration.ofSeconds(5));
      invokeSync(jobs);
      Job seen = other.get(alice, done.id());
      assertThat(seen.view().status()).isEqualTo("succeeded");
      assertThat(((JsonNode) seen.result()).get("total").asInt()).isEqualTo(8);
      other.dismiss(alice, done.id());
      assertThat(jobs.list(alice)).extracting(Job.View::id).doesNotContain(done.id());
    } finally {
      other.destroy();
    }
  }

  private static void invokeSync(JobService service) throws Exception {
    var m = JobService.class.getDeclaredMethod("syncShared");
    m.setAccessible(true);
    m.invoke(service);
  }
}
