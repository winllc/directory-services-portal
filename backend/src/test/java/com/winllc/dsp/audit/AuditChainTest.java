package com.winllc.dsp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.license.Licensing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The ens-audit/1 serialization, hash chain, verification, pruning and history migration. */
class AuditChainTest {
  /** The shared spec's test vector (audit-kit/vector.txt). */
  static final String VECTOR_BODY = "{\"schema\":\"ens-audit/1\",\"id\":\"00000000-0000-4000-8000-000000000001\",\"seq\":1,"
      + "\"time\":\"2026-01-01T00:00:00.000Z\",\"product\":\"test\",\"version\":\"dev\",\"host\":\"h\",\"installationId\":null,"
      + "\"category\":\"system\",\"action\":\"system.started\",\"outcome\":\"success\",\"severity\":\"info\","
      + "\"actor\":{\"type\":\"system\",\"id\":null,\"name\":null,\"roles\":[]},\"source\":null,\"target\":null,\"reason\":null,"
      + "\"details\":{},\"requestId\":null,\"session\":null,"
      + "\"prevHash\":\"0000000000000000000000000000000000000000000000000000000000000000\"}";
  static final String VECTOR_HASH = "90b915788e0c86d867ee7f9786404f333744acaf88803a244651156792ca300d";

  static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path dir;

  static AuditEvent event(String action, String time) {
    return new AuditEvent(java.util.UUID.randomUUID().toString(), 0, time, "test", "dev", "h", null, AuditCatalog.category(action),
        action, "success", "info", AuditEvent.Actor.SYSTEM, null, null, null, new LinkedHashMap<>(), null, null, null, null);
  }

  @Test
  void serializesAndHashesTheSpecVector() {
    AuditEvent e = new AuditEvent("00000000-0000-4000-8000-000000000001", 1, "2026-01-01T00:00:00.000Z", "test", "dev", "h", null,
        "system", "system.started", "success", "info", new AuditEvent.Actor("system", null, null, List.of()), null, null, null,
        Map.of(), null, null, AuditJson.GENESIS, null);
    String body = AuditJson.body(e);
    assertThat(body).isEqualTo(VECTOR_BODY);
    assertThat(AuditJson.hash(AuditJson.GENESIS, body)).isEqualTo(VECTOR_HASH);
    String line = AuditJson.line(body, VECTOR_HASH);
    assertThat(line).endsWith(",\"hash\":\"" + VECTOR_HASH + "\"}");
    assertThat(AuditJson.bodyOf(line)).isEqualTo(body);
    assertThat(AuditJson.hashOf(line)).isEqualTo(VECTOR_HASH);
  }

  @Test
  void escapesStringsLikeJavaScript() {
    StringBuilder sb = new StringBuilder();
    AuditJson.str(sb, "a\"b\\c\n\t\u0001é/€😀");
    assertThat(sb.toString()).isEqualTo("\"a\\\"b\\\\c\\n\\t\\u0001é/€😀\"");
  }

  @Test
  void storeChainsEventsAndTamperingIsDetectedAtItsSeq() throws Exception {
    AuditStore store = new AuditStore(dir);
    for (int i = 0; i < 5; i++) store.append(event("system.started", "2026-01-0" + (i + 1) + "T00:00:00.000Z"), null);
    assertThat(store.headSeq()).isEqualTo(5);
    AuditStore.Verification ok = store.verify();
    assertThat(ok.intact()).isTrue();
    assertThat(ok.anchorSeq()).isEqualTo(1);
    assertThat(ok.anchorIsGenesis()).isTrue();
    assertThat(ok.checked()).isEqualTo(5);

    Path file = dir.resolve("audit-2026-01.jsonl");
    List<String> lines = new ArrayList<>(Files.readAllLines(file));
    lines.set(2, lines.get(2).replace("\"system.started\"", "\"system.stopped\""));
    Files.write(file, lines);
    AuditStore.Verification broken = new AuditStore(dir).verify();
    assertThat(broken.intact()).isFalse();
    assertThat(broken.brokenSeq()).isEqualTo(3);

    // A deleted line is detected too.
    lines = new ArrayList<>(Files.readAllLines(file));
    lines.remove(2);
    Files.write(file, lines);
    assertThat(new AuditStore(dir).verify().brokenSeq()).isEqualTo(3);
  }

  @Test
  void continuesTheChainAfterARestartAndEndsAPartialLine() throws Exception {
    AuditStore store = new AuditStore(dir);
    store.append(event("system.started", "2026-01-01T00:00:00.000Z"), null);
    Files.writeString(dir.resolve("audit-2026-01.jsonl"), "{\"schema\":\"ens-audit/1\",\"id\":\"trunc", java.nio.file.StandardOpenOption.APPEND);
    AuditStore reopened = new AuditStore(dir);
    assertThat(reopened.headSeq()).isEqualTo(1);
    reopened.append(event("system.started", "2026-01-02T00:00:00.000Z"), null);
    AuditStore.Verification v = reopened.verify();
    assertThat(v.intact()).isTrue();
    assertThat(v.unreadableLines()).isEqualTo(1);
    assertThat(v.lastSeq()).isEqualTo(2);
  }

  @Test
  void neverWritesToAnOlderMonthThanTheHead() throws Exception {
    AuditStore store = new AuditStore(dir);
    store.append(event("system.started", "2026-02-01T00:00:00.000Z"), null);
    store.append(event("system.started", "2026-01-31T23:59:59.000Z"), null); // clock went back
    assertThat(dir.resolve("audit-2026-01.jsonl")).doesNotExist();
    assertThat(store.readAfter(0, 10)).extracting(AuditStore.Stored::seq).containsExactly(1L, 2L);
  }

  @Test
  void verificationAfterPruningReportsTheAnchor() throws Exception {
    AuditStore store = new AuditStore(dir);
    store.append(event("system.started", "2025-12-30T00:00:00.000Z"), null);
    for (int i = 0; i < 4; i++) store.append(event("system.started", "2026-01-0" + (i + 1) + "T00:00:00.000Z"), null);
    AuditStore.Pruned p = store.prune(s -> s.time().compareTo("2026-01-02") < 0);
    assertThat(p.count()).isEqualTo(2);
    assertThat(p.fromSeq()).isEqualTo(1);
    assertThat(p.toSeq()).isEqualTo(2);
    assertThat(dir.resolve("audit-2025-12.jsonl")).doesNotExist();

    AuditStore.Verification v = store.verify();
    assertThat(v.intact()).isTrue();
    assertThat(v.anchorSeq()).isEqualTo(3);
    assertThat(v.anchorIsGenesis()).isFalse();
    assertThat(store.readAfter(0, 10)).extracting(AuditStore.Stored::seq).containsExactly(3L, 4L, 5L);

    // Never the newest event.
    store.prune(s -> true);
    assertThat(store.readAfter(0, 10)).extracting(AuditStore.Stored::seq).containsExactly(5L);
    assertThat(store.verify().anchorSeq()).isEqualTo(5);
  }

  @Test
  void migratesTheOriginalAuditFilesInTimeOrder() throws Exception {
    Path auditDir = dir.resolve("audit");
    Files.createDirectories(auditDir);
    String oct = "{\"id\":\"audit-3b241101-e2bb-4255-8caf-4136c566a962\",\"at\":\"2026-10-02T10:00:00.123456Z\",\"action\":\"auth.login\","
        + "\"outcome\":\"failed\",\"sourceAddress\":\"10.0.0.9\",\"target\":{\"type\":\"user\",\"id\":\"bob\"},\"message\":\"Wrong password\","
        + "\"details\":{\"method\":\"password\"}}\n"
        + "{\"id\":\"audit-0b7e7b63-6c7e-4bb4-8c43-7a0c3f1b6b2c\",\"at\":\"2026-10-01T09:00:00Z\",\"action\":\"entry.update\","
        + "\"outcome\":\"success\",\"actor\":{\"username\":\"admin\",\"dn\":\"uid=admin,dc=x\",\"authMethod\":\"password\"},"
        + "\"target\":{\"type\":\"entry\",\"id\":\"uid=bob,dc=x\",\"name\":\"Bob\"},\"definitionId\":\"def-staff\","
        + "\"changes\":[{\"attribute\":\"mail\",\"before\":[\"a@x\"],\"after\":[\"b@x\",\"c@x\"]},"
        + "{\"attribute\":\"userPassword\",\"before\":[],\"after\":[\"(redacted)\"]}]}\n";
    String sep = "{\"id\":\"audit-9c0f5f8e-0d61-4c0e-9a55-1f7d6b1e2a3b\",\"at\":\"2026-09-15T08:00:00Z\",\"action\":\"license.install\","
        + "\"outcome\":\"denied\",\"actor\":{\"username\":\"admin\",\"dn\":\"uid=admin,dc=x\"},\"message\":\"bad\"}\n";
    Files.writeString(auditDir.resolve("audit-2026-10.jsonl"), oct);
    Files.writeString(auditDir.resolve("audit-2026-09.jsonl"), sep);

    AuditLog log = newLog(auditDir);
    assertThat(auditDir.resolve("legacy/audit-2026-10.jsonl")).hasContent(oct);
    assertThat(auditDir.resolve("legacy/audit-2026-09.jsonl")).hasContent(sep);
    assertThat(auditDir.resolve("legacy/MIGRATED")).exists();

    List<AuditStore.Stored> all = log.store().readAfter(0, 100);
    assertThat(all).hasSize(3);
    List<JsonNode> events = new ArrayList<>();
    for (AuditStore.Stored s : all) events.add(MAPPER.readTree(s.line()));
    assertThat(events).extracting(e -> e.get("action").asString()).containsExactly("license.install", "entry.update", "auth.login");
    assertThat(events).extracting(e -> e.get("seq").asLong()).containsExactly(1L, 2L, 3L);
    assertThat(events).allMatch(e -> e.get("details").get("migrated").asBoolean());

    JsonNode lic = events.get(0);
    assertThat(lic.get("category").asString()).isEqualTo("license");
    assertThat(lic.get("time").asString()).isEqualTo("2026-09-15T08:00:00.000Z");
    assertThat(lic.get("id").asString()).isEqualTo("9c0f5f8e-0d61-4c0e-9a55-1f7d6b1e2a3b");
    JsonNode upd = events.get(1);
    assertThat(upd.get("actor").get("id").asString()).isEqualTo("admin");
    assertThat(upd.get("details").get("actorDn").asString()).isEqualTo("uid=admin,dc=x");
    assertThat(upd.get("details").get("definitionId").asString()).isEqualTo("def-staff");
    assertThat(upd.get("details").get("mail.old").asString()).isEqualTo("a@x");
    assertThat(upd.get("details").get("mail.new").asString()).isEqualTo("[\"b@x\",\"c@x\"]");
    assertThat(upd.get("details").get("userPassword.new").asString()).isEqualTo("[redacted]");
    JsonNode login = events.get(2);
    assertThat(login.get("outcome").asString()).isEqualTo("failure");
    assertThat(login.get("severity").asString()).isEqualTo("warning");
    assertThat(login.get("actor").get("type").asString()).isEqualTo("anonymous");
    assertThat(login.get("actor").get("id").asString()).isEqualTo("bob");
    assertThat(login.get("source").get("ip").asString()).isEqualTo("10.0.0.9");
    assertThat(login.get("reason").asString()).isEqualTo("Wrong password");
    assertThat(login.get("time").asString()).isEqualTo("2026-10-02T10:00:00.123Z");
    assertThat(log.store().verify().intact()).isTrue();

    // New events continue the chain; a restart doesn't migrate again.
    log.event("system.started").system().success();
    AuditLog again = newLog(auditDir);
    assertThat(again.store().headSeq()).isEqualTo(4);
    assertThat(again.store().verify().intact()).isTrue();
  }

  @Test
  void redactsSecretsAndTruncatesDetails() {
    Map<String, Object> d = new LinkedHashMap<>();
    AuditDetails.put(d, "password", "hunter2");
    AuditDetails.put(d, "apiToken", "abc");
    AuditDetails.put(d, "userPassword.new", "x");
    AuditDetails.put(d, "emergencyPin", "1234");
    AuditDetails.put(d, "tokencode", "778899");
    AuditDetails.put(d, "mail", "a@x");
    AuditDetails.put(d, "long", "y".repeat(5000));
    AuditDetails.put(d, "count", 3);
    assertThat(d).containsEntry("password", "[redacted]").containsEntry("apiToken", "[redacted]")
        .containsEntry("userPassword.new", "[redacted]").containsEntry("emergencyPin", "[redacted]")
        .containsEntry("tokencode", "[redacted]").containsEntry("mail", "a@x").containsEntry("count", 3);
    assertThat((String) d.get("long")).hasSize(1000);
  }

  static AuditLog newLog(Path auditDir) {
    PortalProperties props = new PortalProperties();
    props.getAudit().setDir(auditDir.toString());
    StaticListableBeanFactory none = new StaticListableBeanFactory();
    return new AuditLog(MAPPER, props, none.getBeanProvider(BuildProperties.class), none.getBeanProvider(Licensing.class));
  }

  static Instant at(String s) {
    return Instant.parse(s);
  }
}
