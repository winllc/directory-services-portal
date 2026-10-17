package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.audit.AccessDenials;
import com.winllc.dsp.audit.AuditJson;
import com.winllc.dsp.audit.AuditLifecycle;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.PortalProperties;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The audit trail through the API, in the ens-audit/1 format. The store is shared by every test
 * in the context, so each test only looks at events recorded since it started.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditLogTest extends ApiTestSupport {
  @Autowired PortalProperties props;
  @Autowired AuditLog audit;
  @Autowired AccessDenials denials;
  @Autowired AuditLifecycle lifecycle;

  private String since;

  @BeforeEach
  void markStart() throws InterruptedException {
    denials.closeWindows();
    since = Instant.now().toString();
    Thread.sleep(2); // events in this test are strictly after the mark
  }

  /** Events since the test started, newest first. */
  private List<JsonNode> events(Client admin, String filters) throws Exception {
    MvcResult r = admin.get("/api/admin/audit?pageSize=200&from=" + URLEncoder.encode(since, StandardCharsets.UTF_8) + filters);
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    List<JsonNode> out = new ArrayList<>();
    body(r).get("events").forEach(out::add);
    return out;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String detail(JsonNode event, String key) {
    JsonNode v = event.get("details").get(key);
    return v == null || v.isNull() ? null : v.asString();
  }

  private String storeText() throws Exception {
    StringBuilder sb = new StringBuilder();
    try (Stream<Path> s = Files.walk(audit.store().dir())) {
      for (Path p : s.filter(Files::isRegularFile).toList()) sb.append(Files.readString(p));
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // Existing audit features, in the new format
  // ---------------------------------------------------------------------------

  @Test
  void recordsEntryChangesWithWhoAndWhatChanged() throws Exception {
    Client admin = login("admin");
    MvcResult created = admin.post("/api/directories/staff/entries", Map.of("values",
        Map.of("uid", "yan", "givenName", "Yan", "sn", "Yu", "cn", "Yan Yu", "mail", List.of("yan@example.com"))));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
    String dn = "uid=yan,ou=people," + B;

    assertThat(status(admin.put("/api/directories/staff/entry" + q(dn), Map.of("values", Map.of("mail", List.of("yan.yu@example.com")))))).isEqualTo(200);
    // Saving without changing anything is not recorded.
    assertThat(status(admin.put("/api/directories/staff/entry" + q(dn), Map.of("values", Map.of("mail", List.of("yan.yu@example.com")))))).isEqualTo(200);
    MvcResult renamed = admin.put("/api/directories/staff/entry" + q(dn), Map.of("values", Map.of("uid", "yan.yu")));
    String newDn = body(renamed).get("dn").asString();
    assertThat(status(admin.del("/api/directories/staff/entry" + q(newDn)))).isEqualTo(204);

    // The history follows the rename: either name finds everything.
    List<JsonNode> history = events(admin, "&action=entry&target=" + enc(dn));
    assertThat(history).extracting(e -> e.get("action").asString())
        .containsExactly("entry.delete", "entry.update", "entry.update", "entry.create");
    assertThat(events(admin, "&action=entry&target=" + enc(newDn))).extracting(e -> e.get("id").asString())
        .containsExactlyElementsOf(history.stream().map(e -> e.get("id").asString()).toList());

    JsonNode create = history.get(3);
    assertThat(create.get("schema").asString()).isEqualTo("ens-audit/1");
    assertThat(create.get("product").asString()).isEqualTo("directory-portal");
    assertThat(create.get("category").asString()).isEqualTo("data");
    assertThat(create.get("outcome").asString()).isEqualTo("success");
    assertThat(create.get("severity").asString()).isEqualTo("info");
    assertThat(create.get("actor").get("type").asString()).isEqualTo("user");
    assertThat(create.get("actor").get("id").asString()).isEqualTo("admin");
    assertThat(create.get("actor").get("roles").toString()).contains("ADMIN");
    assertThat(detail(create, "authMethod")).isEqualTo("password");
    assertThat(detail(create, "definitionId")).isEqualTo("def-staff");
    assertThat(create.get("target").get("name").asString()).isEqualTo("Yan Yu");
    assertThat(detail(create, "mail.new")).isEqualTo("yan@example.com");
    assertThat(create.get("source").get("ip").asString()).isNotBlank();
    assertThat(create.get("requestId").asString()).isNotBlank();
    assertThat(create.get("session").asString()).matches("[0-9a-f]{16}");
    assertThat(create.get("hash").asString()).matches("[0-9a-f]{64}");

    JsonNode update = history.get(2);
    assertThat(detail(update, "mail.old")).isEqualTo("yan@example.com");
    assertThat(detail(update, "mail.new")).isEqualTo("yan.yu@example.com");

    JsonNode rename = history.get(1);
    assertThat(rename.get("target").get("id").asString()).isEqualTo(newDn);
    assertThat(detail(rename, "previousDn")).isEqualTo(dn);

    JsonNode delete = history.get(0);
    assertThat(detail(delete, "mail.old")).isEqualTo("yan.yu@example.com");
    assertThat(delete.get("details").get("mail.new").isNull()).isTrue();
    assertThat(delete.get("seq").asLong()).isGreaterThan(create.get("seq").asLong());
  }

  @Test
  void recordsRefusedWritesAsDeniedAndEveryForbiddenResponse() throws Exception {
    Client alice = login("alice"); // can read Staff, not write it
    assertThat(status(alice.put("/api/directories/def-staff/entry" + q("uid=bob,ou=people," + B), Map.of("values", Map.of("title", "CEO"))))).isEqualTo(403);
    assertThat(status(alice.put("/api/me/def-staff/entry" + q(ALICE), Map.of("values", Map.of("mobile", List.of("+1 555 0100")))))).isEqualTo(200);

    Client admin = login("admin");
    List<JsonNode> events = events(admin, "&actor=alice&action=entry");
    assertThat(events).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString())
        .containsExactly("entry.self_update success", "entry.update denied");
    assertThat(events.get(1).get("reason").asString()).isEqualTo("You do not have write access to this directory");
    assertThat(events.get(1).get("severity").asString()).isEqualTo("warning");
    assertThat(events.get(1).get("details").has("title.new")).isFalse();

    JsonNode denied = events(admin, "&action=access.denied&actor=alice").get(0);
    assertThat(denied.get("category").asString()).isEqualTo("authorization");
    assertThat(detail(denied, "reasonCode")).isEqualTo("forbidden");
    assertThat(detail(denied, "method")).isEqualTo("PUT");
    assertThat(detail(denied, "path")).isEqualTo("/api/directories/def-staff/entry");
  }

  @Test
  void recordsSignInsSignOutsAndFailedAttempts() throws Exception {
    MvcResult bad = anonymous(json(post("/api/auth/login"), Map.of("username", "bob", "password", "wrong")));
    assertThat(status(bad)).isEqualTo(401);
    anonymous(json(post("/api/auth/login"), Map.of("username", "nobody", "password", "wrong")));
    Client bob = login("bob");
    assertThat(status(bob.post("/api/auth/logout", null))).isEqualTo(200);

    Client admin = login("admin");
    List<JsonNode> events = events(admin, "&action=auth&q=bob");
    assertThat(events).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString())
        .containsExactly("auth.logout success", "auth.login success", "auth.login failure");
    JsonNode failed = events.get(2);
    assertThat(failed.get("category").asString()).isEqualTo("authentication");
    assertThat(failed.get("severity").asString()).isEqualTo("warning");
    assertThat(failed.get("actor").get("type").asString()).isEqualTo("anonymous");
    assertThat(failed.get("actor").get("id").asString()).isEqualTo("bob");
    assertThat(failed.get("target").get("id").asString()).isEqualTo("bob");
    assertThat(failed.get("reason").asString()).isEqualTo("Wrong password");
    assertThat(detail(failed, "method")).isEqualTo("password");
    assertThat(failed.get("source").get("ip").asString()).isNotBlank();
    JsonNode success = events.get(1);
    assertThat(success.get("actor").get("id").asString()).isEqualTo("bob");
    assertThat(detail(success, "actorDn")).isEqualTo("uid=bob,ou=people," + B);
    assertThat(detail(success, "method")).isEqualTo("password");

    JsonNode unknown = events(admin, "&action=auth.login&outcome=failure&target=nobody").get(0);
    assertThat(unknown.get("reason").asString()).isEqualTo("Unknown user");
    // The old outcome name still filters.
    assertThat(events(admin, "&action=auth.login&outcome=failed&target=nobody")).hasSize(1);
  }

  @Test
  void recordsALockoutAfterRepeatedFailures() throws Exception {
    for (int i = 0; i < 9; i++) anonymous(json(post("/api/auth/login"), Map.of("username", "carol", "password", "nope" + i)));
    JsonNode lockout = events(login("admin"), "&action=auth.lockout").get(0);
    assertThat(lockout.get("severity").asString()).isEqualTo("alert");
    assertThat(lockout.get("actor").get("id").asString()).isEqualTo("carol");
    assertThat(lockout.get("details").get("failures").asInt()).isEqualTo(8);
  }

  @Test
  void recordsPermissionChangesAsOldAndNewValues() throws Exception {
    Client admin = login("admin");
    MvcResult g = admin.post("/api/admin/grants", Map.of("definitionId", "def-devices", "subjectType", "user", "subject", "bob", "access", "read"));
    admin.post("/api/admin/grants", Map.of("definitionId", "def-devices", "subjectType", "user", "subject", "bob", "access", "write"));
    assertThat(status(admin.del("/api/admin/grants/" + body(g).get("id").asString()))).isEqualTo(204);

    List<JsonNode> grants = events(admin, "&action=grant&definitionId=def-devices");
    assertThat(grants).extracting(e -> e.get("action").asString()).containsExactly("grant.revoke", "grant.save", "grant.save");
    assertThat(grants.get(1).get("category").asString()).isEqualTo("account");
    assertThat(grants.get(1).get("severity").asString()).isEqualTo("notice");
    assertThat(detail(grants.get(1), "access.old")).isEqualTo("read");
    assertThat(detail(grants.get(1), "access.new")).isEqualTo("write");
    assertThat(detail(grants.get(0), "subject")).isEqualTo("bob");
  }

  @Test
  void recordsSettingsChanges() throws Exception {
    Client admin = login("admin");
    JsonNode def = body(admin.get("/api/admin/definitions")).get(0);
    Map<String, Object> input = new HashMap<>(mapper.convertValue(def, Map.class));
    input.put("name", def.get("name").asString() + " (renamed)");
    assertThat(status(admin.put("/api/admin/definitions/" + def.get("id").asString(), input))).isEqualTo(200);
    JsonNode updated = events(admin, "&action=definition.update").get(0);
    assertThat(updated.get("severity").asString()).isEqualTo("notice");
    assertThat(detail(updated, "name.old")).isEqualTo(def.get("name").asString());
    assertThat(detail(updated, "name.new")).isEqualTo(def.get("name").asString() + " (renamed)");
    assertThat(updated.get("details").properties()).extracting(Map.Entry::getKey)
        .filteredOn(k -> k.endsWith(".new")).containsExactly("name.new");
  }

  @Test
  void isOnlyAvailableToAdministrators() throws Exception {
    Client alice = login("alice");
    assertThat(status(alice.get("/api/admin/audit"))).isEqualTo(403);
    assertThat(status(alice.get("/api/admin/audit/export?format=csv"))).isEqualTo(403);
    assertThat(status(alice.post("/api/admin/audit/verify", null))).isEqualTo(403);
    assertThat(status(alice.post("/api/admin/audit/tokens", Map.of("name", "mine")))).isEqualTo(403);
    assertThat(status(login("admin").get("/api/admin/audit?from=yesterday"))).isEqualTo(400);

    JsonNode denied = events(login("admin"), "&action=access.denied&actor=alice").get(0);
    assertThat(detail(denied, "reasonCode")).isEqualTo("role_required");
  }

  @Test
  void collapsesRepeatedIdenticalDenials() throws Exception {
    Client alice = login("alice");
    for (int i = 0; i < 4; i++) assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(403);
    Client admin = login("admin");
    List<JsonNode> first = events(admin, "&action=access.denied&actor=alice");
    assertThat(first).hasSize(1);
    assertThat(first.get(0).get("details").get("count").asInt()).isEqualTo(1);

    denials.closeWindows(); // what happens when the 60 second window closes
    List<JsonNode> all = events(admin, "&action=access.denied&actor=alice");
    assertThat(all).hasSize(2);
    assertThat(all.get(0).get("details").get("count").asInt()).isEqualTo(3);
    assertThat(all.get(0).get("details").get("collapsed").asBoolean()).isTrue();
  }

  @Test
  void recordsCsrfFailuresAsDenials() throws Exception {
    Client admin = login("admin");
    MvcResult r = admin.send(post("/api/admin/forms").contentType(MediaType.APPLICATION_JSON).content("{}"));
    assertThat(status(r)).isEqualTo(403);
    JsonNode denied = events(admin, "&action=access.denied&actor=admin").get(0);
    assertThat(detail(denied, "reasonCode")).isEqualTo("csrf");
  }

  // ---------------------------------------------------------------------------
  // License
  // ---------------------------------------------------------------------------

  @Test
  void recordsLicenseInstallsRejectionsAndModeChanges() throws Exception {
    lifecycle.checkLicenseMode();
    Client admin = login("admin");
    String license = TestLicenses.license().id("lic_audited").revision(1).features("metrics").sign();
    MvcResult ok = admin.send(post("/api/admin/license").header("X-DSP-Request", "1").contentType(MediaType.TEXT_PLAIN).content(license));
    assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
    admin.send(post("/api/admin/license").header("X-DSP-Request", "1").contentType(MediaType.TEXT_PLAIN).content("not a license"));

    JsonNode installed = events(admin, "&action=license.installed").get(0);
    assertThat(installed.get("category").asString()).isEqualTo("license");
    assertThat(installed.get("severity").asString()).isEqualTo("notice");
    assertThat(detail(installed, "licenseId")).isEqualTo("lic_audited");
    JsonNode rejected = events(admin, "&action=license.rejected").get(0);
    assertThat(rejected.get("outcome").asString()).isEqualTo("denied");
    assertThat(rejected.get("reason").asString()).startsWith("That isn't a valid ENS license");
    // The license text is never recorded.
    assertThat(storeText()).doesNotContain(license.substring(Math.max(0, license.length() - 60)));

    TestLicensing.remove();
    lifecycle.checkLicenseMode();
    JsonNode changed = events(admin, "&action=license.mode-changed").get(0);
    assertThat(detail(changed, "mode.old")).isEqualTo("FULL");
    assertThat(detail(changed, "mode.new")).isEqualTo("UNLICENSED");
    TestLicensing.use(TestLicenses.full());
    lifecycle.checkLicenseMode();
  }

  // ---------------------------------------------------------------------------
  // Admin page: paging, export, verification, status
  // ---------------------------------------------------------------------------

  @Test
  void pagesNewestFirstAcrossAllHistory() throws Exception {
    Client admin = login("admin");
    for (int i = 0; i < 5; i++) login("bob");
    String base = "/api/admin/audit?pageSize=2&action=auth&from=" + enc(since);
    JsonNode p1 = body(admin.get(base + "&page=1"));
    JsonNode p2 = body(admin.get(base + "&page=2"));
    assertThat(p1.get("total").asLong()).isGreaterThanOrEqualTo(6);
    assertThat(p1.get("events")).hasSize(2);
    assertThat(p2.get("events")).hasSize(2);
    long s1 = p1.get("events").get(1).get("seq").asLong();
    long s2 = p2.get("events").get(0).get("seq").asLong();
    assertThat(p1.get("events").get(0).get("seq").asLong()).isGreaterThan(s1);
    assertThat(s1).isGreaterThan(s2);
    assertThat(p1.get("truncated").asBoolean()).isFalse();
    // Filters: severity, category, source ip, free text.
    assertThat(body(admin.get("/api/admin/audit?category=authentication&severity=info&ip=127.&from=" + enc(since))).get("total").asLong())
        .isGreaterThanOrEqualTo(6);
    assertThat(body(admin.get("/api/admin/audit?category=license&from=" + enc(since))).get("total").asLong()).isZero();
    JsonNode actions = body(admin.get("/api/admin/audit/actions"));
    assertThat(actions.get("categories")).hasSize(8);
    assertThat(actions.get("actions").toString()).contains("\"auth.login\"", "\"entry.self_update\"", "\"audit.verified\"");
  }

  @Test
  void exportsTheCurrentFilterAsCsvAndNdjsonAndRecordsIt() throws Exception {
    Client admin = login("admin");
    login("bob");
    MvcResult csv = admin.get("/api/admin/audit/export?format=csv&action=auth.login&from=" + enc(since));
    assertThat(status(csv)).isEqualTo(200);
    assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
    String[] rows = csv.getResponse().getContentAsString().split("\r\n");
    assertThat(rows[0]).startsWith("seq,time,category,action,outcome,severity,actor_type,actor_id");
    assertThat(rows).hasSize(3);
    assertThat(rows[1]).contains(",auth.login,success,info,user,admin,");
    assertThat(rows[2]).contains(",user,bob,");

    MvcResult nd = admin.get("/api/admin/audit/export?format=ndjson&action=auth.login&from=" + enc(since));
    assertThat(nd.getResponse().getContentType()).startsWith("application/x-ndjson");
    String[] lines = nd.getResponse().getContentAsString().split("\n");
    assertThat(lines).hasSize(2);
    for (String l : lines) assertThat(AuditJson.hash(mapper.readTree(l).get("prevHash").asString(), AuditJson.bodyOf(l))).isEqualTo(mapper.readTree(l).get("hash").asString());

    List<JsonNode> exported = events(admin, "&action=audit.exported");
    assertThat(exported).hasSize(2);
    assertThat(detail(exported.get(1), "format")).isEqualTo("csv");
    assertThat(exported.get(1).get("details").get("count").asInt()).isEqualTo(2);
    assertThat(detail(exported.get(1), "filter.action")).isEqualTo("auth.login");

    // Downloads elsewhere in the portal are recorded too.
    assertThat(status(admin.get("/api/admin/schema/export"))).isEqualTo(200);
    assertThat(events(admin, "&action=data.exported").get(0).get("target").get("type").asString()).isEqualTo("schema");
  }

  @Test
  void verifiesTheChainAndReportsDeliveryStatus() throws Exception {
    Client admin = login("admin");
    JsonNode v = body(admin.post("/api/admin/audit/verify", null));
    assertThat(v.get("intact").asBoolean()).as(v.toString()).isTrue();
    assertThat(v.get("anchorSeq").asLong()).isEqualTo(1);
    assertThat(v.get("message").asString()).startsWith("Intact");
    JsonNode verified = events(admin, "&action=audit.verified").get(0);
    assertThat(verified.get("details").get("intact").asBoolean()).isTrue();

    JsonNode st = body(admin.get("/api/admin/audit/status"));
    assertThat(st.get("retentionDays").asInt()).isZero();
    assertThat(st.get("outputs")).extracting(o -> o.get("output").asString()).containsExactly("stdout", "file", "splunk", "syslog");
    assertThat(st.get("outputs").get(1).get("enabled").asBoolean()).isTrue();
    assertThat(st.get("outputs").get(2).get("enabled").asBoolean()).isFalse();
  }

  @Test
  void appendsChainedLinesToMonthlyJsonLinesFiles() throws Exception {
    login("admin");
    Path dir = Path.of(props.getDataDir()).resolve("audit");
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> p.getFileName().toString().matches("audit-\\d{4}-\\d{2}\\.jsonl")).sorted().toList();
    }
    assertThat(files).isNotEmpty();
    List<String> lines = Files.readAllLines(files.get(files.size() - 1));
    String last = lines.stream().filter(l -> l.contains("\"auth.login\"")).reduce((a, b) -> b).orElseThrow();
    assertThat(last).startsWith("{\"schema\":\"ens-audit/1\",\"id\":");
    JsonNode n = mapper.readTree(last);
    assertThat(n.get("actor").get("id").asString()).isEqualTo("admin");
    assertThat(AuditJson.hash(n.get("prevHash").asString(), AuditJson.bodyOf(last))).isEqualTo(n.get("hash").asString());
    assertThat(n.get("time").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
  }

  // ---------------------------------------------------------------------------
  // Pull API
  // ---------------------------------------------------------------------------

  @Test
  void pullApiServesEventsAfterACursorToValidTokensOnly() throws Exception {
    Client admin = login("admin");
    MvcResult created = admin.post("/api/admin/audit/tokens", Map.of("name", "Splunk"));
    assertThat(status(created)).isEqualTo(201);
    String token = body(created).get("secret").asString();
    String id = body(created).get("token").get("id").asString();
    assertThat(token).startsWith("ensat_").hasSizeGreaterThanOrEqualTo(6 + 43);
    // Shown once: the list never includes it.
    assertThat(admin.get("/api/admin/audit/tokens").getResponse().getContentAsString()).doesNotContain(token);

    long head = audit.store().headSeq();
    login("bob");
    MvcResult nd = anonymous(get("/api/audit/events?after=" + head + "&limit=10").header("Authorization", "Bearer " + token));
    assertThat(status(nd)).isEqualTo(200);
    assertThat(nd.getResponse().getContentType()).startsWith("application/x-ndjson");
    String[] lines = nd.getResponse().getContentAsString().split("\n");
    assertThat(lines.length).isGreaterThanOrEqualTo(1);
    long prev = head;
    for (String l : lines) {
      long seq = mapper.readTree(l).get("seq").asLong();
      assertThat(seq).isEqualTo(prev + 1);
      prev = seq;
    }
    assertThat(nd.getResponse().getContentAsString()).contains("\"auth.login\"");

    MvcResult js = anonymous(get("/api/audit/events?after=" + head + "&limit=1").header("Authorization", "Bearer " + token)
        .accept(MediaType.APPLICATION_JSON));
    JsonNode page = body(js);
    assertThat(page.get("events")).hasSize(1);
    assertThat(page.get("next").asLong()).isEqualTo(head + 1);
    // Pulls are not audited; the token's last use is.
    assertThat(events(admin, "&q=" + enc("/api/audit/events"))).isEmpty();
    assertThat(body(admin.get("/api/admin/audit/tokens")).get(0).get("lastUsedAt").isNull()).isFalse();

    assertThat(status(anonymous(get("/api/audit/events")))).isEqualTo(401);
    assertThat(status(anonymous(get("/api/audit/events").header("Authorization", "Bearer ensat_wrong")))).isEqualTo(401);
    assertThat(status(admin.get("/api/audit/events"))).as("a session is not a token").isEqualTo(401);
    assertThat(status(anonymous(get("/api/audit/events?limit=5000").header("Authorization", "Bearer " + token)))).isEqualTo(400);

    // Works while the portal is unlicensed.
    TestLicensing.remove();
    assertThat(status(anonymous(get("/api/audit/events?after=0&limit=1").header("Authorization", "Bearer " + token)))).isEqualTo(200);
    TestLicensing.use(TestLicenses.full());

    assertThat(status(admin.del("/api/admin/audit/tokens/" + id))).isEqualTo(200);
    assertThat(status(anonymous(get("/api/audit/events").header("Authorization", "Bearer " + token)))).isEqualTo(401);
    assertThat(events(admin, "&category=audit")).extracting(e -> e.get("action").asString())
        .filteredOn(a -> a.startsWith("audit.token")).containsExactly("audit.token-revoked", "audit.token-created");
  }

  // ---------------------------------------------------------------------------
  // Secrets
  // ---------------------------------------------------------------------------

  @Test
  void neverRecordsPasswordsOrTokensInAnySink(CapturedOutput output) throws Exception {
    Client admin = login("admin");
    MvcResult form = admin.post("/api/admin/forms", Map.of(
        "name", "Service accounts",
        "objectClasses", List.of("top", "inetOrgPerson"),
        "rdnAttribute", "uid",
        "fields", List.of(
            field("1", "uid", Map.of("required", true)),
            field("2", "cn", Map.of("required", true)),
            field("3", "sn", Map.of("required", true)),
            field("4", "userPassword", Map.of()))));
    assertThat(status(form)).as(form.getResponse().getContentAsString()).isEqualTo(201);
    Map<String, Object> def = new HashMap<>(Map.of(
        "name", "Service accounts", "slug", "service-accounts", "formId", body(form).get("form").get("id").asString(),
        "baseDn", "ou=people," + B, "scope", "one", "mode", "readwrite", "everyoneCanRead", false,
        "listAttributes", List.of("cn"), "searchAttributes", List.of("cn")));
    def.put("titleAttribute", "cn");
    def.put("createContainers", List.of("ou=people," + B));
    def.put("selfMatch", Map.of("type", "none"));
    assertThat(status(admin.post("/api/admin/definitions", def))).isEqualTo(201);

    String password = "Sup3r-S3cret-" + System.nanoTime();
    String reset = "R3set-S3cret-" + System.nanoTime();
    String wrong = "Wr0ng-S3cret-" + System.nanoTime();
    MvcResult created = admin.post("/api/directories/service-accounts/entries",
        Map.of("values", Map.of("uid", "svc-agent", "cn", "Agent", "sn", "Agent", "userPassword", password)));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
    String dn = "uid=svc-agent,ou=people," + B;
    assertThat(status(admin.put("/api/directories/service-accounts/entry" + q(dn), Map.of("values", Map.of("userPassword", reset))))).isEqualTo(200);
    assertThat(status(anonymous(json(post("/api/auth/login"), Map.of("username", "svc-agent", "password", wrong))))).isEqualTo(401);
    assertThat(status(anonymous(json(post("/api/auth/login"), Map.of("username", "svc-agent", "password", reset))))).isEqualTo(200);
    String token = body(admin.post("/api/admin/audit/tokens", Map.of("name", "secret test"))).get("secret").asString();
    anonymous(get("/api/audit/events?after=0").header("Authorization", "Bearer " + token));

    JsonNode event = events(admin, "&action=entry.create&target=" + enc(dn)).get(0);
    assertThat(detail(event, "userPassword.new")).isEqualTo("[redacted]");
    JsonNode resetEvent = events(admin, "&action=auth.password-reset").get(0);
    assertThat(resetEvent.get("target").get("id").asString()).isEqualTo(dn);
    assertThat(events(admin, "&action=auth.login&outcome=success&actor=svc-agent")).hasSize(1);

    String pulled = anonymous(get("/api/audit/events?after=0&limit=1000").header("Authorization", "Bearer " + token))
        .getResponse().getContentAsString();
    for (String sink : List.of(storeText(), Files.readString(AUDIT_FILE), output.getOut(), output.getAll(), pulled)) {
      assertThat(sink).isNotEmpty().doesNotContain(password).doesNotContain(reset).doesNotContain(wrong).doesNotContain(token);
    }
    // The stdout output is raw JSON lines (no log prefix).
    assertThat(output.getOut().lines().filter(l -> l.contains("\"auth.password-reset\"")).toList())
        .allMatch(l -> l.startsWith("{\"schema\":\"ens-audit/1\""));
  }

  private static Map<String, Object> field(String id, String attribute, Map<String, Object> extra) {
    Map<String, Object> f = new HashMap<>(Map.of(
        "id", id, "attribute", attribute, "label", attribute, "widget", "text",
        "multiValued", false, "required", false, "readOnly", false, "selfEditable", false));
    f.putAll(extra);
    return f;
  }
}
