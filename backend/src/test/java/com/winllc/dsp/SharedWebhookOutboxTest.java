package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.winllc.dsp.webhooks.UrlGuard;
import com.winllc.dsp.webhooks.WebhookSender;
import com.winllc.dsp.webhooks.WebhookService;
import com.winllc.dsp.webhooks.Webhooks;
import jakarta.servlet.http.Cookie;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Webhooks with the shared database (PostgreSQL): an entry change's deliveries are written in the
 * transaction that stores its audit event, so they commit together or not at all and nothing waits
 * in a replica's memory; signing secrets are stored encrypted with DSP_SECRETS_KEY.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedWebhookOutboxTest extends ApiTestSupport {
  static final String BOB = "uid=bob,ou=people," + B;
  static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

  @DynamicPropertySource
  static void sharedDatabase(DynamicPropertyRegistry registry) {
    SharedDatabaseSupport.register(registry);
    registry.add("portal.secrets.key", () -> KEY);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired WebhookService service;
  @Autowired Webhooks webhooks;
  @Autowired com.winllc.dsp.integration.IntegrationProperties integration;

  Cookie session;

  MvcResult send(MockHttpServletRequestBuilder req) throws Exception {
    if (session != null) req.cookie(session);
    MvcResult r = mvc.perform(req).andReturn();
    Cookie set = r.getResponse().getCookie("DSP_SESSION");
    if (set != null) session = set.getMaxAge() == 0 ? null : set;
    return r;
  }

  @BeforeEach
  void setUp() throws Exception {
    service.pauseBackground(true);
    // Delivery runs only queue (what is checked here); nothing is sent.
    integration.getWebhooks().setEnabled(false);
    service.use(new UrlGuard(List.of(), host -> {
      if (host.equals("hooks.example")) return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
      throw new UnknownHostException(host);
    }), new WebhookSender(SSLContext.getDefault(), Duration.ofSeconds(2), Duration.ofSeconds(5)));
    MvcResult r = send(json(post("/api/auth/login"), Map.of("username", "admin", "password", "password")));
    assertThat(status(r)).isEqualTo(200);
  }

  @AfterEach
  void tearDown() throws Exception {
    jdbc.execute("DROP TRIGGER IF EXISTS dsp_test_fail_now ON dsp_webhook_delivery");
    jdbc.execute("DROP TRIGGER IF EXISTS dsp_test_fail_at_commit ON dsp_webhook_delivery");
    for (Webhooks.Webhook w : webhooks.all()) webhooks.delete(w.id());
    jdbc.update("DELETE FROM dsp_webhook_delivery");
    integration.getWebhooks().setEnabled(true);
    service.pauseBackground(false);
  }

  private Webhooks.Created hook() throws Exception {
    return webhooks.create("outbox", "https://hooks.example/x", List.of("entry.changed"), List.of(), true, "admin");
  }

  private MvcResult changeBob(String mobile) throws Exception {
    return send(json(put(java.net.URI.create("/api/directories/staff/entry" + q(BOB))), Map.of("values", Map.of("mobile", List.of(mobile)))));
  }

  private long queued(String hookId) {
    return jdbc.queryForObject("SELECT count(*) FROM dsp_webhook_delivery WHERE webhook_id = ?", Long.class, hookId);
  }

  private long audited(String text) {
    return jdbc.queryForObject("SELECT count(*) FROM dsp_audit_event WHERE line LIKE '%\"action\":\"entry.update\"%' AND line LIKE ?", Long.class,
        "%" + text + "%");
  }

  /** A trigger failing inserts into the delivery table: at once, or (deferred) only as the transaction commits. */
  private void failDeliveries(boolean atCommit) {
    jdbc.execute("CREATE OR REPLACE FUNCTION dsp_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
        + " RAISE EXCEPTION 'the database went away (test)'; END $$");
    jdbc.execute(atCommit
        ? "CREATE CONSTRAINT TRIGGER dsp_test_fail_at_commit AFTER INSERT ON dsp_webhook_delivery DEFERRABLE INITIALLY DEFERRED"
            + " FOR EACH ROW EXECUTE FUNCTION dsp_test_fail()"
        : "CREATE TRIGGER dsp_test_fail_now BEFORE INSERT ON dsp_webhook_delivery FOR EACH ROW EXECUTE FUNCTION dsp_test_fail()");
  }

  @Test
  void anEntryChangeIsQueuedWithItsAuditEventAndNothingWaitsInMemory() throws Exception {
    Webhooks.Created h = hook();
    MvcResult r = changeBob("+1 555 0400");
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    // Committed with the audit event, before the answer: no delivery run, nothing held by this replica.
    assertThat(audited("+1 555 0400")).isEqualTo(1);
    assertThat(queued(h.webhook().id())).isEqualTo(1);
    assertThat(service.spooled()).isZero();
  }

  @Test
  void whenTheOutboxRowsCannotCommitNeitherDoesTheAuditEvent() throws Exception {
    Webhooks.Created h = hook();
    failDeliveries(true);
    MvcResult r = changeBob("+1 555 0401");
    // The change is refused as unaudited: no event committed without its deliveries, none kept in memory.
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(503);
    assertThat(audited("+1 555 0401")).isZero();
    assertThat(queued(h.webhook().id())).isZero();
    assertThat(service.spooled()).isZero();
  }

  @Test
  void deliveriesThatFailAloneDoNotUndoTheAuditEventAndAreQueuedLater() throws Exception {
    Webhooks.Created h = hook();
    failDeliveries(false);
    MvcResult r = changeBob("+1 555 0402");
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(audited("+1 555 0402")).isEqualTo(1);
    assertThat(queued(h.webhook().id())).isZero();
    assertThat(service.spooled()).isEqualTo(1);

    jdbc.execute("DROP TRIGGER dsp_test_fail_now ON dsp_webhook_delivery");
    service.dispatch();
    assertThat(service.spooled()).isZero();
    assertThat(queued(h.webhook().id())).isEqualTo(1);
  }

  @Test
  void signingSecretsAreStoredEncrypted() throws Exception {
    Webhooks.Created h = hook();
    String stored = jdbc.queryForObject("SELECT content FROM dsp_document WHERE name = 'webhooks'", String.class);
    assertThat(stored).contains(h.webhook().id()).contains("dspenc:v1:").doesNotContain(h.secret());
    assertThat(webhooks.signingSecret(webhooks.get(h.webhook().id()))).isEqualTo(h.secret());
  }
}
