package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.metrics.ValueTransform;
import com.winllc.dsp.util.SafeRegex;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class MetricsTest extends ApiTestSupport {
  @Autowired LdapDirectory directory;

  static Map<String, Object> widget(String id, String definitionId, String kind, Object... extra) {
    Map<String, Object> w = new HashMap<>(Map.of("id", id, "title", id.isEmpty() ? "Chart" : id, "definitionId", definitionId, "kind", kind,
        "chart", "table"));
    for (int i = 0; i < extra.length; i += 2) w.put((String) extra[i], extra[i + 1]);
    return w;
  }

  JsonNode evaluate(Client c, Object... widgets) throws Exception {
    return awaitResult(c, c.post("/api/metrics/evaluate", Map.of("widgets", List.of(widgets), "refresh", true)));
  }

  static Map<String, Double> rows(JsonNode result) {
    Map<String, Double> out = new java.util.LinkedHashMap<>();
    for (JsonNode row : result.get("rows")) out.put(row.get("label").asString(), row.get("value").asDouble());
    return out;
  }

  @Test
  void overviewDescribesADirectoryOutOfTheBox() throws Exception {
    Client admin = login("admin");
    JsonNode body = awaitResult(admin, admin.get("/api/metrics/overview/staff"));
    assertThat(body.get("definition").get("id").asString()).isEqualTo("def-staff");
    JsonNode results = body.get("results");
    assertThat(results.get("ovw-count").get("result").get("total").asLong()).isEqualTo(8);
    Map<String, Double> departments = rows(results.get("ovw-dist-f-departmentnumber").get("result"));
    assertThat(departments).containsEntry("Engineering", 2.0).containsEntry("IT", 2.0).containsEntry("Sales", 1.0);
    // DN-valued drop downs are labelled with the option labels (people's names), not DNs.
    assertThat(rows(results.get("ovw-dist-f-manager").get("result"))).containsEntry("Carol Chen", 2.0).containsEntry("(none)", 6.0);
    assertThat(rows(results.get("ovw-complete").get("result"))).containsEntry("E-mail", 8.0).containsEntry("Mobile", 0.0);
    List<String> ids = new ArrayList<>();
    body.get("widgets").forEach(w -> ids.add(w.get("id").asString()));
    assertThat(ids).contains("ovw-count", "ovw-created", "ovw-complete");
    // Staff is one level deep, so there is no container breakdown.
    assertThat(ids).doesNotContain("ovw-containers");
  }

  @Test
  void distributionsTransformFoldAndSplit() throws Exception {
    Client admin = login("admin");
    JsonNode r = evaluate(admin,
        widget("domains", "def-whitepages", "distribution", "attribute", "mail", "transform", "emailDomain"),
        widget("top2", "def-staff", "distribution", "attribute", "departmentNumber", "limit", 2, "includeMissing", false),
        widget("split", "def-staff", "distribution", "attribute", "departmentNumber", "splitBy", "acmeOfficeLocation"),
        widget("containers", "def-whitepages", "distribution", "attribute", "dn", "transform", "dnParent"),
        widget("prefix", "def-staff", "distribution", "attribute", "acmeCostCenter", "transform", "prefix", "transformArg", "4"));

    assertThat(rows(r.get("domains").get("result"))).containsEntry("example.com", 8.0).containsEntry("partner.example.net", 1.0);

    JsonNode top2 = r.get("top2").get("result");
    assertThat(top2.get("rows")).hasSize(3);
    assertThat(top2.get("rows").get(2).get("other").asBoolean()).isTrue();
    assertThat(top2.get("rows").get(2).get("value").asDouble()).isEqualTo(4.0);
    assertThat(top2.get("distinctValues").asInt()).isEqualTo(6);

    JsonNode split = r.get("split").get("result");
    List<String> series = new ArrayList<>();
    split.get("series").forEach(s -> series.add(s.get("label").asString()));
    assertThat(series).containsExactly("HQ", "Denver", "Austin", "Remote");
    // Engineering (row 0): both at HQ. Every row's segments add up to the row's count.
    assertThat(split.get("matrix").get(0).toString()).isEqualTo("[2,0,0,0]");
    for (int i = 0; i < split.get("rows").size(); i++) {
      long sum = 0;
      for (JsonNode cell : split.get("matrix").get(i)) sum += cell.asLong();
      assertThat(sum).isEqualTo((long) split.get("rows").get(i).get("value").asDouble());
    }

    assertThat(rows(r.get("containers").get("result"))).containsEntry("ou=people,dc=example,dc=com", 8.0)
        .containsEntry("ou=partners,dc=example,dc=com", 2.0);
    assertThat(rows(r.get("prefix").get("result"))).containsEntry("CC-2", 3.0).containsEntry("CC-1", 2.0);
  }

  @Test
  void timelinesAndNumericStatistics() throws Exception {
    Client admin = login("admin");
    JsonNode r = evaluate(admin,
        widget("created", "def-staff", "timeline", "attribute", "createTimestamp", "bucket", "year", "cumulative", true),
        widget("members", "def-groups", "numeric", "attribute", "member", "measure", "valueCount"),
        widget("badges", "def-staff", "numeric", "attribute", "acmeBadgeNumber"),
        widget("complete", "def-staff", "completeness", "attributes", List.of("mail", "mobile")));

    JsonNode created = r.get("created").get("result");
    assertThat(created.get("rows")).hasSize(1);
    assertThat(created.get("rows").get(0).get("key").asString()).isEqualTo(String.valueOf(LocalDate.now().getYear()));
    assertThat(created.get("rows").get(0).get("value").asDouble()).isEqualTo(8.0);

    JsonNode members = r.get("members").get("result");
    assertThat(members.get("stats").get("count").asLong()).isEqualTo(5);
    assertThat(members.get("stats").get("sum").asDouble()).isEqualTo(8.0);
    assertThat(rows(members)).containsEntry("1", 3.0).containsEntry("2", 1.0).containsEntry("3", 1.0);

    // "B-1000" is not a number: reported, not counted.
    JsonNode badges = r.get("badges").get("result");
    assertThat(badges.get("stats").get("invalid").asLong()).isEqualTo(8);
    assertThat(badges.get("warnings").get(0).asString()).contains("not numbers");

    JsonNode complete = r.get("complete").get("result");
    assertThat(complete.get("rows").get(0).get("share").asDouble()).isEqualTo(1.0);
    assertThat(complete.get("rows").get(1).get("share").asDouble()).isEqualTo(0.0);
  }

  @Test
  void metricsOnlyReadWhatTheUserMaySee() throws Exception {
    // bob can't read the staff directory at all.
    Client bob = login("bob");
    assertThat(status(bob.get("/api/metrics/overview/staff"))).isEqualTo(403);
    JsonNode denied = evaluate(bob, widget("x", "def-staff", "count"));
    assertThat(denied.get("x").get("forbidden").asBoolean()).isTrue();
    List<String> summary = new ArrayList<>();
    awaitResult(bob, bob.get("/api/metrics/summary")).forEach(s -> summary.add(s.get("id").asString()));
    assertThat(summary).doesNotContain("def-staff").contains("def-whitepages", "def-partners");

    // alice reads staff, but only the attributes the directory shows.
    Client alice = login("alice");
    JsonNode r = evaluate(alice,
        widget("ok", "def-staff", "distribution", "attribute", "departmentNumber"),
        widget("time", "def-staff", "timeline", "attribute", "modifyTimestamp"),
        widget("hidden", "def-staff", "distribution", "attribute", "employeeType"),
        widget("probe", "def-staff", "count", "filter", "(employeeType=Full*)"));
    assertThat(r.get("ok").has("result")).isTrue();
    assertThat(r.get("time").has("result")).isTrue();
    assertThat(r.get("hidden").get("details").get("attribute").asString()).contains("not shown");
    assertThat(r.get("probe").get("details").get("filter").asString()).contains("not shown");

    // Administrators may use any attribute, but never secrets, in metrics or filters.
    Client admin = login("admin");
    JsonNode a = evaluate(admin,
        widget("any", "def-staff", "distribution", "attribute", "employeeType"),
        widget("secret", "def-staff", "distribution", "attribute", "userPassword"),
        widget("secretFilter", "def-staff", "count", "filter", "(|(cn=*)(userPassword=p*))"),
        widget("anyAttr", "def-staff", "count", "filter", "(:caseIgnoreMatch:=x)"));
    assertThat(rows(a.get("any").get("result"))).containsEntry("Full time", 8.0);
    assertThat(a.get("secret").get("error").asString()).contains("can't be used");
    assertThat(a.get("secretFilter").get("details").get("filter").asString()).contains("can't be used");
    assertThat(a.get("anyAttr").has("error")).isTrue();
  }

  @Test
  void invalidWidgetsFailAloneWithFieldDetails() throws Exception {
    Client admin = login("admin");
    JsonNode r = evaluate(admin,
        widget("good", "def-staff", "count"),
        widget("badRegex", "def-staff", "distribution", "attribute", "cn", "transform", "regex", "transformArg", "(unclosed"),
        widget("badPrefix", "def-staff", "distribution", "attribute", "cn", "transform", "prefix", "transformArg", "x"),
        widget("missing", "def-nope", "count"));
    assertThat(r.get("good").get("result").get("total").asLong()).isEqualTo(8);
    assertThat(r.get("badRegex").get("details").has("transformArg")).isTrue();
    assertThat(r.get("badPrefix").get("details").get("transformArg").asString()).contains("leading characters");
    assertThat(r.get("missing").get("error").asString()).contains("not found");
    // Request-level validation: unknown kinds and charts are rejected outright.
    assertThat(status(admin.post("/api/metrics/evaluate", Map.of("widgets", List.of(widget("x", "def-staff", "pie")))))).isEqualTo(400);
  }

  @Test
  void dashboardsAreManagedByAdminsAndSharedWithReaders() throws Exception {
    Client admin = login("admin");
    Client alice = login("alice");
    List<String> visible = new ArrayList<>();
    body(alice.get("/api/metrics/dashboards")).forEach(d -> visible.add(d.get("id").asString()));
    assertThat(visible).contains("dash-workforce", "dash-it");

    Map<String, Object> input = Map.of("name", "Private", "shared", false,
        "widgets", List.of(widget("", "def-staff", "count"), widget("", "def-devices", "count")));
    MvcResult created = admin.post("/api/admin/dashboards", input);
    assertThat(status(created)).isEqualTo(201);
    JsonNode dash = body(created);
    String id = dash.get("id").asString();
    // Widget ids are assigned and unique.
    assertThat(dash.get("widgets").get(0).get("id").asString()).isNotBlank()
        .isNotEqualTo(dash.get("widgets").get(1).get("id").asString());

    assertThat(status(alice.get("/api/metrics/dashboards/" + id))).isEqualTo(404);
    assertThat(status(alice.post("/api/admin/dashboards", input))).isEqualTo(403);

    Map<String, Object> shared = new HashMap<>(input);
    shared.put("shared", true);
    assertThat(status(admin.put("/api/admin/dashboards/" + id, shared))).isEqualTo(200);
    // alice (engineering) reads staff; devices only through her user grant.
    JsonNode results = awaitResult(alice, alice.get("/api/metrics/dashboards/" + id + "/results")).get("results");
    assertThat(results.size()).isEqualTo(2);
    results.forEach(o -> assertThat(o.has("result")).isTrue());
    Client bob = login("bob");
    JsonNode bobs = awaitResult(bob, bob.get("/api/metrics/dashboards/" + id + "/results")).get("results");
    bobs.forEach(o -> assertThat(o.get("forbidden").asBoolean()).isTrue());

    Map<String, Object> unknown = Map.of("name", "Bad", "widgets", List.of(widget("w", "def-nope", "count")));
    MvcResult bad = admin.post("/api/admin/dashboards", unknown);
    assertThat(status(bad)).isEqualTo(400);
    assertThat(body(bad).get("details").has("widgets[0].definitionId")).isTrue();

    assertThat(status(admin.del("/api/admin/dashboards/" + id))).isEqualTo(204);
    assertThat(status(admin.get("/api/metrics/dashboards/" + id))).isEqualTo(404);
  }

  @Test
  void scansPageThroughLargeDirectoriesAndStopAtTheLimit() throws Exception {
    for (int i = 0; i < 1203; i++) {
      Map<String, List<String>> attrs = new java.util.LinkedHashMap<>();
      attrs.put("objectClass", List.of("top", "acmeDevice"));
      attrs.put("cn", List.of("BULK-" + i));
      attrs.put("acmeDeviceSerial", List.of("S" + i));
      attrs.put("acmeDeviceType", List.of(i % 3 == 0 ? "Laptop" : "Phone"));
      directory.add("cn=BULK-" + i + ",ou=devices," + B, attrs);
    }
    Client admin = login("admin");
    JsonNode r = evaluate(admin, widget("n", "def-devices", "count"), widget("t", "def-devices", "distribution", "attribute", "acmeDeviceType"));
    assertThat(r.get("n").get("result").get("total").asLong()).isEqualTo(1208);
    assertThat(r.get("n").get("result").get("truncated").asBoolean()).isFalse();
    assertThat(rows(r.get("t").get("result"))).containsEntry("Laptop", 404.0);

    List<String> seen = new ArrayList<>();
    LdapDirectory.ScanResult limited = directory.scan("ou=devices," + B, SearchScope.ONE, Filter.createPresenceFilter("objectClass"),
        List.of("cn"), 600, e -> seen.add(e.dn()));
    assertThat(limited.entries()).isEqualTo(600);
    assertThat(limited.truncated()).isTrue();
    assertThat(seen).hasSize(600).doesNotHaveDuplicates();
  }

  @Test
  void userRegexesRunWithATimeBudget() {
    // Polynomial backtracking: without the budget this runs for minutes.
    SafeRegex evil = SafeRegex.compile("(.*a){10}x", false, false);
    String input = "a".repeat(44);
    assertThatThrownBy(() -> evil.extract(input)).isInstanceOf(SafeRegex.TimeoutException.class);
    assertThat(SafeRegex.compile("^([A-Za-z]+)", false, false).extract("Windows 11")).isEqualTo("Windows");
    assertThatThrownBy(() -> SafeRegex.compile("(", false, false)).hasMessageContaining("Invalid pattern");
  }

  @Test
  void parsesDirectoryDates() {
    assertThat(ValueTransform.parseDate("20260314093000Z")).isEqualTo(LocalDate.of(2026, 3, 14));
    assertThat(ValueTransform.parseDate("20260314093000.123-0500")).isEqualTo(LocalDate.of(2026, 3, 14));
    assertThat(ValueTransform.parseDate("2027-06-30")).isEqualTo(LocalDate.of(2027, 6, 30));
    assertThat(ValueTransform.parseDate("2026-03-14T23:30:00-05:00")).isEqualTo(LocalDate.of(2026, 3, 15));
    assertThat(ValueTransform.parseDate("soon")).isNull();
    assertThat(ValueTransform.of("week", null).apply("2026-01-01").key()).isEqualTo("2026-W01");
    assertThat(ValueTransform.of("quarter", null).apply("2026-05-20").label()).isEqualTo("Q2 2026");
  }
}
