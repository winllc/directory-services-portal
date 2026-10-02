package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * The audit log is shared by every test in the context, so each test only looks at events
 * recorded since it started.
 */
class AuditLogTest extends ApiTestSupport {
  @Autowired PortalProperties props;

  private String since;

  @BeforeEach
  void markStart() throws InterruptedException {
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

  private static JsonNode change(JsonNode event, String attribute) {
    for (JsonNode c : event.get("changes")) if (c.get("attribute").asString().equalsIgnoreCase(attribute)) return c;
    throw new AssertionError("no change to " + attribute + " in " + event);
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null) array.forEach(n -> out.add(n.asString()));
    return out;
  }

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
    assertThat(create.get("outcome").asString()).isEqualTo("success");
    assertThat(create.get("actor").get("username").asString()).isEqualTo("admin");
    assertThat(create.get("actor").get("authMethod").asString()).isEqualTo("password");
    assertThat(create.get("definitionId").asString()).isEqualTo("def-staff");
    assertThat(create.get("target").get("name").asString()).isEqualTo("Yan Yu");
    assertThat(texts(change(create, "mail").get("after"))).containsExactly("yan@example.com");

    JsonNode update = history.get(2);
    assertThat(texts(change(update, "mail").get("before"))).containsExactly("yan@example.com");
    assertThat(texts(change(update, "mail").get("after"))).containsExactly("yan.yu@example.com");

    JsonNode rename = history.get(1);
    assertThat(rename.get("target").get("id").asString()).isEqualTo(newDn);
    assertThat(rename.get("details").get("previousDn").asString()).isEqualTo(dn);

    JsonNode delete = history.get(0);
    assertThat(texts(change(delete, "mail").get("before"))).containsExactly("yan.yu@example.com");
    assertThat(texts(change(delete, "mail").get("after"))).isEmpty();
  }

  @Test
  void recordsRefusedWritesAsDenied() throws Exception {
    Client alice = login("alice"); // can read Staff, not write it
    assertThat(status(alice.put("/api/directories/def-staff/entry" + q("uid=bob,ou=people," + B), Map.of("values", Map.of("title", "CEO"))))).isEqualTo(403);
    assertThat(status(alice.put("/api/me/def-staff/entry" + q(ALICE), Map.of("values", Map.of("mobile", List.of("+1 555 0100")))))).isEqualTo(200);

    List<JsonNode> events = events(login("admin"), "&actor=alice&action=entry");
    assertThat(events).extracting(e -> e.get("action").asString() + " " + e.get("outcome").asString())
        .containsExactly("entry.self_update success", "entry.update denied");
    assertThat(events.get(1).get("message").asString()).isEqualTo("You do not have write access to this directory");
    assertThat(events.get(1).has("changes")).isFalse();
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
        .containsExactly("auth.logout success", "auth.login success", "auth.login failed");
    JsonNode failed = events.get(2);
    assertThat(failed.has("actor")).isFalse();
    assertThat(failed.get("target").get("id").asString()).isEqualTo("bob");
    assertThat(failed.get("message").asString()).isEqualTo("Wrong password");
    assertThat(failed.get("sourceAddress").asString()).isNotBlank();
    assertThat(events.get(1).get("actor").get("dn").asString()).isEqualTo("uid=bob,ou=people," + B);

    JsonNode unknown = events(admin, "&action=auth.login&outcome=failed&target=nobody").get(0);
    assertThat(unknown.get("message").asString()).isEqualTo("Unknown user");
  }

  @Test
  void recordsConfigurationChangesAsDiffs() throws Exception {
    Client admin = login("admin");
    MvcResult g = admin.post("/api/admin/grants", Map.of("definitionId", "def-devices", "subjectType", "user", "subject", "bob", "access", "read"));
    admin.post("/api/admin/grants", Map.of("definitionId", "def-devices", "subjectType", "user", "subject", "bob", "access", "write"));
    assertThat(status(admin.del("/api/admin/grants/" + body(g).get("id").asString()))).isEqualTo(204);

    List<JsonNode> grants = events(admin, "&action=grant&definitionId=def-devices");
    assertThat(grants).extracting(e -> e.get("action").asString()).containsExactly("grant.revoke", "grant.save", "grant.save");
    assertThat(texts(change(grants.get(1), "access").get("before"))).containsExactly("read");
    assertThat(texts(change(grants.get(1), "access").get("after"))).containsExactly("write");
    assertThat(grants.get(0).get("details").get("subject").asString()).isEqualTo("bob");

    JsonNode def = body(admin.get("/api/admin/definitions")).get(0);
    Map<String, Object> input = new HashMap<>(mapper.convertValue(def, Map.class));
    input.put("name", def.get("name").asString() + " (renamed)");
    assertThat(status(admin.put("/api/admin/definitions/" + def.get("id").asString(), input))).isEqualTo(200);
    JsonNode updated = events(admin, "&action=definition.update").get(0);
    assertThat(updated.get("changes")).hasSize(1);
    assertThat(texts(change(updated, "name").get("after"))).containsExactly(def.get("name").asString() + " (renamed)");
  }

  @Test
  void redactsSecretAttributes() throws Exception {
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

    MvcResult created = admin.post("/api/directories/service-accounts/entries",
        Map.of("values", Map.of("uid", "svc-agent", "cn", "Agent", "sn", "Agent", "userPassword", "s3cret!")));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);

    JsonNode event = events(admin, "&action=entry.create&target=" + enc("uid=svc-agent,ou=people," + B)).get(0);
    assertThat(texts(change(event, "userPassword").get("after"))).containsExactly("(redacted)");
    assertThat(event.toString()).doesNotContain("s3cret!");
  }

  @Test
  void appendsEventsToMonthlyJsonLinesFiles() throws Exception {
    login("admin");
    Path dir = Path.of(props.getDataDir()).resolve("audit");
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.toList();
    }
    assertThat(files).extracting(p -> p.getFileName().toString()).anyMatch(n -> n.matches("audit-\\d{4}-\\d{2}\\.jsonl"));
    String last = files.stream().map(p -> {
      try {
        List<String> lines = Files.readAllLines(p);
        return lines.get(lines.size() - 1);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }).filter(l -> l.contains("\"auth.login\"")).findFirst().orElseThrow();
    assertThat(mapper.readTree(last).get("actor").get("username").asString()).isEqualTo("admin");
  }

  @Test
  void isOnlyAvailableToAdministrators() throws Exception {
    assertThat(status(login("alice").get("/api/admin/audit"))).isEqualTo(403);
    assertThat(status(login("admin").get("/api/admin/audit?from=yesterday"))).isEqualTo(400);
  }

  private static Map<String, Object> field(String id, String attribute, Map<String, Object> extra) {
    Map<String, Object> f = new HashMap<>(Map.of(
        "id", id, "attribute", attribute, "label", attribute, "widget", "text",
        "multiValued", false, "required", false, "readOnly", false, "selfEditable", false));
    f.putAll(extra);
    return f;
  }
}
