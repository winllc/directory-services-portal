package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Users mapped by the email claim: only an address the provider marks verified is used. */
class SsoOidcEmailTest extends ApiTestSupport {
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
    registry.add("portal.sso.oidc.username-claim", () -> "email");
    registry.add("portal.sso.oidc.user-filter", () -> "(&(objectClass=inetOrgPerson)(mail={{value}}))");
  }

  @AfterAll
  static void stop() {
    IDP.close();
  }

  MvcResult signOn(String code, Map<String, Object> claims) throws Exception {
    MvcResult r = mvc.perform(get("/api/auth/sso/authorize/oidc")).andReturn();
    Map<String, String> q = SsoOidcTest.query(URI.create(r.getResponse().getRedirectedUrl()));
    Cookie sso = r.getResponse().getCookie("DSP_SSO");
    IDP.codes.put(code, new TestOidcProvider.Grant(q.get("nonce"), q.get("code_challenge"), claims, null, null));
    return mvc.perform(get("/api/auth/sso/callback/oidc").param("code", code).param("state", q.get("state")).cookie(sso)).andReturn();
  }

  @Test
  void onlyAVerifiedAddressSignsIn() throws Exception {
    MvcResult verified = signOn("code-e1", Map.of("email", "alice@example.com", "email_verified", true));
    assertThat(verified.getResponse().getRedirectedUrl()).isEqualTo("/");
    MockHttpSession session = (MockHttpSession) verified.getRequest().getSession(false);
    assertThat(body(mvc.perform(get("/api/auth/me").session(session)).andReturn()).get("user").get("username").asString()).isEqualTo("alice");

    assertThat(signOn("code-e2", Map.of("email", "alice@example.com", "email_verified", false)).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=unverified_email");
    assertThat(signOn("code-e3", Map.of("email", "alice@example.com")).getResponse().getRedirectedUrl())
        .isEqualTo("/login?sso_error=unverified_email");
  }
}
