package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class ApiIntegrationTest extends ApiTestSupport {

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }

  private static Map<String, Object> field(String id, String attribute, String label, Map<String, Object> extra) {
    Map<String, Object> f = new HashMap<>(Map.of(
        "id", id, "attribute", attribute, "label", label, "widget", "text",
        "multiValued", false, "required", false, "readOnly", false, "selfEditable", false));
    f.putAll(extra);
    return f;
  }

  @Nested
  class Authentication {
    @Test
    void logsInWithDirectoryCredentialsAndResolvesGroups() throws Exception {
      Client admin = login("admin");
      assertThat(admin.user.get("isAdmin").asBoolean()).isTrue();
      assertThat(texts(admin.user.get("groups"))).anyMatch(g -> g.startsWith("cn=directory-admins"));

      Client alice = login("alice");
      assertThat(alice.user.get("username").asString()).isEqualTo("alice");
      assertThat(alice.user.get("dn").asString()).isEqualTo(ALICE);
      assertThat(alice.user.get("isAdmin").asBoolean()).isFalse();
      assertThat(alice.user.get("displayName").asString()).isEqualTo("Alice Anderson");
      assertThat(body(alice.get("/api/auth/me")).get("user").get("username").asString()).isEqualTo("alice");
    }

    @Test
    void rejectsBadCredentialsAndFilterInjection() throws Exception {
      MvcResult bad = anonymous(json(post("/api/auth/login"), Map.of("username", "alice", "password", "nope")));
      assertThat(status(bad)).isEqualTo(401);
      MvcResult inj = anonymous(json(post("/api/auth/login"), Map.of("username", "*)(uid=*", "password", "password")));
      assertThat(status(inj)).isEqualTo(401);
      MvcResult empty = anonymous(json(post("/api/auth/login"), Map.of("username", "alice", "password", "")));
      assertThat(status(empty)).isEqualTo(400);
    }

    @Test
    void requiresTheCsrfHeaderOnMutations() throws Exception {
      MvcResult r = anonymous(post("/api/auth/login").contentType("application/json").content("{\"username\":\"alice\",\"password\":\"password\"}"));
      assertThat(status(r)).isEqualTo(403);
    }

    @Test
    void requiresASessionForApiCalls() throws Exception {
      assertThat(status(anonymous(get("/api/definitions")))).isEqualTo(401);
      MvcResult me = anonymous(get("/api/auth/me"));
      assertThat(status(me)).isEqualTo(200);
      assertThat(body(me).get("user").isNull()).isTrue();
    }

    @Test
    void issuesANewSessionOnLoginAndInvalidatesOnLogout() throws Exception {
      Client alice = login("alice");
      assertThat(status(alice.post("/api/auth/logout", null))).isEqualTo(200);
      assertThat(status(alice.get("/api/definitions"))).isEqualTo(401);
    }

    @Test
    void throttlesRepeatedFailures() throws Exception {
      for (int i = 0; i < 8; i++) {
        anonymous(json(post("/api/auth/login"), Map.of("username", "george", "password", "x")).with(r -> {
          r.setRemoteAddr("10.1.2.3");
          return r;
        }));
      }
      MvcResult r = anonymous(json(post("/api/auth/login"), Map.of("username", "george", "password", "password")).with(req -> {
        req.setRemoteAddr("10.1.2.3");
        return req;
      }));
      assertThat(status(r)).isEqualTo(429);
    }
  }

  @Nested
  class Permissions {
    @Test
    void listsOnlyAccessibleDefinitionsWithEffectiveAccess() throws Exception {
      Client alice = login("alice");
      Map<String, String> access = new HashMap<>();
      body(alice.get("/api/definitions")).forEach(d -> access.put(d.get("slug").asString(), d.get("access").asString()));
      // engineering group -> staff read; user grant -> devices read; everyoneCanRead -> white pages & groups
      assertThat(access).isEqualTo(Map.of("staff", "read", "devices", "read", "white-pages", "read", "groups", "read"));

      Client bob = login("bob");
      List<String> slugs = new ArrayList<>();
      body(bob.get("/api/definitions")).forEach(d -> slugs.add(d.get("slug").asString()));
      assertThat(slugs).containsExactlyInAnyOrder("groups", "partners", "white-pages");
    }

    @Test
    void enforcesReadAccess() throws Exception {
      Client bob = login("bob");
      assertThat(status(bob.get("/api/directories/def-staff/entries"))).isEqualTo(403);
      assertThat(status(bob.get("/api/directories/def-staff/entry" + q(ALICE)))).isEqualTo(403);
    }

    @Test
    void enforcesWriteAccess() throws Exception {
      Client alice = login("alice");
      assertThat(status(alice.put("/api/directories/def-staff/entry" + q(ALICE), Map.of("values", Map.of("title", "CEO"))))).isEqualTo(403);
      Client erin = login("erin"); // hr group has write
      MvcResult ok = erin.put("/api/directories/def-staff/entry" + q(ALICE), Map.of("values", Map.of("title", "Staff Engineer")));
      assertThat(status(ok)).isEqualTo(200);
      assertThat(texts(body(ok).get("attributes").get("title"))).containsExactly("Staff Engineer");
    }

    @Test
    void neverAllowsWritesThroughWhitePagesEvenForAdmins() throws Exception {
      Client admin = login("admin");
      assertThat(status(admin.put("/api/directories/def-whitepages/entry" + q(ALICE), Map.of("values", Map.of("title", "x"))))).isEqualTo(403);
      assertThat(status(admin.del("/api/directories/def-whitepages/entry" + q(ALICE)))).isEqualTo(403);
      MvcResult grant = admin.post("/api/admin/grants", Map.of("definitionId", "def-whitepages", "subjectType", "user", "subject", "bob", "access", "write"));
      assertThat(status(grant)).isEqualTo(400);
    }

    @Test
    void grantsTakeEffectImmediatelyAndCanBeRevoked() throws Exception {
      Client admin = login("admin");
      MvcResult g = admin.post("/api/admin/grants", Map.of("definitionId", "def-devices", "subjectType", "user", "subject", "bob", "access", "write"));
      assertThat(status(g)).isEqualTo(201);
      Client bob = login("bob");
      MvcResult create = bob.post("/api/directories/def-devices/entries",
          Map.of("values", Map.of("cn", "LT-0099", "acmeDeviceSerial", "SN-X", "acmeDeviceType", "Laptop")));
      assertThat(status(create)).isEqualTo(201);
      assertThat(status(admin.del("/api/admin/grants/" + body(g).get("id").asString()))).isEqualTo(204);
      assertThat(status(bob.get("/api/directories/def-devices/entries"))).isEqualTo(403);
    }

    @Test
    void groupGrantsApplyToMembers() throws Exception {
      Client dave = login("dave"); // helpdesk group has write on devices
      MvcResult r = dave.put("/api/directories/devices/entry" + q("cn=LT-0003,ou=devices," + B), Map.of("values", Map.of("acmeDeviceStatus", "In use")));
      assertThat(status(r)).isEqualTo(200);
    }

    @Test
    void blocksNonAdminsFromAdminApis() throws Exception {
      Client alice = login("alice");
      assertThat(status(alice.get("/api/admin/forms"))).isEqualTo(403);
      assertThat(status(alice.get("/api/admin/schema"))).isEqualTo(403);
    }
  }

  @Nested
  class Entries {
    @Test
    void listsSearchesSortsAndPages() throws Exception {
      Client admin = login("admin");
      JsonNode all = body(admin.get("/api/directories/staff/entries?pageSize=3"));
      assertThat(all.get("total").asInt()).isEqualTo(8);
      assertThat(all.get("entries").size()).isEqualTo(3);
      assertThat(texts(all.get("entries").get(0).get("attributes").get("cn"))).containsExactly("Ada Admin");
      assertThat(all.get("entries").get(0).get("attributes").has("userpassword")).isFalse();

      JsonNode desc = body(admin.get("/api/directories/staff/entries?pageSize=1&order=desc"));
      assertThat(texts(desc.get("entries").get(0).get("attributes").get("cn"))).containsExactly("George Gupta");

      List<String> names = new ArrayList<>();
      body(admin.get("/api/directories/staff/entries?q=engineer")).get("entries").forEach(e -> names.add(e.get("attributes").get("cn").get(0).asString()));
      assertThat(names).containsExactlyInAnyOrder("Alice Anderson", "Carol Chen");

      JsonNode injection = body(admin.get("/api/directories/staff/entries?q=" + java.net.URLEncoder.encode("*)(uid=*", "UTF-8")));
      assertThat(injection.get("total").asInt()).isZero();
    }

    @Test
    void createsReadsUpdatesRenamesAndDeletes() throws Exception {
      Client admin = login("admin");
      Map<String, Object> values = new HashMap<>();
      values.put("uid", "zoe");
      values.put("givenName", "Zoe");
      values.put("sn", "Zimmer");
      values.put("cn", "Zoe Zimmer");
      values.put("mail", List.of("zoe@example.com", "z@example.com"));
      values.put("departmentNumber", "Engineering");
      values.put("manager", "uid=carol,ou=people," + B);
      values.put("acmeSkill", List.of("Go", "Rust"));
      values.put("acmeBadgeNumber", "ignored-read-only");
      MvcResult create = admin.post("/api/directories/staff/entries", Map.of("values", values));
      assertThat(status(create)).as(create.getResponse().getContentAsString()).isEqualTo(201);
      JsonNode created = body(create);
      String dn = created.get("dn").asString();
      assertThat(dn).isEqualTo("uid=zoe,ou=people," + B);
      assertThat(texts(created.get("attributes").get("objectclass"))).contains("acmePerson");
      assertThat(created.get("attributes").has("acmebadgenumber")).isFalse();
      assertThat(texts(created.get("attributes").get("acmeskill"))).containsExactlyInAnyOrder("Go", "Rust");

      MvcResult upd = admin.put("/api/directories/staff/entry" + q(dn),
          Map.of("values", Map.of("mail", List.of("zoe@example.com"), "acmeSkill", List.of(), "title", "SRE")));
      assertThat(status(upd)).isEqualTo(200);
      JsonNode updated = body(upd);
      assertThat(texts(updated.get("attributes").get("mail"))).containsExactly("zoe@example.com");
      assertThat(updated.get("attributes").has("acmeskill")).isFalse();
      assertThat(texts(updated.get("attributes").get("title"))).containsExactly("SRE");

      MvcResult ren = admin.put("/api/directories/staff/entry" + q(dn), Map.of("values", Map.of("uid", "zoe.z")));
      assertThat(status(ren)).isEqualTo(200);
      String newDn = body(ren).get("dn").asString();
      assertThat(newDn).isEqualTo("uid=zoe.z,ou=people," + B);
      assertThat(texts(body(ren).get("attributes").get("uid"))).containsExactly("zoe.z");

      assertThat(status(admin.del("/api/directories/staff/entry" + q(newDn)))).isEqualTo(204);
      assertThat(status(admin.get("/api/directories/staff/entry" + q(newDn)))).isEqualTo(404);
    }

    @Test
    void escapesSpecialCharactersInNamingValues() throws Exception {
      Client admin = login("admin");
      MvcResult r = admin.post("/api/directories/devices/entries",
          Map.of("values", Map.of("cn", "Lab, Room #2 + spare", "acmeDeviceSerial", "SN-9", "acmeDeviceType", "Monitor")));
      assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
      String dn = body(r).get("dn").asString();
      assertThat(status(admin.get("/api/directories/devices/entry" + q(dn)))).isEqualTo(200);
      assertThat(texts(body(admin.get("/api/directories/devices/entry" + q(dn))).get("attributes").get("cn"))).containsExactly("Lab, Room #2 + spare");
    }

    @Test
    void validatesValuesAgainstTheForm() throws Exception {
      Client admin = login("admin");
      MvcResult r = admin.post("/api/directories/staff/entries", Map.of("values", Map.of(
          "uid", "xy", "givenName", "X", "sn", "Y", "cn", "X Y", "mail", "not-an-email",
          "departmentNumber", "Nope", "manager", "uid=ghost,ou=people,dc=example,dc=com")));
      assertThat(status(r)).isEqualTo(400);
      List<String> keys = new ArrayList<>();
      body(r).get("details").propertyNames().forEach(keys::add);
      assertThat(keys).containsExactlyInAnyOrder("departmentnumber", "mail", "manager");
    }

    @Test
    void surfacesDirectoryErrors() throws Exception {
      Client admin = login("admin");
      assertThat(status(admin.post("/api/directories/devices/entries", Map.of("values", Map.of("cn", "NO-SERIAL", "acmeDeviceType", "Laptop"))))).isEqualTo(400);
      MvcResult dup = admin.post("/api/directories/devices/entries", Map.of("values", Map.of("cn", "LT-0001", "acmeDeviceSerial", "x", "acmeDeviceType", "Laptop")));
      assertThat(status(dup)).isEqualTo(409);
      // SINGLE-VALUE attributes accept one value.
      assertThat(status(admin.put("/api/directories/devices/entry" + q("cn=LT-0001,ou=devices," + B),
          Map.of("values", Map.of("acmeDeviceSerial", List.of("a", "b")))))).isEqualTo(400);
    }

    @Test
    void confinesOperationsToTheNamespace() throws Exception {
      Client admin = login("admin");
      assertThat(status(admin.get("/api/directories/devices/entry" + q(ALICE)))).isEqualTo(404);
      assertThat(status(admin.del("/api/directories/devices/entry" + q(ALICE)))).isEqualTo(404);
      assertThat(status(admin.get("/api/directories/devices/entry" + q("ou=devices," + B)))).isEqualTo(404);
      MvcResult wrongParent = admin.post("/api/directories/devices/entries",
          Map.of("parentDn", "ou=people," + B, "values", Map.of("cn", "X", "acmeDeviceSerial", "x", "acmeDeviceType", "Laptop")));
      assertThat(status(wrongParent)).isEqualTo(400);
      // In the namespace but not matching the definition's filter.
      assertThat(status(admin.get("/api/directories/white-pages/entry" + q("cn=LT-0001,ou=devices," + B)))).isEqualTo(404);
      assertThat(status(admin.get("/api/directories/staff/entry?dn=not-a-dn"))).isEqualTo(400);
    }

    @Test
    void resolvesLdapBackedDropDownOptions() throws Exception {
      Client admin = login("admin");
      MvcResult r = admin.get("/api/directories/devices/options/f-acmeassignedto");
      assertThat(status(r)).isEqualTo(200);
      String aliceValue = null;
      for (JsonNode o : body(r)) if ("Alice Anderson".equals(o.get("label").asString())) aliceValue = o.get("value").asString();
      assertThat(aliceValue).isEqualTo(ALICE);
    }

    @Test
    void returnsFormsWithDropDownConfiguration() throws Exception {
      Client alice = login("alice");
      JsonNode form = body(alice.get("/api/definitions/devices")).get("form");
      JsonNode assigned = null;
      for (JsonNode f : form.get("fields")) if ("acmeAssignedTo".equals(f.get("attribute").asString())) assigned = f;
      assertThat(assigned).isNotNull();
      assertThat(assigned.get("dropdown").get("type").asString()).isEqualTo("ldap");
      assertThat(assigned.get("dropdown").get("valueAttribute").asString()).isEqualTo("dn");
    }
  }

  @Nested
  class SelfService {
    @Test
    void showsTheUserInEveryMatchingDefinition() throws Exception {
      Client alice = login("alice");
      Map<String, List<String>> bySlug = new HashMap<>();
      for (JsonNode r : body(alice.get("/api/me/entries"))) {
        List<String> dns = new ArrayList<>();
        r.get("entries").forEach(e -> dns.add(e.get("dn").asString()));
        bySlug.put(r.get("definition").get("slug").asString(), dns);
      }
      assertThat(bySlug.get("staff")).containsExactly(ALICE);
      assertThat(bySlug.get("white-pages")).containsExactly(ALICE);
      assertThat(bySlug.get("devices")).containsExactlyInAnyOrder("cn=LT-0001,ou=devices," + B, "cn=PH-0001,ou=devices," + B);
      assertThat(bySlug.get("groups")).containsExactly("cn=engineering,ou=groups," + B);
      assertThat(bySlug.get("partners")).isEmpty();
    }

    @Test
    void letsUsersEditSelfEditableFieldsOnTheirOwnEntryOnly() throws Exception {
      Client alice = login("alice");
      MvcResult ok = alice.put("/api/me/def-staff/entry" + q(ALICE), Map.of("values", Map.of("mobile", List.of("+1 555 9999"), "title", "CTO")));
      assertThat(status(ok)).isEqualTo(200);
      assertThat(texts(body(ok).get("attributes").get("mobile"))).containsExactly("+1 555 9999");
      assertThat(texts(body(ok).get("attributes").get("title"))).containsExactly("Senior Software Engineer");

      assertThat(status(alice.put("/api/me/def-staff/entry" + q("uid=bob,ou=people," + B), Map.of("values", Map.of("mobile", List.of("1")))))).isEqualTo(403);
      assertThat(status(alice.put("/api/me/def-whitepages/entry" + q(ALICE), Map.of("values", Map.of("mobile", List.of("1")))))).isEqualTo(403);
    }
  }

  @Nested
  class Administration {
    @Test
    void createsAndValidatesFormsAndDefinitions() throws Exception {
      Client admin = login("admin");
      MvcResult bad = admin.post("/api/admin/forms", Map.of("name", "Bad", "objectClasses", List.of("inetOrgPerson"), "rdnAttribute", "uid",
          "fields", List.of(field("1", "displayName", "D", Map.of("multiValued", true)))));
      assertThat(status(bad)).isEqualTo(400);

      MvcResult form = admin.post("/api/admin/forms", Map.of(
          "name", "Contractor",
          "objectClasses", List.of("top", "inetOrgPerson", "acmePartner"),
          "rdnAttribute", "uid",
          "fields", List.of(
              field("1", "uid", "User", Map.of("required", true)),
              field("2", "cn", "Name", Map.of("required", true)),
              field("3", "sn", "Surname", Map.of("required", true)),
              field("4", "acmePartnerCompany", "Company", Map.of("widget", "dropdown",
                  "dropdown", Map.of("type", "static", "options", List.of(Map.of("value", "A"), Map.of("value", "B"))))))));
      assertThat(status(form)).as(form.getResponse().getContentAsString()).isEqualTo(201);
      String formId = body(form).get("form").get("id").asString();

      Map<String, Object> def = new HashMap<>(Map.of(
          "name", "X", "slug", "x", "formId", formId, "baseDn", "ou=partners," + B, "scope", "one", "mode", "readwrite",
          "everyoneCanRead", false, "listAttributes", List.of("cn"), "searchAttributes", List.of("cn")));
      def.put("titleAttribute", "cn");
      def.put("createContainers", List.of("ou=people," + B));
      def.put("selfMatch", Map.of("type", "none"));
      def.put("filter", "(bad");
      assertThat(status(admin.post("/api/admin/definitions", def))).isEqualTo(400);
      def.put("filter", "");
      assertThat(status(admin.post("/api/admin/definitions", def))).isEqualTo(400); // container outside base

      def.put("name", "Contractors");
      def.put("slug", "contractors");
      def.put("createContainers", List.of("ou=partners," + B));
      def.put("selfMatch", Map.of("type", "dn"));
      def.put("listAttributes", List.of("cn", "acmePartnerCompany"));
      MvcResult created = admin.post("/api/admin/definitions", def);
      assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
      assertThat(body(admin.get("/api/directories/contractors/entries")).get("total").asInt()).isEqualTo(2);

      assertThat(status(admin.del("/api/admin/forms/" + formId))).isEqualTo(409);
      assertThat(status(admin.del("/api/admin/definitions/" + body(created).get("id").asString()))).isEqualTo(204);
      assertThat(status(admin.del("/api/admin/forms/" + formId))).isEqualTo(204);
    }

    @Test
    void pullsImportsCustomizesAndExportsSchema() throws Exception {
      Client admin = login("admin");
      MvcResult refresh = admin.post("/api/admin/schema/refresh", null);
      assertThat(status(refresh)).isEqualTo(200);
      assertThat(body(refresh).get("objectClasses").asInt()).isGreaterThan(10);

      MvcResult imp = admin.post("/api/admin/schema/import", Map.of("text",
          "attributetype ( 1.2.3.4.5 NAME 'widgetColor' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )\n"
              + "objectclass ( 1.2.3.4.6 NAME 'widget' SUP top STRUCTURAL MUST cn MAY widgetColor )"));
      assertThat(status(imp)).isEqualTo(200);
      assertThat(body(imp).get("attributeTypes").asInt()).isEqualTo(1);
      assertThat(body(imp).get("objectClasses").asInt()).isEqualTo(1);

      MvcResult override = admin.put("/api/admin/schema/attribute-types", Map.of("definition", Map.of(
          "oid", "1.3.6.1.4.1.99999.1.3", "names", List.of("acmeSkill"), "singleValue", false, "noUserModification", false,
          "displayName", "Skills", "desc", "Overridden")));
      assertThat(status(override)).isEqualTo(200);

      JsonNode schema = body(admin.get("/api/admin/schema"));
      List<JsonNode> skill = new ArrayList<>();
      schema.get("merged").get("attributeTypes").forEach(a -> {
        if (texts(a.get("names")).contains("acmeSkill")) skill.add(a);
      });
      assertThat(skill).hasSize(1);
      assertThat(skill.get(0).get("source").asString()).isEqualTo("custom");
      assertThat(skill.get(0).get("desc").asString()).isEqualTo("Overridden");
      boolean hasWidget = false;
      for (JsonNode o : schema.get("merged").get("objectClasses")) hasWidget |= texts(o.get("names")).contains("widget");
      assertThat(hasWidget).isTrue();

      String exported = admin.get("/api/admin/schema/export").getResponse().getContentAsString();
      assertThat(exported).contains("NAME 'widgetColor'");
      assertThat(status(admin.del("/api/admin/schema/object-classes/1.2.3.4.6"))).isEqualTo(204);
    }

    @Test
    void searchesUsersAndGroupsForPermissionSubjects() throws Exception {
      Client admin = login("admin");
      JsonNode users = body(admin.get("/api/admin/subjects?q=car"));
      boolean carol = false;
      for (JsonNode s : users) carol |= "user".equals(s.get("type").asString()) && "carol".equals(s.get("id").asString());
      assertThat(carol).isTrue();
      JsonNode groups = body(admin.get("/api/admin/subjects?type=group&q=eng"));
      assertThat(groups.size()).isEqualTo(1);
      assertThat(groups.get(0).get("label").asString()).isEqualTo("engineering");
    }

    @Test
    void browsesAndPreviewsTheDirectory() throws Exception {
      Client admin = login("admin");
      JsonNode browse = body(admin.get("/api/admin/browse"));
      List<String> names = new ArrayList<>();
      browse.get("children").forEach(c -> names.add(c.get("name").asString()));
      assertThat(names).contains("people", "groups", "devices", "partners");
      JsonNode preview = body(admin.post("/api/admin/preview", Map.of("baseDn", "ou=devices," + B, "scope", "one", "filter", "(acmeDeviceType=Laptop)")));
      assertThat(preview.get("entries").size()).isEqualTo(3);
    }
  }
}
