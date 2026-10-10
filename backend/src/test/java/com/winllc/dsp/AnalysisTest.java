package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class AnalysisTest extends ApiTestSupport {
  static final String LEGACY = "ou=legacy," + B;

  @Autowired LdapDirectory directory;

  private int people;

  private void person(String parent, String uid, String mail, String title) {
    String cn = uid + " " + (++people);
    Map<String, List<String>> a = new LinkedHashMap<>();
    a.put("objectClass", List.of("top", "person", "organizationalPerson", "inetOrgPerson"));
    a.put("uid", List.of(uid));
    a.put("cn", List.of(cn));
    a.put("sn", List.of(uid));
    if (mail != null) a.put("mail", List.of(mail));
    if (title != null) a.put("title", List.of(title));
    directory.add("cn=" + cn + "," + parent, a);
  }

  /** A second copy of the people directory ("legacy HR system") with drift. */
  private void legacyDirectory() {
    directory.add(LEGACY, Map.of("objectClass", List.of("top", "organizationalUnit"), "ou", List.of("legacy")));
    person(LEGACY, "alice", "alice@example.com", "Senior Software Engineer");
    person(LEGACY, "bob", "BOB@Example.com", "Account Executive"); // differs only in case
    person(LEGACY, "carol", "carol@example.com", "VP Engineering"); // different title
    person(LEGACY, "zed", "zed@example.com", "Ghost"); // only here
    person(LEGACY, "dave", "dave@example.com", "Helpdesk Technician");
    person(LEGACY, "Dave", "dave2@example.com", "Helpdesk Technician"); // duplicate key (case-insensitive)
    store.update(d -> {
      DirectoryDefinition staff = d.definitions.stream().filter(x -> x.id().equals("def-staff")).findFirst().orElseThrow();
      d.definitions.add(new DirectoryDefinition("def-legacy", "Legacy HR", "legacy", null, staff.formId(), LEGACY, "one",
          "(objectClass=inetOrgPerson)", "readonly", false, staff.listAttributes(), staff.searchAttributes(), staff.titleAttribute(),
          List.of(), staff.selfMatch(), null, staff.createdAt(), staff.updatedAt()));
      return null;
    });
  }

  static Map<String, Object> compare(boolean ignoreCase) {
    Map<String, Object> options = new HashMap<>();
    options.put("attributes", List.of(Map.of("label", "E-mail", "attributes", List.of("mail")), Map.of("attributes", List.of("title"))));
    options.put("ignoreCase", ignoreCase);
    return Map.of("name", "Staff vs legacy", "type", "compare",
        "sources", List.of(Map.of("definitionId", "def-staff", "keyAttribute", "uid"), Map.of("definitionId", "def-legacy", "keyAttribute", "uid")),
        "compare", options);
  }

  static Map<String, JsonNode> byKey(JsonNode rows) {
    Map<String, JsonNode> out = new HashMap<>();
    rows.forEach(r -> out.put(r.get("key").asString(), r));
    return out;
  }

  @Test
  void comparesDirectoriesByKey() throws Exception {
    legacyDirectory();
    Client admin = login("admin");
    MvcResult r = admin.post("/api/admin/analysis/run", compare(true));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
    JsonNode c = awaitResult(admin, r).get("compare");

    JsonNode summary = c.get("summary");
    // 8 staff + zed; alice, bob, carol, dave are in both.
    assertThat(summary.get("keys").asLong()).isEqualTo(9);
    assertThat(summary.get("inAll").asLong()).isEqualTo(4);
    assertThat(summary.get("different").asLong()).isEqualTo(1);
    assertThat(summary.get("identical").asLong()).isEqualTo(3);
    assertThat(summary.get("partial").asLong()).isEqualTo(5);
    assertThat(summary.get("onlyIn").toString()).isEqualTo("[4,1]");
    assertThat(summary.get("duplicates").asLong()).isEqualTo(1);
    assertThat(c.get("sources").get(1).get("duplicateKeys").asLong()).isEqualTo(1);

    Map<String, JsonNode> rows = byKey(c.get("rows"));
    assertThat(rows).doesNotContainKey("alice").doesNotContainKey("bob");
    JsonNode carol = rows.get("carol");
    assertThat(carol.get("status").asString()).isEqualTo("different");
    assertThat(carol.get("differences")).hasSize(1);
    assertThat(carol.get("differences").get(0).get("label").asString()).isEqualTo("title");
    assertThat(carol.get("differences").get(0).get("values").toString()).isEqualTo("[[\"Engineering Manager\"],[\"VP Engineering\"]]");
    assertThat(rows.get("zed").get("status").asString()).isEqualTo("missing");
    assertThat(rows.get("zed").get("present").toString()).isEqualTo("[false,true]");
    assertThat(rows.get("dave").get("status").asString()).isEqualTo("duplicate");
    assertThat(rows.get("dave").get("dns").get(1)).hasSize(2);
    assertThat(c.get("attributes").get(1).get("differences").asLong()).isEqualTo(1);

    // Case-sensitive comparison also flags bob's e-mail.
    JsonNode strict = awaitResult(admin, admin.post("/api/admin/analysis/run", compare(false))).get("compare");
    assertThat(byKey(strict.get("rows")).get("bob").get("differences").get(0).get("label").asString()).isEqualTo("E-mail");
  }

  @Test
  void checksValuesForCorrectnessAndUniqueness() throws Exception {
    directory.modify(ALICE, List.of(new Modification(ModificationType.REPLACE, "mail", "bob@example.com")));
    directory.modify("uid=fatima,ou=people," + B, List.of(new Modification(ModificationType.REPLACE, "manager", "uid=nobody,ou=people," + B),
        new Modification(ModificationType.REPLACE, "acmeBadgeNumber", "X-1")));
    directory.modify("uid=george,ou=people," + B, List.of(new Modification(ModificationType.ADD, "mail", "not-an-email")));
    directory.modify("uid=hana,ou=partners," + B, List.of(new Modification(ModificationType.REPLACE, "acmeContractEnd", "someday")));
    // A partner sharing a staff member's uid (only a problem across sources).
    person("ou=partners," + B, "carol", "carol.partner@example.net", "Consultant");

    Client admin = login("admin");
    MvcResult r = admin.post("/api/admin/analysis/an-people-quality/run", null);
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(202);
    JsonNode q = awaitResult(admin, r).get("quality");
    Map<String, Long> violations = new HashMap<>();
    q.get("rules").forEach(rule -> violations.put(rule.get("id").asString(), rule.get("violations").asLong()));
    assertThat(violations).containsEntry("r-uid", 2L).containsEntry("r-mail-unique", 2L).containsEntry("r-mail-format", 1L)
        .containsEntry("r-manager", 1L).containsEntry("r-sponsor", 0L).containsEntry("r-badge", 1L).containsEntry("r-contract", 1L);
    // The staff form's own rules flag george's invalid e-mail too.
    assertThat(violations).containsEntry("form:def-staff:f-mail", 1L);

    List<String> messages = new ArrayList<>();
    q.get("issues").forEach(i -> messages.add(i.get("dn").asString() + " | " + i.get("message").asString()));
    assertThat(messages).anyMatch(m -> m.startsWith(ALICE) && m.contains("also used by uid=bob"));
    assertThat(messages).anyMatch(m -> m.startsWith("uid=fatima") && m.contains("does not exist"));
    assertThat(messages).anyMatch(m -> m.contains("uid=carol,ou=people") && m.contains("(Partners)"));
    assertThat(q.get("summary").get("entries").asLong()).isEqualTo(11);
    assertThat(q.get("summary").get("entriesWithIssues").asLong()).isGreaterThanOrEqualTo(6);

    // The run is remembered on the saved analysis, and its result kept for later.
    JsonNode saved = body(admin.get("/api/admin/analysis/an-people-quality"));
    assertThat(saved.get("lastRun").get("by").asString()).isEqualTo("admin");
    assertThat(saved.get("lastRun").get("issues").asLong()).isEqualTo(q.get("summary").get("issues").asLong());
    JsonNode latest = body(admin.get("/api/admin/analysis/an-people-quality/result"));
    assertThat(latest.get("stale").asBoolean()).isFalse();
    assertThat(latest.get("result").get("quality").get("summary")).isEqualTo(q.get("summary"));
    // The job shows in admin's list with a link back to the analysis.
    JsonNode job = body(admin.get("/api/jobs")).get(0);
    assertThat(job.get("link").asString()).isEqualTo("/admin/analysis/an-people-quality");
    assertThat(job.get("status").asString()).isEqualTo("succeeded");
  }

  @Test
  void validatesAndAuditsSavedAnalyses() throws Exception {
    Client admin = login("admin");
    Map<String, Object> oneSource = Map.of("name", "Bad", "type", "compare",
        "sources", List.of(Map.of("definitionId", "def-staff", "keyAttribute", "uid")));
    assertThat(body(admin.post("/api/admin/analysis", oneSource)).get("details").has("sources")).isTrue();

    Map<String, Object> noKey = Map.of("name", "Bad", "type", "compare",
        "sources", List.of(Map.of("definitionId", "def-staff", "keyAttribute", "uid"), Map.of("definitionId", "def-groups")));
    assertThat(body(admin.post("/api/admin/analysis", noKey)).get("details").has("sources[1].keyAttribute")).isTrue();

    Map<String, Object> badRegex = Map.of("name", "Bad", "type", "quality", "sources", List.of(Map.of("definitionId", "def-staff")),
        "quality", Map.of("rules", List.of(Map.of("attribute", "uid", "type", "pattern", "pattern", "(oops"))));
    assertThat(body(admin.post("/api/admin/analysis", badRegex)).get("details").has("quality.rules[0].pattern")).isTrue();

    Map<String, Object> ok = Map.of("name", "Groups have owners", "type", "quality", "sources", List.of(Map.of("definitionId", "def-groups")),
        "quality", Map.of("rules", List.of(Map.of("attribute", "owner", "type", "required"), Map.of("attribute", "member", "type", "dnExists"))));
    MvcResult created = admin.post("/api/admin/analysis", ok);
    assertThat(status(created)).isEqualTo(201);
    String id = body(created).get("id").asString();
    JsonNode rules = body(created).get("quality").get("rules");
    assertThat(rules.get(0).get("id").asString()).isNotEqualTo(rules.get(1).get("id").asString());

    assertThat(status(admin.get("/api/admin/analysis/" + id + "/result"))).isEqualTo(204);
    JsonNode run = awaitResult(admin, admin.post("/api/admin/analysis/" + id + "/run", null)).get("quality");
    assertThat(run.get("rules").get(0).get("label").asString()).isEqualTo("owner is required");
    assertThat(run.get("rules").get(1).get("violations").asLong()).isZero();

    JsonNode audit = body(admin.get("/api/admin/audit?action=analysis.create"));
    assertThat(audit.get("events").get(0).get("target").get("id").asString()).isEqualTo(id);
    assertThat(status(admin.del("/api/admin/analysis/" + id))).isEqualTo(204);

    Client alice = login("alice");
    assertThat(status(alice.get("/api/admin/analysis"))).isEqualTo(403);
    assertThat(status(alice.post("/api/admin/analysis/run", ok))).isEqualTo(403);
  }
}
