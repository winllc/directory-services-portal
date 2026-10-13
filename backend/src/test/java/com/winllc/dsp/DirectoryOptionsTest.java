package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** Tuning of drop downs backed by a directory search. */
class DirectoryOptionsTest extends ApiTestSupport {
  static final String PEOPLE = "ou=people," + B;

  @Autowired LdapDirectory directory;

  static Map<String, Object> source(Object... extra) {
    Map<String, Object> s = new LinkedHashMap<>(Map.of("type", "ldap", "baseDn", PEOPLE, "scope", "one",
        "filter", "(objectClass=inetOrgPerson)", "valueAttribute", "dn", "labelAttribute", "cn"));
    for (int i = 0; i < extra.length; i += 2) s.put((String) extra[i], extra[i + 1]);
    return s;
  }

  JsonNode preview(Client admin, Map<String, Object> src, String q) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("source", src);
    if (q != null) body.put("q", q);
    MvcResult r = admin.post("/api/admin/forms/preview-options", body);
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    return body(r);
  }

  static List<String> labels(JsonNode page) {
    List<String> out = new ArrayList<>();
    page.get("options").forEach(o -> out.add(o.get("label").asString()));
    return out;
  }

  @Test
  void matchingCanBeTunedPerAttributeAndMode() throws Exception {
    Client admin = login("admin");
    // Contains (the default), on the label by default.
    assertThat(labels(preview(admin, source("mode", "search", "minChars", 1), "anders"))).containsExactly("Alice Anderson");
    // Starts with: "car" matches Carol, "arol" does not.
    assertThat(labels(preview(admin, source("mode", "search", "match", "startsWith", "minChars", 1), "car"))).containsExactly("Carol Chen");
    assertThat(labels(preview(admin, source("mode", "search", "match", "startsWith", "minChars", 1), "arol"))).isEmpty();
    // Exact: only whole values.
    assertThat(labels(preview(admin, source("mode", "search", "match", "exact", "searchAttributes", List.of("uid"), "minChars", 1), "bob")))
        .containsExactly("Bob Baker");
    // Other attributes: search by e-mail; every typed word must match one of them.
    JsonNode byMail = preview(admin, source("mode", "search", "searchAttributes", List.of("cn", "mail"), "minChars", 1), "erin@example");
    assertThat(labels(byMail)).containsExactly("Erin Evans");
    assertThat(byMail.get("filter").asString()).contains("(mail=*erin@example*)").contains("(objectClass=inetOrgPerson)");
    assertThat(labels(preview(admin, source("mode", "search", "searchAttributes", List.of("cn", "title"), "minChars", 1), "engineer manager")))
        .containsExactly("Carol Chen");
    // Too few characters: nothing yet.
    assertThat(labels(preview(admin, source("mode", "search"), "a"))).isEmpty();
  }

  @Test
  void labelsDescriptionsSortingAndLimitsCanBeTuned() throws Exception {
    Client admin = login("admin");
    JsonNode page = preview(admin, source("labelTemplate", "{cn} ({acmePronouns})", "descriptionAttribute", "title", "sortAttribute", "uid",
        "limit", 3), null);
    // Sorted by uid: admin, alice, bob; three of eight, so truncated.
    assertThat(labels(page)).containsExactly("Ada Admin (she/her)", "Alice Anderson (she/her)", "Bob Baker");
    assertThat(page.get("options").get(1).get("description").asString()).isEqualTo("Senior Software Engineer");
    assertThat(page.get("truncated").asBoolean()).isTrue();
    // Bad settings are reported, not thrown.
    JsonNode bad = preview(admin, source("baseDn", "ou=nowhere," + B), null);
    assertThat(bad.get("error").asString()).isNotBlank();
    assertThat(status(login("alice").post("/api/admin/forms/preview-options", Map.of("source", source())))).isEqualTo(403);
  }

  @Test
  void valuesBeyondTheLimitAreValidatedByLookup() throws Exception {
    // 30 more people, and a manager drop down that lists only 5: any of them must still be accepted.
    for (int i = 0; i < 30; i++) {
      Map<String, List<String>> a = new LinkedHashMap<>();
      a.put("objectClass", List.of("top", "person", "organizationalPerson", "inetOrgPerson"));
      a.put("uid", List.of("p" + i));
      a.put("cn", List.of("Person " + i));
      a.put("sn", List.of("P" + i));
      directory.add("uid=p" + i + "," + PEOPLE, a);
    }
    store.update(d -> {
      d.forms.replaceAll(f -> !f.id().equals("form-person") ? f : withManagerSource(f,
          new DropdownSource.Ldap(PEOPLE, "one", "(objectClass=inetOrgPerson)", "dn", "cn", "list", null, null, null, 5, null, null, null, 0)));
      return null;
    });
    Client admin = login("admin");
    JsonNode listed = body(admin.get("/api/directories/staff/options/f-manager"));
    assertThat(listed.get("options")).hasSize(5);
    assertThat(listed.get("truncated").asBoolean()).isTrue();
    // Typing narrows a long list on the server.
    assertThat(labels(body(admin.get("/api/directories/staff/options/f-manager?q=Person%2027")))).containsExactly("Person 27");

    MvcResult ok = admin.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", Map.of("manager", "uid=p27," + PEOPLE)));
    assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
    MvcResult bad = admin.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", Map.of("manager", "uid=nobody," + PEOPLE)));
    assertThat(status(bad)).isEqualTo(400);
    assertThat(body(bad).get("details").get("manager").asString()).contains("not an allowed value");
    // Outside the drop down's search base is not an option either.
    MvcResult outside = admin.put("/api/directories/staff/entry" + q(ALICE), Map.of("values", Map.of("manager", "cn=LT-0001,ou=devices," + B)));
    assertThat(status(outside)).isEqualTo(400);
  }

  static FormDefinition withManagerSource(FormDefinition f, DropdownSource source) {
    List<FormField> fields = f.fields().stream().map(x -> !x.id().equals("f-manager") ? x
        : new FormField(x.id(), x.attribute(), x.label(), x.helpText(), x.widget(), x.multiValued(), x.required(), x.readOnly(), x.selfEditable(),
            x.format(), x.pattern(), x.maxLength(), x.placeholder(), source, x.allowCustomValues(), x.section(), x.defaultValues())).toList();
    return new FormDefinition(f.id(), f.name(), f.description(), f.objectClasses(), f.rdnAttribute(), fields, f.createdAt(), f.updatedAt());
  }

  @Test
  void lintsSearchSettings() throws Exception {
    Client admin = login("admin");
    FormDefinition person = store.snapshot().forms.stream().filter(f -> f.id().equals("form-person")).findFirst().orElseThrow();
    FormDefinition broken = withManagerSource(person, new DropdownSource.Ldap(PEOPLE, "one", null, "dn", "cn", "search",
        List.of("cn"), null, null, null, null, null, "{cn} ({not an attribute})", null));
    Map<String, Object> input = Map.of("name", broken.name(), "objectClasses", broken.objectClasses(), "rdnAttribute", broken.rdnAttribute(),
        "fields", broken.fields());
    JsonNode lint = body(admin.post("/api/admin/forms/lint", input));
    List<String> errors = new ArrayList<>();
    lint.get("errors").forEach(e -> errors.add(e.asString()));
    assertThat(errors).anyMatch(e -> e.contains("label template"));
  }
}
