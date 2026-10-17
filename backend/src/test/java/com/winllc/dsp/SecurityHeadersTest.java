package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

/** The ENS baseline security headers on API responses, and the Secure flag on the portal's cookies. */
class SecurityHeadersTest extends ApiTestSupport {
  static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
      + "font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'";

  @Autowired EnsProperties ens;
  @Autowired PortalProperties props;

  @AfterEach
  void restore() {
    ens.getCsp().setReportOnly(false);
    ens.getCookie().setSecure(false);
    props.getX509().setEnabled(false);
    props.getX509().setTrustedCaFile("");
  }

  private static void assertBaseline(MockHttpServletResponse r) {
    assertThat(r.getHeader("Content-Security-Policy")).isEqualTo(CSP);
    assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(r.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(r.getHeader("Permissions-Policy")).isEqualTo("camera=(), microphone=(), geolocation=(), payment=()");
  }

  @Test
  void sendsTheBaselineHeadersOnEveryResponse() throws Exception {
    assertBaseline(anonymous(get("/api/info")).getResponse());
    // Errors and the bearer-token audit API too.
    assertBaseline(anonymous(get("/api/definitions")).getResponse());
    assertBaseline(anonymous(get("/api/audit/events")).getResponse());
    assertBaseline(login("alice").get("/api/definitions").getResponse());
  }

  @Test
  void sendsHstsOnlyOnSecureRequests() throws Exception {
    assertThat(anonymous(get("/api/info")).getResponse().getHeader("Strict-Transport-Security")).isNull();
    // Direct TLS, or a proxy that terminated TLS (forward-headers-strategy=framework).
    assertThat(anonymous(get("/api/info").secure(true)).getResponse().getHeader("Strict-Transport-Security"))
        .isEqualTo("max-age=31536000 ; includeSubDomains");
    assertThat(anonymous(get("/api/info").header("X-Forwarded-Proto", "https")).getResponse().getHeader("Strict-Transport-Security"))
        .isEqualTo("max-age=31536000 ; includeSubDomains");
  }

  @Test
  void reportsOnlyWhenAsked() throws Exception {
    ens.getCsp().setReportOnly(true);
    MockHttpServletResponse r = anonymous(get("/api/info")).getResponse();
    assertThat(r.getHeader("Content-Security-Policy")).isNull();
    assertThat(r.getHeader("Content-Security-Policy-Report-Only")).isEqualTo(CSP);
  }

  @Test
  void marksTheAutoLoginCookieSecureOverTlsOrWhenConfigured() throws Exception {
    TestCertificates.Ca ca = TestCertificates.ca("CN=Headers Test CA");
    Path caFile = Files.createTempFile("ca", ".pem");
    Files.writeString(caFile, TestCertificates.pem(ca.cert()));
    props.getX509().setEnabled(true);
    props.getX509().setTrustedCaFile(caFile.toString());
    X509Certificate alice = TestCertificates.client("CN=alice").issuedBy(ca);

    MvcResult plain = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").with(x509(alice))).andReturn();
    assertThat(status(plain)).isEqualTo(200);
    MockHttpSession session = (MockHttpSession) plain.getRequest().getSession(false);
    String cookie = mvc.perform(post("/api/auth/logout").header("X-DSP-Request", "1").session(session)).andReturn()
        .getResponse().getHeader("Set-Cookie");
    assertThat(cookie).startsWith("DSP_X509_SIGNED_OUT=1").contains("HttpOnly", "SameSite=Strict").doesNotContain("Secure");

    MvcResult tls = mvc.perform(post("/api/auth/x509").secure(true).header("X-DSP-Request", "1").with(x509(alice))).andReturn();
    session = (MockHttpSession) tls.getRequest().getSession(false);
    cookie = mvc.perform(post("/api/auth/logout").secure(true).header("X-DSP-Request", "1").session(session)).andReturn()
        .getResponse().getHeader("Set-Cookie");
    assertThat(cookie).contains("Secure");

    ens.getCookie().setSecure(true);
    MvcResult proxied = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").with(x509(alice))).andReturn();
    session = (MockHttpSession) proxied.getRequest().getSession(false);
    cookie = mvc.perform(post("/api/auth/logout").header("X-DSP-Request", "1").session(session)).andReturn()
        .getResponse().getHeader("Set-Cookie");
    assertThat(cookie).contains("Secure");
  }
}
