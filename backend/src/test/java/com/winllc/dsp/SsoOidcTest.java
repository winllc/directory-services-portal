package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.service.SessionService;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * OpenID Connect sign-in against a test provider: authorization code with PKCE, the asserted
 * claim mapped to one directory entry, administrator rights from provider groups only through the
 * configured mapping, every outcome audited, the banner enforced.
 */
class SsoOidcTest extends ApiTestSupport {
  static final TestOidcProvider IDP;

  static {
    try {
      IDP = new TestOidcProvider();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void oidc(DynamicPropertyRegistry registry) {
    registry.add("portal.sso.oidc.issuer-uri", () -> IDP.issuer);
    registry.add("portal.sso.oidc.client-id", () -> "portal");
    registry.add("portal.sso.oidc.client-secret", () -> "test-secret");
    registry.add("portal.sso.oidc.admin-groups", () -> "portal-admins");
    registry.add("portal.sso.oidc.label", () -> "Sign in with Example ID");
  }

  @AfterAll
  static void stop() {
    IDP.close();
  }

  @Autowired EnsProperties ens;

  @AfterEach
  void bannerOff() {
    ens.getConsentBanner().setEnabled(false);
  }

  /** The browser's side of a sign-on: start, then come back from the provider with a code. */
  record Started(Cookie sso, String state, String nonce, String challenge, MockHttpSession session) {}

  Started start(MockHttpSession session) throws Exception {
    var req = get("/api/auth/sso/authorize/oidc");
    if (session != null) req.session(session);
    MvcResult r = mvc.perform(req).andReturn();
    assertThat(status(r)).isEqualTo(302);
    URI location = URI.create(r.getResponse().getRedirectedUrl());
    assertThat(location.toString()).startsWith(IDP.issuer + "/authorize");
    Map<String, String> q = query(location);
    assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "portal").containsEntry("code_challenge_method", "S256")
        .containsEntry("redirect_uri", "http://localhost/api/auth/sso/callback/oidc");
    assertThat(q.get("scope")).contains("openid");
    Cookie sso = r.getResponse().getCookie("DSP_SSO");
    assertThat(sso).isNotNull();
    assertThat(sso.isHttpOnly()).isTrue();
    assertThat(r.getResponse().getHeaders("Set-Cookie")).anySatisfy(h -> assertThat(h).contains("DSP_SSO=").contains("SameSite=Lax"));
    return new Started(sso, q.get("state"), q.get("nonce"), q.get("code_challenge"), session);
  }

  /** The provider's answer for {@code s}, issuing {@code claims}. */
  MvcResult callback(Started s, String code, Map<String, Object> claims) throws Exception {
    IDP.codes.put(code, new TestOidcProvider.Grant(s.nonce(), s.challenge(), claims, null, null));
    return mvc.perform(get("/api/auth/sso/callback/oidc").param("code", code).param("state", s.state()).cookie(s.sso())).andReturn();
  }

  static Map<String, String> query(URI uri) {
    Map<String, String> out = new HashMap<>();
    for (String p : uri.getRawQuery().split("&")) {
      int eq = p.indexOf('=');
      out.put(p.substring(0, eq), URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  JsonNode me(MvcResult signedIn) throws Exception {
    MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
    assertThat(session).isNotNull();
    return body(mvc.perform(get("/api/auth/me").session(session)).andReturn()).get("user");
  }

  long auditCount(String text) throws Exception {
    Client admin = login("admin");
    JsonNode events = body(admin.get("/api/admin/audit?pageSize=100&action=auth.login")).get("events");
    long n = 0;
    for (JsonNode e : events) if (e.toString().contains(text)) n++;
    return n;
  }

  @Test
  void theLoginPageOffersTheProvider() throws Exception {
    JsonNode sso = body(anonymous(get("/api/info"))).get("auth").get("sso");
    assertThat(sso).hasSize(1);
    assertThat(sso.get(0).get("label").asString()).isEqualTo("Sign in with Example ID");
    assertThat(sso.get(0).get("url").asString()).isEqualTo("/api/auth/sso/authorize/oidc");
  }

  @Test
  void signsInTheMappedDirectoryUserWithPkce() throws Exception {
    Started s = start(null);
    MvcResult r = callback(s, "code-alice", Map.of("preferred_username", "alice"));
    assertThat(status(r)).isEqualTo(302);
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/");
    JsonNode user = me(r);
    assertThat(user.get("username").asString()).isEqualTo("alice");
    assertThat(user.get("dn").asString()).isEqualTo(ALICE);
    assertThat(user.get("authMethod").asString()).isEqualTo("oidc");
    assertThat(user.get("isAdmin").asBoolean()).isFalse();
    // The code was redeemed with the PKCE verifier and the client's credentials.
    Map<String, String> token = IDP.tokenRequests.get(IDP.tokenRequests.size() - 1);
    assertThat(token).containsKey("code_verifier").containsEntry("grant_type", "authorization_code");
    assertThat(auditCount("\"method\":\"oidc\"")).isPositive();
  }

  @Test
  void eachSignInRequestIsUsedOnce() throws Exception {
    Started s = start(null);
    assertThat(status(callback(s, "code-1", Map.of("preferred_username", "alice")))).isEqualTo(302);
    MvcResult again = callback(s, "code-2", Map.of("preferred_username", "alice"));
    assertThat(again.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
    // Without the browser's DSP_SSO cookie, a valid state and code are refused too.
    Started other = start(null);
    IDP.codes.put("code-3", new TestOidcProvider.Grant(other.nonce(), other.challenge(), Map.of("preferred_username", "alice"), null, null));
    MvcResult stolen = mvc.perform(get("/api/auth/sso/callback/oidc").param("code", "code-3").param("state", other.state())).andReturn();
    assertThat(stolen.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
  }

  @Test
  void refusesTokensThatDoNotCheckOut() throws Exception {
    // Another nonce than the one sent (a token from another sign-in).
    Started s = start(null);
    IDP.codes.put("code-n", new TestOidcProvider.Grant("other-nonce", s.challenge(), Map.of("preferred_username", "alice"), null, null));
    MvcResult r = mvc.perform(get("/api/auth/sso/callback/oidc").param("code", "code-n").param("state", s.state()).cookie(s.sso())).andReturn();
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
    // Another audience.
    Started a = start(null);
    IDP.codes.put("code-a", new TestOidcProvider.Grant(a.nonce(), a.challenge(), Map.of("preferred_username", "alice"), null, "someone-else"));
    r = mvc.perform(get("/api/auth/sso/callback/oidc").param("code", "code-a").param("state", a.state()).cookie(a.sso())).andReturn();
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
    // Another issuer.
    Started i = start(null);
    IDP.codes.put("code-i", new TestOidcProvider.Grant(i.nonce(), i.challenge(), Map.of("preferred_username", "alice"), "https://evil.example", null));
    r = mvc.perform(get("/api/auth/sso/callback/oidc").param("code", "code-i").param("state", i.state()).cookie(i.sso())).andReturn();
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=failed");
    assertThat(r.getRequest().getSession(false) == null
        || body(mvc.perform(get("/api/auth/me").session((MockHttpSession) r.getRequest().getSession(false))).andReturn()).get("user").isNull())
        .isTrue();
  }

  @Test
  void refusesAnIdentityWithoutADirectoryEntryAndAuditsIt() throws Exception {
    MvcResult r = callback(start(null), "code-x", Map.of("preferred_username", "nobody"));
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=no_account");
    assertThat(auditCount("\"reasonCode\":\"no_account\"")).isPositive();
    // A claim that would widen the filter is escaped, not interpreted.
    r = callback(start(null), "code-y", Map.of("preferred_username", "*"));
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=no_account");
  }

  @Test
  void providerGroupsGrantAdministratorOnlyThroughTheMapping() throws Exception {
    MvcResult mapped = callback(start(null), "code-g1", Map.of("preferred_username", "alice", "groups", List.of("staff", "portal-admins")));
    assertThat(me(mapped).get("isAdmin").asBoolean()).isTrue();
    MockHttpSession session = (MockHttpSession) mapped.getRequest().getSession(false);
    assertThat(session.getAttribute(SessionService.IDP_ADMIN)).isEqualTo(Boolean.TRUE);
    // Still an administrator after groups are read again from the directory.
    session.setAttribute(SessionService.GROUPS_RESOLVED_AT, System.currentTimeMillis() - Duration.ofHours(1).toMillis());
    assertThat(status(mvc.perform(get("/api/admin/audit?pageSize=1").session(session)).andReturn())).isEqualTo(200);

    MvcResult unmapped = callback(start(null), "code-g2", Map.of("preferred_username", "bob", "groups", List.of("admins", "Domain Admins")));
    assertThat(me(unmapped).get("isAdmin").asBoolean()).isFalse();
  }

  @Test
  void administratorRightsFromProviderGroupsEndWithTheirLifetime() throws Exception {
    MvcResult mapped = callback(start(null), "code-l1", Map.of("preferred_username", "alice", "groups", List.of("portal-admins")));
    MockHttpSession session = (MockHttpSession) mapped.getRequest().getSession(false);
    // SSO_IDP_ADMIN_SESSION_LIFETIME (1h) after sign-in, the session ends: the provider is asked again.
    session.setAttribute(SessionService.SIGNED_IN_AT, System.currentTimeMillis() - Duration.ofMinutes(61).toMillis());
    MvcResult r = mvc.perform(get("/api/admin/audit?pageSize=1").session(session)).andReturn();
    assertThat(status(r)).isEqualTo(401);
    assertThat(body(r).get("code").asString()).isEqualTo("session_expired");

    // A directory administrator signing in through the provider keeps the usual lifetime.
    MvcResult admin = callback(start(null), "code-l2", Map.of("preferred_username", "admin", "groups", List.of("portal-admins")));
    MockHttpSession adminSession = (MockHttpSession) admin.getRequest().getSession(false);
    assertThat(adminSession.getAttribute(SessionService.IDP_ADMIN)).isNull();
    adminSession.setAttribute(SessionService.SIGNED_IN_AT, System.currentTimeMillis() - Duration.ofMinutes(61).toMillis());
    assertThat(status(mvc.perform(get("/api/admin/audit?pageSize=1").session(adminSession)).andReturn())).isEqualTo(200);
  }

  @Test
  void theBannerComesFirst() throws Exception {
    ens.getConsentBanner().setEnabled(true);
    MvcResult refused = mvc.perform(get("/api/auth/sso/authorize/oidc")).andReturn();
    assertThat(refused.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error=consent");

    MvcResult ack = mvc.perform(post("/api/auth/consent").header("X-DSP-Request", "1")).andReturn();
    MockHttpSession session = (MockHttpSession) ack.getRequest().getSession(false);
    MvcResult r = callback(start(session), "code-c", Map.of("preferred_username", "alice"));
    assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/");
    JsonNode me = body(mvc.perform(get("/api/auth/me").session((MockHttpSession) r.getRequest().getSession(false))).andReturn());
    assertThat(me.get("user").get("username").asString()).isEqualTo("alice");
  }
}
