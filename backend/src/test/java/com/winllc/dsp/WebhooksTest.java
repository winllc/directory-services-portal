package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.webhooks.Deliveries;
import com.winllc.dsp.webhooks.UrlGuard;
import com.winllc.dsp.webhooks.WebhookSender;
import com.winllc.dsp.webhooks.WebhookService;
import com.winllc.dsp.webhooks.Webhooks;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Signed outbound webhooks: the SSRF guard, signatures, retries and the delivery log. */
@ExtendWith(OutputCaptureExtension.class)
class WebhooksTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String BOB = "uid=bob,ou=people," + B;

  @Autowired WebhookService service;
  @Autowired IntegrationProperties props;
  @Autowired Webhooks webhooks;

  /** A received request. */
  record Received(String path, Map<String, List<String>> headers, String body) {
    String header(String name) {
      for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
      return null;
    }
  }

  HttpsServer server;
  final List<Received> received = new CopyOnWriteArrayList<>();
  final AtomicInteger answer = new AtomicInteger(204);
  /** What "hooks.example" resolves to; changed by the rebinding test. */
  volatile InetAddress[] hooksExample;
  SSLContext clientTls;

  // ---------------------------------------------------------------------------

  @BeforeEach
  void startReceiver() throws Exception {
    WebhookRuns.Tls tls = WebhookRuns.localhostTls();
    SSLContext serverTls = tls.server();
    clientTls = tls.client();

    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
    server.createContext("/", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(new Received(exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()), body));
      exchange.sendResponseHeaders(answer.get(), -1);
      exchange.close();
    });
    server.start();

    hooksExample = new InetAddress[] {InetAddress.getByAddress("hooks.example", new byte[] {93, (byte) 184, (byte) 216, 34})};
    props.getWebhooks().setAllowedHosts("localhost");
    props.getWebhooks().setMaxAttempts(8);
    UrlGuard guard = new UrlGuard(props.getWebhooks().allowedHostList(), host -> {
      if (host.equals("hooks.example")) return hooksExample;
      if (host.equals("localhost")) return new InetAddress[] {InetAddress.getLoopbackAddress()};
      throw new UnknownHostException(host);
    });
    service.use(guard, new WebhookSender(clientTls, Duration.ofSeconds(2), Duration.ofSeconds(5)));
  }

  @AfterEach
  void stopReceiver() throws Exception {
    // Webhooks outlive a test (the context is shared): remove this test's.
    Client admin = login("admin");
    for (JsonNode w : body(admin.get("/api/admin/webhooks"))) admin.del("/api/admin/webhooks/" + w.get("id").asString());
    server.stop(0);
    props.getWebhooks().setAllowedHosts("");
    props.getWebhooks().setMaxAttempts(8);
  }

  private String url(String path) {
    return "https://localhost:" + server.getAddress().getPort() + path;
  }

  private record Hook(String id, String secret) {}

  private Hook hook(Client admin, String url, List<String> events) throws Exception {
    MvcResult r = admin.post("/api/admin/webhooks", Map.of("name", "test " + System.nanoTime(), "url", url, "events", events));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
    return new Hook(body(r).get("webhook").get("id").asString(), body(r).get("secret").asString());
  }

  private JsonNode deliveries(Client admin, Hook h) throws Exception {
    return body(admin.get("/api/admin/webhooks/" + h.id() + "/deliveries"));
  }

  /** Runs the delivery loop until {@code done} (the background thread may get there first). */
  private void until(java.util.function.BooleanSupplier done) throws Exception {
    for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
      service.dispatch();
      Thread.sleep(50);
    }
    assertThat(done.getAsBoolean()).as("condition reached").isTrue();
  }

  private static String hmac(String secret, String text) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  // ---------------------------------------------------------------------------

  @Test
  void deliversSignedEventsForEntryAndMembershipChanges(CapturedOutput output) throws Exception {
    Client admin = login("admin");
    Hook h = hook(admin, url("/hooks/dsp?k=receiver-key"), List.of("entry.changed", "group.membership_changed"));

    MvcResult r = admin.put("/api/directories/groups/entry" + q(ENGINEERING),
        Map.of("values", Map.of("member", List.of(ALICE, "uid=carol,ou=people," + B, "uid=fatima,ou=people," + B, BOB))));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    until(() -> received.size() >= 2);

    Received membership = received.stream().filter(x -> "group.membership_changed".equals(x.header("X-DSP-Event"))).findFirst().orElseThrow();
    assertThat(membership.path()).isEqualTo("/hooks/dsp?k=receiver-key");
    // Signature: t=<timestamp>,v1=hex(HMAC-SHA256(secret, "<timestamp>.<body>")), and the timestamp is current.
    String ts = membership.header(WebhookSender.TIMESTAMP_HEADER);
    assertThat(Math.abs(Instant.now().getEpochSecond() - Long.parseLong(ts))).isLessThan(60);
    String expected = "t=" + ts + ",v1=" + hmac(h.secret(), ts + "." + membership.body());
    assertThat(MessageDigest.isEqual(expected.getBytes(), membership.header(WebhookSender.SIGNATURE_HEADER).getBytes())).isTrue();
    assertThat(membership.header(WebhookSender.SIGNATURE_HEADER)).isNotEqualTo("t=" + ts + ",v1=" + hmac("whsec_wrong", ts + "." + membership.body()));
    JsonNode event = mapper.readTree(membership.body());
    assertThat(event.get("type").asString()).isEqualTo("group.membership_changed");
    assertThat(event.get("data").get("added").toString()).contains(BOB);
    assertThat(event.get("data").get("removed").size()).isZero();
    assertThat(event.get("actor").get("username").asString()).isEqualTo("admin");
    assertThat(event.get("actor").get("authMethod").asString()).isEqualTo("password");
    assertThat(event.get("actor").has("apiClientId")).isFalse();

    Received entry = received.stream().filter(x -> "entry.changed".equals(x.header("X-DSP-Event"))).findFirst().orElseThrow();
    assertThat(mapper.readTree(entry.body()).get("data").get("operation").asString()).isEqualTo("update");
    // form.submitted wasn't subscribed.
    assertThat(received).noneMatch(x -> "form.submitted".equals(x.header("X-DSP-Event")));

    JsonNode log = deliveries(admin, h);
    assertThat(log.size()).isEqualTo(2);
    log.forEach(d -> {
      assertThat(d.get("status").asString()).isEqualTo("delivered");
      assertThat(d.get("lastStatus").asInt()).isEqualTo(204);
    });
    // The secret is shown once: never listed, logged or audited; the URL's key isn't audited either.
    String listed = admin.get("/api/admin/webhooks").getResponse().getContentAsString();
    assertThat(listed).contains(h.id()).doesNotContain(h.secret()).doesNotContain("secret");
    for (String sink : List.of(Files.readString(AUDIT_FILE), output.getAll())) {
      assertThat(sink).doesNotContain(h.secret()).doesNotContain("receiver-key");
    }
  }

  /** The outbox {@code real}, failing to queue (as with the database unreachable) while {@code down}. */
  private static Deliveries failingWhile(Deliveries real, java.util.concurrent.atomic.AtomicBoolean down) {
    return new Deliveries() {
      @Override
      public int enqueue(List<Delivery> deliveries) {
        if (down.get()) throw new IllegalStateException("Connection refused (test)");
        return real.enqueue(deliveries);
      }

      @Override
      public void flush() {
        real.flush();
      }

      @Override
      public List<Delivery> claim(Instant now, int limit, Duration lease, java.util.Set<String> skip) {
        return real.claim(now, limit, lease, skip);
      }

      @Override
      public int postpone(String webhookId, Instant until) {
        return real.postpone(webhookId, until);
      }

      @Override
      public void record(Delivery delivery) {
        real.record(delivery);
      }

      @Override
      public List<Delivery> recent(String webhookId, int limit) {
        return real.recent(webhookId, limit);
      }

      @Override
      public void cancel(String webhookId, String reason) {
        real.cancel(webhookId, reason);
      }

      @Override
      public void purge(Instant before) {
        real.purge(before);
      }
    };
  }

  @Test
  void eventsNameThePersonWhoChangedTheEntryTheEntryAndWhatChanged() throws Exception {
    Client admin = login("admin");
    hook(admin, url("/hooks/people"), List.of("entry.changed", "form.submitted"));

    // A user's own profile change, then an administrator deleting a device.
    MvcResult self = login("alice").put("/api/me/def-staff/entry" + q(ALICE), Map.of("values", Map.of("mobile", List.of("+1 555 0199"), "title", "CTO")));
    assertThat(status(self)).as(self.getResponse().getContentAsString()).isEqualTo(200);
    String device = "cn=LT-0002,ou=devices," + B;
    assertThat(status(admin.del("/api/directories/devices/entry" + q(device)))).isEqualTo(204);
    until(() -> received.size() >= 3);

    List<JsonNode> events = new java.util.ArrayList<>();
    for (Received x : received) {
      JsonNode e = mapper.readTree(x.body());
      assertThat(x.header("X-DSP-Event")).isEqualTo(e.get("type").asString());
      events.add(e);
    }
    assertThat(events).extracting(e -> e.get("type").asString() + " " + e.get("actor").get("username").asString())
        .containsExactlyInAnyOrder("entry.changed alice", "form.submitted alice", "entry.changed admin");
    java.util.function.BiFunction<String, String, JsonNode> event = (type, actor) -> events.stream()
        .filter(e -> e.get("type").asString().equals(type) && e.get("actor").get("username").asString().equals(actor)).findFirst().orElseThrow();

    JsonNode changed = event.apply("entry.changed", "alice").get("data");
    assertThat(changed.get("operation").asString()).isEqualTo("update");
    assertThat(changed.get("definitionId").asString()).isEqualTo("def-staff");
    assertThat(changed.get("dn").asString()).isEqualTo(ALICE);
    assertThat(changed.get("name").asString()).isEqualTo("Alice Anderson");
    // Only what was written: the title isn't self-editable.
    assertThat(changed.get("attributes").toString()).isEqualTo("[\"mobile\"]");
    assertThat(changed.has("previousDn")).isFalse();

    JsonNode submitted = event.apply("form.submitted", "alice").get("data");
    assertThat(submitted.get("operation").asString()).isEqualTo("self_update");
    assertThat(submitted.get("formId").asString()).isEqualTo("form-person");
    assertThat(submitted.get("dn").asString()).isEqualTo(ALICE);
    assertThat(submitted.get("name").asString()).isEqualTo("Alice Anderson");

    JsonNode deleted = event.apply("entry.changed", "admin").get("data");
    assertThat(deleted.get("operation").asString()).isEqualTo("delete");
    assertThat(deleted.get("dn").asString()).isEqualTo(device);
    assertThat(deleted.get("name").asString()).isEqualTo("LT-0002");
    assertThat(event.apply("entry.changed", "admin").get("actor").get("authMethod").asString()).isEqualTo("password");
  }

  @Test
  void aChangeThatCannotBeQueuedIsKeptAndSentOnceTheOutboxIsBack() throws Exception {
    Client admin = login("admin");
    Hook h = hook(admin, url("/spool"), List.of("entry.changed"));
    java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
    service.pauseBackground(true);
    Deliveries real = service.use(failingWhile(service.deliveries(), down));
    try {
      // The entry is changed; its event can't be queued yet.
      MvcResult r = admin.put("/api/directories/staff/entry" + q(BOB), Map.of("values", Map.of("mobile", List.of("+1 555 0300"))));
      assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
      MvcResult r2 = admin.put("/api/directories/staff/entry" + q(BOB), Map.of("values", Map.of("mobile", List.of("+1 555 0301"))));
      assertThat(status(r2)).isEqualTo(200);
      assertThat(service.spooled()).isEqualTo(2);
      service.dispatch();
      assertThat(service.spooled()).isEqualTo(2);
      assertThat(received).isEmpty();

      // The outbox is back: both are queued, in order, and delivered.
      down.set(false);
      until(() -> received.size() >= 2);
      assertThat(service.spooled()).isZero();
      assertThat(received).allMatch(x -> "entry.changed".equals(x.header("X-DSP-Event")));
      assertThat(received.get(0).body()).contains(BOB);
      assertThat(Instant.parse(mapper.readTree(received.get(0).body()).get("createdAt").asString()))
          .isBeforeOrEqualTo(Instant.parse(mapper.readTree(received.get(1).body()).get("createdAt").asString()));
      assertThat(deliveries(admin, h).size()).isEqualTo(2);
    } finally {
      service.use(real);
      service.pauseBackground(false);
    }
  }

  @Test
  void retriesFailuresAndGivesUpAfterTheLastAttempt() throws Exception {
    Client admin = login("admin");
    answer.set(500);
    Hook h = hook(admin, url("/fail"), List.of("entry.changed"));
    assertThat(status(admin.post("/api/admin/webhooks/" + h.id() + "/test", null))).isEqualTo(200);
    until(() -> received.size() == 1);
    until(() -> {
      try {
        return deliveries(admin, h).get(0).get("lastStatus").asInt() == 500;
      } catch (Exception e) {
        return false;
      }
    });
    JsonNode d = deliveries(admin, h).get(0);
    assertThat(d.get("status").asString()).isEqualTo("pending");
    assertThat(d.get("attempts").asInt()).isEqualTo(1);
    assertThat(Instant.parse(d.get("nextAttemptAt").asString())).isAfter(Instant.now().plusSeconds(20));
    // Not attempted again before its time.
    service.dispatch();
    assertThat(received).hasSize(1);

    props.getWebhooks().setMaxAttempts(1);
    assertThat(status(admin.post("/api/admin/webhooks/" + h.id() + "/test", null))).isEqualTo(200);
    until(() -> received.size() == 2);
    until(() -> {
      try {
        for (JsonNode x : deliveries(admin, h)) if (x.get("status").asString().equals("failed")) return true;
        return false;
      } catch (Exception e) {
        return false;
      }
    });
    JsonNode failed = body(admin.get("/api/admin/audit?action=webhook.delivery-failed")).get("events").get(0);
    assertThat(failed.get("target").get("id").asString()).isEqualTo(h.id());
    assertThat(failed.get("details").get("host").asString()).isEqualTo("localhost");
  }

  @Test
  void refusesPrivateLoopbackLinkLocalAndMetadataUrls() throws Exception {
    Client admin = login("admin");
    props.getWebhooks().setAllowedHosts("");
    UrlGuard strict = new UrlGuard(List.of(), InetAddress::getAllByName);
    service.use(strict, new WebhookSender(clientTls, Duration.ofSeconds(2), Duration.ofSeconds(5)));
    for (String bad : List.of("http://example.com/hook", "https://user:pw@example.com/", "https://169.254.169.254/latest/meta-data/",
        "https://[::1]/", "https://127.0.0.1/", "https://localhost/", "https://10.1.2.3/", "https://192.168.0.1/", "https://172.20.0.1/",
        "https://[fd00:ec2::254]/", "https://[::ffff:169.254.169.254]/", "https://[64:ff9b::a9fe:a9fe]/", "https://[2002:a9fe:a9fe::]/",
        "https://[fe80::1]/", "https://0.0.0.0/", "https://100.64.0.1/", "ftp://example.com/",
        // NAT64 (well-known and local-use prefixes), IPv4-compatible and IPv4-translated forms.
        "https://[64:ff9b:1::a9fe:a9fe]/", "https://[64:ff9b:1:ffff::1]/", "https://[64:ff9b::7f00:1]/", "https://[::a9fe:a9fe]/",
        "https://[::ffff:0:a9fe:a9fe]/", "https://[::ffff:0:7f00:1]/")) {
      MvcResult r = admin.post("/api/admin/webhooks", Map.of("name", "bad", "url", bad, "events", List.of("entry.changed")));
      assertThat(status(r)).as(bad).isEqualTo(400);
      assertThat(body(r).get("details").get("url").asString()).as(bad).isNotBlank();
    }
  }

  @Test
  void theGuardClassifiesAddressesAndHonoursTheAllowList() throws Exception {
    Map<String, String> dns = Map.of("public.example", "93.184.216.34", "internal.example", "10.0.0.5", "meta.example", "169.254.169.254",
        "v6meta.example", "fd00:ec2::254", "loop.example", "::1");
    UrlGuard.Resolver resolver = host -> {
      String ip = dns.get(host);
      if (ip == null) throw new UnknownHostException(host);
      return new InetAddress[] {InetAddress.getByName(ip)};
    };
    UrlGuard guard = new UrlGuard(List.of("internal.example", "*.allowed.example", "meta.example", "v6meta.example"), resolver);
    assertThat(guard.resolve(URI.create("https://public.example/x")).address().getHostAddress()).isEqualTo("93.184.216.34");
    assertThat(guard.resolve(URI.create("https://internal.example:8443/x")).port()).isEqualTo(8443);
    // Metadata addresses stay refused even when the host is allow-listed.
    assertThatThrownBy(() -> guard.resolve(URI.create("https://meta.example/"))).hasMessageContaining("metadata");
    assertThatThrownBy(() -> guard.resolve(URI.create("https://v6meta.example/"))).hasMessageContaining("metadata");
    assertThatThrownBy(() -> guard.resolve(URI.create("https://loop.example/"))).hasMessageContaining("loopback");
    assertThatThrownBy(() -> guard.resolve(URI.create("https://nowhere.example/"))).hasMessageContaining("cannot be resolved");
    assertThat(guard.allowListed("a.allowed.example")).isTrue();
    assertThat(guard.allowListed("allowed.example")).isFalse();
    assertThat(guard.allowListed("evilallowed.example")).isFalse();
    // A host with several addresses is refused when any of them is not public.
    UrlGuard mixed = new UrlGuard(List.of(), host -> new InetAddress[] {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")});
    assertThatThrownBy(() -> mixed.resolve(URI.create("https://mixed.example/"))).hasMessageContaining("loopback");
  }

  @Test
  void checksTheAddressAgainAtSendTime() throws Exception {
    // DNS rebinding: hooks.example is public when the webhook is saved, then resolves to the
    // metadata service; the delivery is refused and nothing is sent.
    Client admin = login("admin");
    Hook h = hook(admin, "https://hooks.example:" + server.getAddress().getPort() + "/rebind", List.of("entry.changed"));
    hooksExample = new InetAddress[] {InetAddress.getByName("169.254.169.254")};
    assertThat(status(admin.post("/api/admin/webhooks/" + h.id() + "/test", null))).isEqualTo(200);
    until(() -> {
      try {
        JsonNode d = deliveries(admin, h).get(0);
        return d.get("attempts").asInt() >= 1 && d.hasNonNull("lastError");
      } catch (Exception e) {
        return false;
      }
    });
    assertThat(deliveries(admin, h).get(0).get("lastError").asString()).contains("Refused").contains("metadata");
    // Rebinding to loopback (the receiver's own address) is refused too: hooks.example isn't allow-listed.
    hooksExample = new InetAddress[] {InetAddress.getLoopbackAddress()};
    assertThat(status(admin.post("/api/admin/webhooks/" + h.id() + "/test", null))).isEqualTo(200);
    until(() -> {
      try {
        for (JsonNode d : deliveries(admin, h)) if (d.hasNonNull("lastError") && d.get("lastError").asString().contains("loopback")) return true;
        return false;
      } catch (Exception e) {
        return false;
      }
    });
    assertThat(received).isEmpty();
  }

  @Test
  void managesWebhooksAsAdministratorsOnlyWithAudit() throws Exception {
    Client alice = login("alice");
    assertThat(status(alice.get("/api/admin/webhooks"))).isEqualTo(403);
    Client admin = login("admin");
    Hook h = hook(admin, url("/x"), List.of("form.submitted"));
    MvcResult rotated = admin.post("/api/admin/webhooks/" + h.id() + "/rotate-secret", null);
    String secret = body(rotated).get("secret").asString();
    assertThat(secret).startsWith("whsec_").isNotEqualTo(h.secret());
    MvcResult updated = admin.put("/api/admin/webhooks/" + h.id(), Map.of("name", "renamed", "url", url("/y"), "events", List.of("entry.changed"),
        "enabled", false));
    assertThat(status(updated)).isEqualTo(200);
    assertThat(body(updated).get("enabled").asBoolean()).isFalse();
    assertThat(status(admin.post("/api/admin/webhooks/" + h.id() + "/test", null))).isEqualTo(400);
    assertThat(status(admin.del("/api/admin/webhooks/" + h.id()))).isEqualTo(204);
    List<String> actions = new ArrayList<>();
    body(admin.get("/api/admin/audit?category=configuration&pageSize=50")).get("events").forEach(e -> actions.add(e.get("action").asString()));
    assertThat(actions).contains("webhook.created", "webhook.secret-rotated", "webhook.updated", "webhook.deleted");
    String auditText = Files.readString(AUDIT_FILE);
    assertThat(auditText).doesNotContain(h.secret()).doesNotContain(secret);
    assertThat(service.deliveries().recent(h.id(), 10)).allMatch(d -> !Deliveries.PENDING.equals(d.status()));
  }

  @Test
  void aDeadReceiverIsBackedOffAsAWholeAndLiveOnesStillGetTheirDeliveries() throws Exception {
    WebhookRuns.aDeadReceiverIsBackedOffAsAWhole(service, webhooks, props);
  }

  @Test
  void aWebhookGetsAtMostTenAttemptsARun() throws Exception {
    WebhookRuns.aWebhookGetsAtMostTenAttemptsARun(service, webhooks);
  }

  @Test
  void aSlowReceiverGetsNoMoreThanItsTimeShareOfARun() throws Exception {
    WebhookRuns.aSlowReceiverGetsNoMoreThanItsTimeShare(service, webhooks);
  }

  @Test
  void noAttemptStartsAfterTheRunBudget() throws Exception {
    WebhookRuns.noAttemptStartsAfterTheRunBudget(service, webhooks, props);
  }

  @Test
  void signsWithHmacSha256OverTimestampAndBody() throws Exception {
    byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    assertThat(WebhookSender.sign("whsec_k", 1700000000L, body)).isEqualTo("t=1700000000,v1=" + hmac("whsec_k", "1700000000.{\"a\":1}"));
    assertThat(WebhookSender.sign("whsec_k", 1700000001L, body)).isNotEqualTo(WebhookSender.sign("whsec_k", 1700000000L, body));
  }
}
