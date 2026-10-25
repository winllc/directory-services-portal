package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Coming back from single sign-on, the portal redirects by path, never to an address built from
 * the Host the API sees: behind a proxy that does not pass the browser's Host on (nginx's default
 * proxy_pass in front of the web tier), that address names the proxy's upstream, which the browser
 * cannot reach and which has no session cookie. Run on Tomcat as deployed, behind the web tier
 * (forwarded headers honoured), with such an upstream in X-Forwarded-Host.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.forward-headers-strategy=framework")
@Import(TestLicensing.Config.class)
class SsoRedirectsTest {
  private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
      .followRedirects(HttpClient.Redirect.NEVER).build();
  /** What the web tier forwards when the proxy in front of it sent its own upstream as Host. */
  private static final String UPSTREAM = "web:8080";
  static final TestOidcProvider IDP;

  static {
    try {
      IDP = new TestOidcProvider();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @LocalServerPort int port;

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) throws IOException {
    String dir = Files.createTempDirectory("dsp-redirects-test").toString();
    registry.add("portal.directory-mode", () -> "memory");
    registry.add("portal.data-dir", () -> dir);
    registry.add("portal.sso.public-url", () -> "http://localhost:9443");
    registry.add("portal.sso.oidc.issuer-uri", () -> IDP.issuer);
    registry.add("portal.sso.oidc.client-id", () -> "portal");
    registry.add("portal.sso.oidc.client-secret", () -> "test-secret");
  }

  @BeforeAll
  static void license() {
    TestLicensing.use(TestLicenses.full());
  }

  @AfterAll
  static void stop() {
    IDP.close();
  }

  private HttpResponse<String> get(String path, String cookie) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Forwarded-Host", UPSTREAM);
    if (cookie != null) b.header("Cookie", cookie);
    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** The redirect's Location. 303 rather than 302 from the forwarded-headers filter's relative redirects. */
  private static String location(HttpResponse<?> r) {
    assertThat(r.statusCode()).isIn(302, 303);
    return r.headers().firstValue("Location").orElseThrow();
  }

  @Test
  void signingInRedirectsToTheStartPageByPath() throws Exception {
    HttpResponse<String> start = get("/api/auth/sso/authorize/oidc", null);
    URI toIdp = URI.create(location(start));
    // The provider gets the configured public address to come back to, whatever the Host.
    Map<String, String> q = SsoOidcTest.query(toIdp);
    assertThat(q).containsEntry("redirect_uri", "http://localhost:9443/api/auth/sso/callback/oidc");
    String ssoCookie = start.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("DSP_SSO=")).findFirst().orElseThrow()
        .split(";", 2)[0];
    IDP.codes.put("code-r", new TestOidcProvider.Grant(q.get("nonce"), q.get("code_challenge"), Map.of("preferred_username", "alice"), null, null));

    HttpResponse<String> back = get("/api/auth/sso/callback/oidc?code=code-r&state=" + q.get("state"), ssoCookie);
    assertThat(location(back)).isEqualTo("/");
    assertThat(back.headers().allValues("Set-Cookie")).anySatisfy(c -> assertThat(c).startsWith("DSP_SESSION="));
  }

  @Test
  void aRefusedSignInRedirectsToTheSignInPageByPath() throws Exception {
    assertThat(location(get("/api/auth/sso/callback/oidc?code=x&state=unknown", null))).isEqualTo("/login?sso_error=failed");
  }
}
