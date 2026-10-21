package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.integration.ApiTokenOwners;
import com.winllc.dsp.ldap.LdapDirectory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** The integration API (/api/v1) and its API tokens. */
@ExtendWith(OutputCaptureExtension.class)
class IntegrationApiTest extends ApiTestSupport {
  static final String ENGINEERING = "cn=engineering,ou=groups," + B;
  static final String BOB = "uid=bob,ou=people," + B;

  @Autowired PortalProperties props;
  @Autowired AuditLog audit;
  @Autowired ApiTokenOwners owners;
  @Autowired LdapDirectory directory;

  private record Token(String id, String secret) {}

  private Token token(Client admin, Map<String, Object> request) throws Exception {
    MvcResult r = admin.post("/api/admin/api-tokens", request);
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
    JsonNode b = body(r);
    return new Token(b.get("token").get("id").asString(), b.get("secret").asString());
  }

  private Token token(Client admin, String... scopes) throws Exception {
    return token(admin, Map.of("name", "test " + System.nanoTime(), "scopes", List.of(scopes), "expiresInDays", 30));
  }

  private MvcResult call(Token t, MockHttpServletRequestBuilder req) throws Exception {
    return mvc.perform(req.header("Authorization", "Bearer " + t.secret())).andReturn();
  }

  private MockHttpServletRequestBuilder jsonBody(MockHttpServletRequestBuilder req, Object body) throws Exception {
    // No X-DSP-Request header: token requests don't need the CSRF header.
    return req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
  }

  private static URI u(String path) {
    return URI.create(path);
  }

  private List<JsonNode> events(Client admin, String action) throws Exception {
    List<JsonNode> out = new ArrayList<>();
    body(admin.get("/api/admin/audit?action=" + action + "&pageSize=200")).get("events").forEach(out::add);
    return out;
  }

  @Test
  void readsDirectoriesAndEntriesWithAReadToken() throws Exception {
    Client admin = login("admin");
    Token t = token(admin, "entries:read");

    MvcResult dirs = call(t, get("/api/v1/directories"));
    assertThat(status(dirs)).isEqualTo(200);
    List<String> slugs = new ArrayList<>();
    body(dirs).forEach(d -> slugs.add(d.get("slug").asString()));
    assertThat(slugs).contains("staff", "groups");
    // A read-only token sees read access even where its creator could write.
    body(dirs).forEach(d -> assertThat(d.get("access").asString()).isEqualTo("read"));
    // Stateless: no session is created and no cookie set.
    assertThat(dirs.getRequest().getSession(false)).isNull();
    assertThat(dirs.getResponse().getHeader("Set-Cookie")).isNull();

    JsonNode detail = body(call(t, get("/api/v1/directories/staff")));
    assertThat(detail.get("rdnAttribute").asString()).isEqualTo("uid");
    assertThat(detail.get("fields").toString()).contains("\"attribute\":\"mail\"");

    JsonNode found = body(call(t, get("/api/v1/directories/staff/entries?q=alice")));
    assertThat(found.get("entries").get(0).get("dn").asString()).isEqualTo(ALICE);
    JsonNode alice = body(call(t, get(u("/api/v1/directories/staff/entry" + q(ALICE)))));
    assertThat(alice.get("attributes").get("cn").get(0).asString()).isEqualTo("Alice Anderson");
    assertThat(alice.get("attributes").has("userpassword")).isFalse();
  }

  @Test
  void refusesMissingUnknownAndMalformedTokens() throws Exception {
    MvcResult none = anonymous(get("/api/v1/directories"));
    assertThat(status(none)).isEqualTo(401);
    assertThat(body(none).get("code").asString()).isEqualTo("token_required");
    assertThat(none.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");

    MvcResult unknown = anonymous(get("/api/v1/directories").header("Authorization", "Bearer dspat_nope"));
    assertThat(status(unknown)).isEqualTo(401);
    assertThat(body(unknown).get("code").asString()).isEqualTo("token_unknown");

    // An audit API token is not an integration API token, and a session cookie is not a token.
    Client admin = login("admin");
    String auditToken = body(admin.post("/api/admin/audit/tokens", Map.of("name", "siem"))).get("secret").asString();
    assertThat(status(anonymous(get("/api/v1/directories").header("Authorization", "Bearer " + auditToken)))).isEqualTo(401);
    assertThat(status(admin.get("/api/v1/directories"))).isEqualTo(401);
    // And an integration token opens nothing outside /api/v1.
    Token t = token(admin, "entries:read", "entries:write");
    assertThat(status(call(t, get("/api/definitions")))).isEqualTo(401);
    assertThat(status(call(t, get("/api/admin/api-tokens")))).isEqualTo(401);
    assertThat(status(call(t, jsonBody(post("/api/admin/api-tokens"), Map.of("name", "x", "scopes", List.of("entries:read")))))).isEqualTo(403);
  }

  @Test
  void scopesLimitWhatATokenCanDo() throws Exception {
    Client admin = login("admin");
    Token read = token(admin, "entries:read");
    Map<String, Object> device = Map.of("values", Map.of("cn", "LT-9001", "acmeDeviceSerial", "SN-9001", "acmeDeviceType", "Laptop",
        "acmeDeviceStatus", "In use"));
    MvcResult denied = call(read, jsonBody(post("/api/v1/directories/devices/entries"), device));
    assertThat(status(denied)).isEqualTo(403);
    assertThat(body(denied).get("code").asString()).isEqualTo("insufficient_scope");
    assertThat(status(call(read, get(u("/api/v1/directories/groups/members" + q(ENGINEERING)))))).isEqualTo(403);

    Token write = token(admin, "entries:read", "entries:write");
    MvcResult created = call(write, jsonBody(post("/api/v1/directories/devices/entries"), device));
    assertThat(status(created)).as(created.getResponse().getContentAsString()).isEqualTo(201);
    String dn = body(created).get("dn").asString();
    MvcResult changed = call(write, jsonBody(patch(u("/api/v1/directories/devices/entry" + q(dn))), Map.of("values", Map.of("acmeAssetTag", "A-1"))));
    assertThat(status(changed)).as(changed.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(changed).get("attributes").get("acmeassettag").get(0).asString()).isEqualTo("A-1");
    // Deleting needs its own scope.
    assertThat(status(call(write, delete(u("/api/v1/directories/devices/entry" + q(dn)))))).isEqualTo(403);
    Token deleter = token(admin, "entries:delete");
    assertThat(status(call(deleter, delete(u("/api/v1/directories/devices/entry" + q(dn)))))).isEqualTo(204);

    // Audited as the token's creator, signed in with the token.
    JsonNode event = events(admin, "entry.create").get(0);
    assertThat(event.get("actor").get("id").asString()).isEqualTo("admin");
    assertThat(event.get("details").get("authMethod").asString()).isEqualTo("api-token");
    assertThat(event.get("details").get("apiClientId").asString()).isEqualTo(write.id());
  }

  @Test
  void readsAndChangesGroupMembers() throws Exception {
    Client admin = login("admin");
    Token t = token(admin, "groups:read", "groups:write");
    JsonNode members = body(call(t, get(u("/api/v1/directories/groups/members" + q(ENGINEERING)))));
    assertThat(members.get("attribute").asString()).isEqualTo("member");
    assertThat(members.get("members").toString()).contains(ALICE).doesNotContain(BOB);

    MvcResult r = call(t, jsonBody(post(u("/api/v1/directories/groups/members" + q(ENGINEERING))), Map.of("add", List.of(BOB), "remove", List.of(ALICE))));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    JsonNode change = body(r);
    assertThat(change.get("added").toString()).contains(BOB);
    assertThat(change.get("removed").toString()).contains(ALICE);
    assertThat(body(call(t, get(u("/api/v1/directories/groups/members" + q(ENGINEERING))))).get("members").toString())
        .contains(BOB).doesNotContain(ALICE);
    // A value that is not a person the member field offers is refused by the form's validation.
    MvcResult bad = call(t, jsonBody(post(u("/api/v1/directories/groups/members" + q(ENGINEERING))), Map.of("add", List.of("uid=nobody,ou=people," + B))));
    assertThat(status(bad)).isEqualTo(400);
    // Only membership fields: not any multi-valued attribute.
    MvcResult other = call(t, jsonBody(post(u("/api/v1/directories/staff/members" + q(ALICE))), Map.of("attribute", "mail", "add", List.of("x@example.com"))));
    assertThat(status(other)).isEqualTo(400);
    // groups:write is not entries:write.
    assertThat(status(call(t, jsonBody(patch(u("/api/v1/directories/groups/entry" + q(ENGINEERING))), Map.of("values", Map.of("description", "x"))))))
        .isEqualTo(403);
  }

  @Test
  void aTokenNeverHasMoreThanItsCreatorNow() throws Exception {
    Client admin = login("admin");
    Token limited = token(admin, Map.of("name", "devices only", "scopes", List.of("entries:read"), "definitionIds", List.of("def-devices"),
        "expiresInDays", 30));
    List<String> slugs = new ArrayList<>();
    body(call(limited, get("/api/v1/directories"))).forEach(d -> slugs.add(d.get("slug").asString()));
    assertThat(slugs).containsExactly("devices");
    assertThat(status(call(limited, get("/api/v1/directories/staff/entries")))).isEqualTo(404);

    // dave (helpdesk) is made an administrator through ADMIN_GROUPS and creates a token.
    String admins = "cn=directory-admins,ou=groups," + B;
    String dave = "uid=dave,ou=people," + B;
    directory.modify(admins, List.of(new Modification(ModificationType.ADD, "member", dave)));
    Token full = token(login("dave"), "entries:read");
    assertThat(status(call(full, get("/api/v1/directories/staff/entries")))).isEqualTo(200);
    // He stops being an administrator: the token loses what only an administrator had.
    directory.modify(admins, List.of(new Modification(ModificationType.DELETE, "member", dave)));
    owners.forget(full.id());
    assertThat(status(call(full, get("/api/v1/directories/staff/entries")))).isEqualTo(404);
    // Still a helpdesk member: devices stay readable.
    assertThat(status(call(full, get("/api/v1/directories/devices/entries")))).isEqualTo(200);
  }

  @Test
  void aTokenOfADisabledOrLockedCreatorIsRefused() throws Exception {
    // The demo schema has no lock attributes: teach the embedded server nsAccountLock (389-DS) and
    // pwdAccountLockedTime (password policy), and let dave's entry hold them.
    String dave = "uid=dave,ou=people," + B;
    // (The embedded server's schema entry lacks its own cn value, which the change must add.)
    directory.modify("cn=schema", List.of(new Modification(ModificationType.REPLACE, "cn", "schema"),
        new Modification(ModificationType.ADD, "attributeTypes",
        "( 2.16.840.1.113730.3.1.610 NAME 'nsAccountLock' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )",
        "( 1.3.6.1.4.1.42.2.27.8.1.17 NAME 'pwdAccountLockedTime' SYNTAX 1.3.6.1.4.1.1466.115.121.1.24 SINGLE-VALUE )")));
    String admins = "cn=directory-admins,ou=groups," + B;
    directory.modify(admins, List.of(new Modification(ModificationType.ADD, "member", dave)));
    Token t = token(login("dave"), "entries:read");
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(200);

    directory.modify(dave, List.of(new Modification(ModificationType.ADD, "objectClass", "extensibleObject"),
        new Modification(ModificationType.ADD, "nsAccountLock", "true")));
    owners.forget(t.id());
    MvcResult locked = call(t, get("/api/v1/directories"));
    assertThat(status(locked)).isEqualTo(401);
    assertThat(body(locked).get("code").asString()).isEqualTo("token_owner_disabled");
    // Refused on every request, not cached; usable again once unlocked.
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(401);
    directory.modify(dave, List.of(new Modification(ModificationType.DELETE, "nsAccountLock")));
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(200);

    directory.modify(dave, List.of(new Modification(ModificationType.ADD, "pwdAccountLockedTime", "20261021000000Z")));
    owners.forget(t.id());
    assertThat(body(call(t, get("/api/v1/directories"))).get("code").asString()).isEqualTo("token_owner_disabled");
  }

  @Test
  void refusesAnAddressThatKeepsPresentingBadTokens() throws Exception {
    MockHttpServletRequestBuilder bad = get("/api/v1/directories").header("Authorization", "Bearer dspat_guess").with(r -> {
      r.setRemoteAddr("203.0.113.77");
      return r;
    });
    int limited = 0;
    for (int i = 0; i < 40; i++) {
      MvcResult r = mvc.perform(bad).andReturn();
      if (status(r) == 429) {
        limited++;
        assertThat(r.getResponse().getHeader("Retry-After")).isNotNull();
      }
    }
    assertThat(limited).isGreaterThanOrEqualTo(10);
    // Another address is not affected.
    assertThat(status(anonymous(get("/api/v1/directories").header("Authorization", "Bearer dspat_guess")))).isEqualTo(401);
  }

  @Test
  void revokedTokensStopWorking() throws Exception {
    Client admin = login("admin");
    Token t = token(admin, "entries:read");
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(200);
    assertThat(status(admin.del("/api/admin/api-tokens/" + t.id()))).isEqualTo(200);
    MvcResult r = call(t, get("/api/v1/directories"));
    assertThat(status(r)).isEqualTo(401);
    assertThat(body(r).get("code").asString()).isEqualTo("token_revoked");
    JsonNode listed = body(admin.get("/api/admin/api-tokens"));
    listed.forEach(v -> {
      if (v.get("id").asString().equals(t.id())) assertThat(v.get("status").asString()).isEqualTo("revoked");
    });
    assertThat(events(admin, "api.token-revoked").get(0).get("target").get("id").asString()).isEqualTo(t.id());
    assertThat(events(admin, "access.denied").stream().anyMatch(e -> e.get("details").get("reasonCode").asString().equals("api_token_revoked"))).isTrue();
  }

  @Test
  void ratesAreLimitedPerToken() throws Exception {
    Client admin = login("admin");
    Token t = token(admin, Map.of("name", "slow", "scopes", List.of("entries:read"), "expiresInDays", 1, "rateLimitPerMinute", 2));
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(200);
    assertThat(status(call(t, get("/api/v1/directories")))).isEqualTo(200);
    MvcResult limited = call(t, get("/api/v1/directories"));
    assertThat(status(limited)).isEqualTo(429);
    assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isPositive();
    // Another token has its own budget.
    assertThat(status(call(token(admin, "entries:read"), get("/api/v1/directories")))).isEqualTo(200);
  }

  @Test
  void onlyAdministratorsCreateTokensWithValidSettings() throws Exception {
    Client alice = login("alice");
    assertThat(status(alice.post("/api/admin/api-tokens", Map.of("name", "x", "scopes", List.of("entries:read"))))).isEqualTo(403);
    Client admin = login("admin");
    assertThat(status(admin.post("/api/admin/api-tokens", Map.of("name", "x", "scopes", List.of())))).isEqualTo(400);
    assertThat(status(admin.post("/api/admin/api-tokens", Map.of("name", "x", "scopes", List.of("admin:all"))))).isEqualTo(400);
    assertThat(status(admin.post("/api/admin/api-tokens", Map.of("name", "x", "scopes", List.of("entries:read"), "expiresInDays", 9999)))).isEqualTo(400);
    assertThat(status(admin.post("/api/admin/api-tokens", Map.of("name", "x", "scopes", List.of("entries:read"), "definitionIds", List.of("nope")))))
        .isEqualTo(400);
    // The CSRF header is still required to manage tokens (a browser session).
    MvcResult noHeader = mvc.perform(post("/api/admin/api-tokens").session(admin.session).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"x\",\"scopes\":[\"entries:read\"]}")).andReturn();
    assertThat(status(noHeader)).isEqualTo(403);
  }

  @Test
  void theTokenValueIsShownOnceAndNeverStoredOrLogged(CapturedOutput output) throws Exception {
    Client admin = login("admin");
    Token t = token(admin, "entries:read", "entries:write");
    call(t, get("/api/v1/directories"));
    call(t, jsonBody(patch(u("/api/v1/directories/staff/entry" + q(ALICE))), Map.of("values", Map.of("title", "Engineer"))));
    call(t, get("/api/v1/directories/nope"));
    String listed = admin.get("/api/admin/api-tokens").getResponse().getContentAsString();
    assertThat(listed).contains(t.id()).doesNotContain(t.secret()).doesNotContain("hash");

    String stored = Files.readString(Path.of(props.getDataDir()).toAbsolutePath().resolve("api-tokens.json"));
    assertThat(stored).contains(t.id()).doesNotContain(t.secret());
    StringBuilder auditText = new StringBuilder();
    try (Stream<Path> s = Files.walk(audit.store().dir())) {
      for (Path p : s.filter(Files::isRegularFile).toList()) auditText.append(Files.readString(p));
    }
    for (String sink : List.of(auditText.toString(), Files.readString(AUDIT_FILE), output.getAll())) {
      assertThat(sink).isNotEmpty().doesNotContain(t.secret()).doesNotContain(t.secret().substring(12));
    }
  }

  @Test
  void servesTheOpenApiDocumentWithoutSignIn() throws Exception {
    MvcResult r = anonymous(get("/api/v1/openapi"));
    assertThat(status(r)).isEqualTo(200);
    JsonNode doc = body(r);
    assertThat(doc.get("openapi").asString()).startsWith("3.");
    assertThat(doc.get("components").get("securitySchemes").get("apiToken").get("scheme").asString()).isEqualTo("bearer");
    // The server is the configured public address or "/", never the request's Host.
    assertThat(doc.get("servers").get(0).get("url").asString()).isEqualTo("/");
    List<String> paths = new ArrayList<>();
    doc.get("paths").propertyNames().forEach(paths::add);
    assertThat(paths).contains("/api/v1/directories", "/api/v1/directories/{id}/entries", "/api/v1/directories/{id}/entry",
        "/api/v1/directories/{id}/members");
    assertThat(paths).allMatch(p -> p.startsWith("/api/v1/"));
    assertThat(status(anonymous(get("/api/v1/openapi.yaml")))).isEqualTo(200);
    OpenApiDocument.write(r.getResponse().getContentAsString());
  }
}
