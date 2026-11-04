package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.winllc.dsp.config.DirectoryConfig;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.PermissionGrant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Two directories (the embedded demo directory twice: "default" and "lab", the same DNs holding
 * different data once changed): definitions, sign-in, grants, search, self service, audit records
 * and API payloads each keep to their directory, and the license feature multi-directory decides
 * whether the second is used at all.
 */
class MultiDirectoryTest extends ApiTestSupport {
  static final String LAB = DirectoryConfig.DEMO_SECOND_ID;
  static final String BOB = "uid=bob,ou=people," + B;

  @DynamicPropertySource
  static void twoDirectories(DynamicPropertyRegistry registry) {
    registry.add("portal.demo-directories", () -> "2");
  }

  @Autowired Directories directories;

  private String since;

  @BeforeEach
  void lab() throws InterruptedException {
    directories.find(LAB).orElseThrow().embedded().reset();
    // The staff definition again, in the lab directory.
    store.update(d -> {
      DirectoryDefinition staff = d.definitions.stream().filter(x -> x.id().equals("def-staff")).findFirst().orElseThrow();
      d.definitions.add(new DirectoryDefinition("def-lab-staff", "Lab staff", "lab-staff", null, staff.formId(), staff.baseDn(), staff.scope(),
          null, staff.mode(), false, staff.listAttributes(), staff.searchAttributes(), staff.titleAttribute(), staff.createContainers(),
          staff.selfMatch(), null, staff.createdAt(), staff.updatedAt(), LAB));
      return null;
    });
    since = Instant.now().toString();
    Thread.sleep(2);
  }

  Client login(String username, String directory) throws Exception {
    MvcResult r = mvc.perform(json(post("/api/auth/login"), Map.of("username", username, "password", "password", "directory", directory)))
        .andReturn();
    if (status(r) != 200) throw new AssertionError("login failed for " + username + "@" + directory + ": " + r.getResponse().getContentAsString());
    return new Client((org.springframework.mock.web.MockHttpSession) r.getRequest().getSession(false), body(r).get("user"));
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private String phone(Client c, String slug, String dn) throws Exception {
    MvcResult r = c.get("/api/directories/" + slug + "/entry" + q(dn));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    JsonNode v = body(r).get("attributes").get("telephonenumber");
    return v == null ? null : v.get(0).asString();
  }

  private List<String> slugs(Client c) throws Exception {
    List<String> out = new ArrayList<>();
    body(c.get("/api/definitions")).forEach(d -> {
      if (!"none".equals(d.get("access").asString())) out.add(d.get("slug").asString());
    });
    return out;
  }

  @Test
  void theLoginPageOffersEveryDirectoryAndSignInKeepsToTheChosenOne() throws Exception {
    JsonNode offered = body(anonymous(get("/api/info"))).get("auth").get("directories");
    assertThat(offered).hasSize(2);
    assertThat(offered.get(0).get("id").asString()).isEqualTo("default");
    assertThat(offered.get(1).get("id").asString()).isEqualTo(LAB);
    assertThat(offered.get(1).get("name").asString()).isEqualTo("Lab directory (demo)");

    assertThat(login("alice").user.get("directory").asString()).isEqualTo("default");
    assertThat(login("alice", LAB).user.get("directory").asString()).isEqualTo(LAB);
    // The same name in another directory is another user: a password changed in one leaves the other.
    directories.find(LAB).orElseThrow().ldap().modify(ALICE,
        List.of(new com.unboundid.ldap.sdk.Modification(com.unboundid.ldap.sdk.ModificationType.REPLACE, "userPassword", "lab-only")));
    MvcResult wrong = mvc.perform(json(post("/api/auth/login"), Map.of("username", "alice", "password", "password", "directory", LAB))).andReturn();
    assertThat(status(wrong)).isEqualTo(401);
    assertThat(login("alice", "default").user.get("dn").asString()).isEqualTo(ALICE);

    MvcResult unknown = mvc.perform(json(post("/api/auth/login"), Map.of("username", "alice", "password", "password", "directory", "nope")))
        .andReturn();
    assertThat(status(unknown)).isEqualTo(400);
    assertThat(body(unknown).get("error").asString()).contains("not available");
  }

  @Test
  void eachDefinitionReadsAndWritesItsOwnDirectory() throws Exception {
    Client admin = login("admin");
    MvcResult saved = admin.put("/api/directories/lab-staff/entry" + q(ALICE), Map.of("values", Map.of("telephoneNumber", "+1 555 0100")));
    assertThat(status(saved)).as(saved.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(phone(admin, "lab-staff", ALICE)).isEqualTo("+1 555 0100");
    assertThat(phone(admin, "staff", ALICE)).isNotEqualTo("+1 555 0100");

    MvcResult created = admin.post("/api/directories/lab-staff/entries",
        Map.of("values", Map.of("uid", "zoe", "givenName", "Zoe", "sn", "Zed", "cn", "Zoe Zed", "mail", "zoe@example.com")));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
    // Search: only the lab directory has her.
    assertThat(body(admin.get("/api/directories/lab-staff/entries?q=zoe")).get("total").asInt()).isEqualTo(1);
    assertThat(body(admin.get("/api/directories/staff/entries?q=zoe")).get("total").asInt()).isZero();
    assertThat(directories.primary().ldap().get("uid=zoe,ou=people," + B, List.of("1.1"))).isNull();

    // The admin API names each definition's directory; a new one without a directory gets the first.
    JsonNode defs = body(admin.get("/api/admin/definitions"));
    defs.forEach(d -> assertThat(d.get("directory").asString()).isEqualTo(d.get("id").asString().equals("def-lab-staff") ? LAB : "default"));
    MvcResult unknownDirectory = admin.post("/api/admin/definitions", Map.ofEntries(Map.entry("name", "X"), Map.entry("slug", "x"),
        Map.entry("formId", "form-person"), Map.entry("baseDn", "ou=people," + B), Map.entry("scope", "one"), Map.entry("mode", "readwrite"),
        Map.entry("everyoneCanRead", false), Map.entry("listAttributes", List.of("cn")), Map.entry("searchAttributes", List.of("cn")),
        Map.entry("titleAttribute", "cn"), Map.entry("createContainers", List.of()), Map.entry("selfMatch", Map.of("type", "none")),
        Map.entry("directory", "nope")));
    assertThat(status(unknownDirectory)).isEqualTo(400);
  }

  @Test
  void grantsMatchOnlyUsersOfTheirDirectory() throws Exception {
    store.update(d -> {
      d.grants.add(new PermissionGrant("g-bob-lab", "def-lab-staff", "user", "bob", "Bob", "read", Instant.now().toString(), LAB));
      return null;
    });
    // bob of the lab directory, not bob of the default one.
    assertThat(status(login("bob", LAB).get("/api/directories/lab-staff/entries"))).isEqualTo(200);
    assertThat(status(login("bob").get("/api/directories/lab-staff/entries"))).isEqualTo(403);
    assertThat(slugs(login("bob", LAB))).contains("lab-staff");
    assertThat(slugs(login("bob"))).doesNotContain("lab-staff");

    // The demo's group grants on staff name groups of the default directory: erin (hr) of the lab
    // directory is in a group with the same DN there, which is not the same group.
    assertThat(status(login("erin").get("/api/directories/staff/entries"))).isEqualTo(200);
    assertThat(status(login("erin", LAB).get("/api/directories/staff/entries"))).isEqualTo(403);

    // A grant made without a directory is for the definition's; one may name another directory.
    Client admin = login("admin");
    MvcResult own = admin.post("/api/admin/grants", Map.of("definitionId", "def-lab-staff", "subjectType", "user", "subject", "carol",
        "access", "read"));
    assertThat(body(own).get("directory").asString()).isEqualTo(LAB);
    MvcResult cross = admin.post("/api/admin/grants", Map.of("definitionId", "def-lab-staff", "subjectType", "user", "subject", "carol",
        "access", "write", "directory", "default"));
    assertThat(body(cross).get("directory").asString()).isEqualTo("default");
    assertThat(body(cross).get("id").asString()).isNotEqualTo(body(own).get("id").asString());
    assertThat(status(login("carol").get("/api/directories/lab-staff/entries"))).isEqualTo(200);
    // Users and groups to grant to are searched in the directory asked for.
    JsonNode subjects = body(admin.get("/api/admin/subjects?q=carol&type=user&directory=" + LAB));
    assertThat(subjects.get(0).get("id").asString()).isEqualTo("carol");
    assertThat(status(admin.get("/api/admin/subjects?q=carol&directory=nope"))).isEqualTo(404);
  }

  @Test
  void selfServiceShowsOnlyEntriesOfTheUsersDirectory() throws Exception {
    List<String> labView = new ArrayList<>();
    body(login("alice", LAB).get("/api/me/entries")).forEach(r -> labView.add(r.get("definition").get("slug").asString()));
    assertThat(labView).containsExactly("lab-staff");
    List<String> defaultView = new ArrayList<>();
    body(login("alice").get("/api/me/entries")).forEach(r -> defaultView.add(r.get("definition").get("slug").asString()));
    assertThat(defaultView).contains("staff").doesNotContain("lab-staff");
  }

  @Test
  void theAuditRecordNamesTheDirectory() throws Exception {
    Client admin = login("admin");
    admin.put("/api/directories/lab-staff/entry" + q(ALICE), Map.of("values", Map.of("telephoneNumber", "+1 555 0199")));
    login("bob", LAB);
    List<JsonNode> events = new ArrayList<>();
    body(admin.get("/api/admin/audit?pageSize=200&from=" + enc(since))).get("events").forEach(events::add);
    JsonNode update = events.stream().filter(e -> e.get("action").asString().equals("entry.update")).findFirst().orElseThrow();
    assertThat(update.get("details").get("directory").asString()).isEqualTo(LAB);
    assertThat(update.get("details").get("definitionId").asString()).isEqualTo("def-lab-staff");
    assertThat(update.get("details").get("actorDirectory").asString()).isEqualTo("default");
    JsonNode signIn = events.stream().filter(e -> e.get("action").asString().equals("auth.login") && e.get("actor").get("id").asString().equals("bob"))
        .findFirst().orElseThrow();
    assertThat(signIn.get("details").get("directory").asString()).isEqualTo(LAB);
    assertThat(signIn.get("details").get("actorDirectory").asString()).isEqualTo(LAB);
  }

  @Test
  void theIntegrationApiNamesEachDirectorysLdapDirectory() throws Exception {
    Client admin = login("admin");
    MvcResult r = admin.post("/api/admin/api-tokens", Map.of("name", "multi " + System.nanoTime(), "scopes", List.of("entries:read"),
        "expiresInDays", 30));
    String secret = body(r).get("secret").asString();
    JsonNode dirs = body(mvc.perform(get("/api/v1/directories").header("Authorization", "Bearer " + secret)).andReturn());
    Map<String, String> byslug = new java.util.HashMap<>();
    dirs.forEach(d -> byslug.put(d.get("slug").asString(), d.get("directory").asString()));
    assertThat(byslug).containsEntry("staff", "default").containsEntry("lab-staff", LAB);

    // A token of a lab user acts as that user, in the lab directory.
    store.update(d -> {
      d.grants.add(new PermissionGrant("g-dave-lab", "def-lab-staff", "user", "dave", "Dave", "read", Instant.now().toString(), LAB));
      return null;
    });
    String daveLab = body(login("admin", LAB).post("/api/admin/api-tokens", Map.of("name", "lab " + System.nanoTime(),
        "scopes", List.of("entries:read"), "expiresInDays", 30))).get("secret").asString();
    assertThat(status(mvc.perform(get("/api/v1/directories/lab-staff/entries").header("Authorization", "Bearer " + daveLab)).andReturn()))
        .isEqualTo(200);
  }

  @Test
  void administratorsSeeEveryDirectoryAndHealthPerDirectory() throws Exception {
    Client admin = login("admin");
    JsonNode listing = body(admin.get("/api/admin/directories"));
    assertThat(listing.get("multiDirectoryLicensed").asBoolean()).isTrue();
    assertThat(listing.has("notice") && !listing.get("notice").isNull()).isFalse();
    JsonNode lab = listing.get("directories").get(1);
    assertThat(lab.get("id").asString()).isEqualTo(LAB);
    assertThat(lab.get("enabled").asBoolean()).isTrue();
    assertThat(lab.get("reachable").asBoolean()).isTrue();
    assertThat(lab.get("servers").get(0).asString()).startsWith("ldap://127.0.0.1:");
    assertThat(lab.get("currentServer").asString()).startsWith("127.0.0.1:");
    assertThat(listing.toString()).doesNotContain("portal-secret");

    JsonNode health = body(admin.get("/actuator/health/readiness")).get("components").get("ldap");
    assertThat(health.get("status").asString()).isEqualTo("UP");
    assertThat(health.get("details").get("directories").get(LAB).get("status").asString()).isEqualTo("UP");

    // The lab directory stops answering: the portal stays ready, degraded, and says which.
    directories.find(LAB).orElseThrow().embedded().intercept(new InMemoryOperationInterceptor() {
      @Override
      public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
        throw new LDAPException(ResultCode.UNAVAILABLE, "lab is down");
      }
    });
    MvcResult ready = admin.get("/actuator/health/readiness");
    assertThat(status(ready)).isEqualTo(200);
    JsonNode degraded = body(ready).get("components").get("ldap");
    assertThat(degraded.get("status").asString()).isEqualTo("UP");
    assertThat(degraded.get("details").get("degraded").asBoolean()).isTrue();
    assertThat(degraded.get("details").get("directories").get(LAB).get("status").asString()).isEqualTo("DOWN");
    assertThat(degraded.get("details").get("directories").get("default").get("status").asString()).isEqualTo("UP");
    JsonNode down = body(admin.get("/api/admin/directories")).get("directories").get(1);
    assertThat(down.get("reachable").asBoolean()).isFalse();
    // Users of the default directory carry on.
    assertThat(status(login("alice").get("/api/directories/staff/entries"))).isEqualTo(200);
  }

  @Test
  void withoutMultiDirectoryOnlyTheFirstDirectoryIsUsed() throws Exception {
    List<String> features = new ArrayList<>(TestLicenses.ALL_FEATURES);
    features.remove("multi-directory");
    TestLicensing.use(TestLicenses.license().features(features.toArray(String[]::new)).sign());

    assertThat(body(anonymous(get("/api/info"))).get("auth").has("directories")).isFalse();
    MvcResult refused = mvc.perform(json(post("/api/auth/login"), Map.of("username", "alice", "password", "password", "directory", LAB)))
        .andReturn();
    assertThat(status(refused)).isEqualTo(400);

    Client admin = login("admin");
    JsonNode listing = body(admin.get("/api/admin/directories"));
    assertThat(listing.get("multiDirectoryLicensed").asBoolean()).isFalse();
    assertThat(listing.get("notice").asString()).contains("Only the first directory is in use", "multi-directory");
    assertThat(listing.get("directories").get(0).get("enabled").asBoolean()).isTrue();
    assertThat(listing.get("directories").get(1).get("enabled").asBoolean()).isFalse();

    // The lab definition is listed for administrators but can't be used.
    MvcResult entries = admin.get("/api/directories/lab-staff/entries");
    assertThat(status(entries)).isEqualTo(403);
    assertThat(body(entries).get("code").asString()).isEqualTo("feature_not_licensed");
    assertThat(slugs(admin)).contains("staff").doesNotContain("lab-staff");
    JsonNode health = body(admin.get("/actuator/health/readiness")).get("components").get("ldap");
    assertThat(health.get("status").asString()).isEqualTo("UP");
    assertThat(health.get("details").get("directories").get(LAB).get("status").asString()).isEqualTo("DISABLED");

    // Failover is not gated: one directory works as ever.
    assertThat(status(login("alice").get("/api/directories/staff/entries"))).isEqualTo(200);
  }

  @Autowired com.winllc.dsp.sso.SsoSignIn sso;
  @Autowired com.winllc.dsp.config.PortalProperties props;

  @Test
  void singleSignOnUsesTheFirstDirectoryInOrderThatHoldsTheUser() throws Exception {
    com.winllc.dsp.sso.SsoProperties.Oidc provider = new com.winllc.dsp.sso.SsoProperties.Oidc();
    // alice is in both demo directories: the first in configured order wins, the other is reported.
    com.winllc.dsp.sso.SsoSignIn.Identity alice = sso.map("oidc", provider, "alice", List.of());
    assertThat(alice.user().directory()).isEqualTo("default");
    assertThat(alice.alsoMatched()).containsExactly(LAB);
    // zoe only in the lab directory: signed in there, nothing else matched.
    Client admin = login("admin");
    admin.post("/api/directories/lab-staff/entries", Map.of("values", Map.of("uid", "zoe", "givenName", "Zoe", "sn", "Zed", "cn", "Zoe Zed", "mail", "zoe@example.com")));
    com.winllc.dsp.sso.SsoSignIn.Identity zoe = sso.map("oidc", provider, "zoe", List.of());
    assertThat(zoe.user().directory()).isEqualTo(LAB);
    assertThat(zoe.user().dn()).isEqualTo("uid=zoe,ou=people," + B);
    assertThat(zoe.alsoMatched()).isEmpty();
    // Two matches in the first directory that matches: still refused.
    provider.setUserFilter("(&(objectClass=inetOrgPerson)(|(uid={{value}})(uid=bob)))");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sso.map("oidc", provider, "alice", List.of()))
        .isInstanceOf(com.winllc.dsp.sso.SsoSignIn.MappingException.class)
        .hasMessageContaining("More than one directory entry");
  }

  @Test
  void certificateSignInUsesTheFirstDirectoryAndAuditsTheOthers() throws Exception {
    TestCertificates.Ca ca = TestCertificates.ca("CN=Multi Directory CA,O=Example Corp");
    java.nio.file.Path caFile = java.nio.file.Files.createTempFile("multi-ca", ".pem");
    java.nio.file.Files.writeString(caFile, TestCertificates.pem(ca.cert()));
    com.winllc.dsp.config.PortalProperties.X509 x = props.getX509();
    x.setEnabled(true);
    x.setSource("servlet");
    x.setTrustedCaFile(caFile.toString());
    x.setMapping("filter");
    x.setUserFilter("(&(objectClass=inetOrgPerson)(uid={{cn}}))");
    try {
      MvcResult r = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1")
          .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509(
              TestCertificates.client("CN=alice,O=Example Corp").issuedBy(ca)))).andReturn();
      assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
      assertThat(body(r).get("user").get("directory").asString()).isEqualTo("default");
    } finally {
      x.setEnabled(false);
      x.setTrustedCaFile("");
    }
    List<JsonNode> events = new ArrayList<>();
    body(login("admin").get("/api/admin/audit?pageSize=200&action=auth.login&from=" + enc(since))).get("events").forEach(events::add);
    JsonNode signIn = events.stream().filter(e -> "x509".equals(e.get("details").path("method").asString())).findFirst().orElseThrow();
    assertThat(signIn.get("outcome").asString()).isEqualTo("success");
    assertThat(signIn.get("details").get("directory").asString()).isEqualTo("default");
    assertThat(signIn.get("details").get("alsoMatched").asString()).isEqualTo(LAB);
  }
}
