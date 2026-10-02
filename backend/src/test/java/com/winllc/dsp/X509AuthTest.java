package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import jakarta.servlet.http.Cookie;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

class X509AuthTest extends ApiTestSupport {
  static TestCertificates.Ca ca;
  static TestCertificates.Ca rogueCa;
  static Path caFile;

  @Autowired PortalProperties props;
  @Autowired LdapDirectory directory;

  @BeforeAll
  static void createCas() throws Exception {
    ca = TestCertificates.ca("CN=Example Users CA,O=Example Corp");
    rogueCa = TestCertificates.ca("CN=Rogue CA");
    caFile = Files.createTempFile("trusted-ca", ".pem");
    Files.writeString(caFile, TestCertificates.pem(ca.cert()));
  }

  @BeforeEach
  void enableX509() {
    PortalProperties.X509 x = props.getX509();
    x.setEnabled(true);
    x.setSource("servlet");
    x.setTrustedCaFile(caFile.toString());
    x.setMapping("filter");
    x.setUserFilter("(&(objectClass=inetOrgPerson)(uid={{cn}}))");
    x.setRequireCertificateMatch(false);
    x.setAutoLogin(false);
    x.setTrustedProxies("127.0.0.1/32;::1/128");
    props.setPasswordLoginEnabled(true);
  }

  @AfterEach
  void restoreDefaults() {
    PortalProperties.X509 x = props.getX509();
    x.setEnabled(false);
    x.setTrustedCaFile("");
    x.setSource("servlet");
    x.setAutoLogin(false);
    x.setRequireCertificateMatch(false);
    props.setPasswordLoginEnabled(true);
  }

  private static X509Certificate cert(String subject) {
    return TestCertificates.client(subject).issuedBy(ca);
  }

  private MvcResult certLogin(X509Certificate cert) throws Exception {
    MockHttpServletRequestBuilder req = post("/api/auth/x509").header("X-DSP-Request", "1");
    if (cert != null) req = req.with(x509(cert));
    return mvc.perform(req).andReturn();
  }

  private String error(MvcResult r) throws Exception {
    return body(r).get("error").asString();
  }

  @Test
  void signsInWithACertificateFromTheTrustedCa() throws Exception {
    MvcResult r = certLogin(cert("CN=alice,OU=Engineering,O=Example Corp"));
    assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
    JsonNode user = body(r).get("user");
    assertThat(user.get("username").asString()).isEqualTo("alice");
    assertThat(user.get("dn").asString()).isEqualTo(ALICE);
    assertThat(user.get("authMethod").asString()).isEqualTo("x509");
    assertThat(user.get("certificateSubject").asString()).isEqualTo("CN=alice,OU=Engineering,O=Example Corp");
    assertThat(texts(user.get("groups"))).contains("cn=engineering,ou=groups," + B);

    MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
    assertThat(status(mvc.perform(get("/api/definitions").session(session)).andReturn())).isEqualTo(200);
  }

  @Test
  void administratorsKeepTheirRoleWithCertificates() throws Exception {
    MvcResult r = certLogin(cert("CN=admin,O=Example Corp"));
    assertThat(body(r).get("user").get("isAdmin").asBoolean()).isTrue();
    MockHttpSession session = (MockHttpSession) r.getRequest().getSession(false);
    assertThat(status(mvc.perform(get("/api/admin/forms").session(session)).andReturn())).isEqualTo(200);
  }

  @Test
  void recordsCertificateSignInsAndRejectionsInTheAuditLog() throws Exception {
    assertThat(status(certLogin(cert("CN=alice,OU=Audited,O=Example Corp")))).isEqualTo(200);
    assertThat(status(certLogin(cert("CN=nobody,OU=Audited,O=Example Corp")))).isEqualTo(401);

    MockHttpSession admin = (MockHttpSession) certLogin(cert("CN=admin,O=Example Corp")).getRequest().getSession(false);
    JsonNode events = body(mvc.perform(get("/api/admin/audit?action=auth.login&q=Audited").session(admin)).andReturn()).get("events");
    assertThat(events).hasSize(2);
    JsonNode rejected = events.get(0);
    assertThat(rejected.get("outcome").asString()).isEqualTo("failed");
    assertThat(rejected.get("target").get("id").asString()).isEqualTo("CN=nobody,OU=Audited,O=Example Corp");
    assertThat(rejected.get("message").asString()).isEqualTo("No directory account matches this certificate");
    assertThat(rejected.get("details").get("certificateSerial").asString()).isNotBlank();
    JsonNode signedIn = events.get(1);
    assertThat(signedIn.get("outcome").asString()).isEqualTo("success");
    assertThat(signedIn.get("actor").get("dn").asString()).isEqualTo(ALICE);
    assertThat(signedIn.get("actor").get("authMethod").asString()).isEqualTo("x509");
  }

  @Test
  void rejectsMissingUntrustedExpiredAndWrongPurposeCertificates() throws Exception {
    MvcResult none = certLogin(null);
    assertThat(status(none)).isEqualTo(401);
    assertThat(error(none)).isEqualTo("No client certificate was presented");

    MvcResult rogue = certLogin(TestCertificates.client("CN=alice").issuedBy(rogueCa));
    assertThat(status(rogue)).isEqualTo(401);
    assertThat(error(rogue)).contains("not issued by a trusted certificate authority");

    MvcResult expired = certLogin(TestCertificates.client("CN=alice").expired().issuedBy(ca));
    assertThat(status(expired)).isEqualTo(401);
    assertThat(error(expired)).contains("expired");

    MvcResult serverCert = certLogin(TestCertificates.client("CN=alice").eku(KeyPurposeId.id_kp_serverAuth).issuedBy(ca));
    assertThat(status(serverCert)).isEqualTo(401);
    assertThat(error(serverCert)).contains("client authentication");

    MvcResult unknown = certLogin(cert("CN=nobody"));
    assertThat(status(unknown)).isEqualTo(401);
    assertThat(error(unknown)).isEqualTo("No directory account matches this certificate");
  }

  @Test
  void isRefusedWhenDisabled() throws Exception {
    props.getX509().setEnabled(false);
    MvcResult r = certLogin(cert("CN=alice"));
    assertThat(status(r)).isEqualTo(401);
    assertThat(error(r)).isEqualTo("Certificate sign-in is not enabled");
  }

  @Test
  void mapsByEmailAndUpnSubjectAlternativeNames() throws Exception {
    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(mail={{email}}))");
    MvcResult byEmail = certLogin(TestCertificates.client("CN=Alice Anderson").email("alice@example.com").issuedBy(ca));
    assertThat(body(byEmail).get("user").get("username").asString()).isEqualTo("alice");

    MvcResult noEmail = certLogin(cert("CN=Alice Anderson"));
    assertThat(status(noEmail)).isEqualTo(401);
    assertThat(error(noEmail)).contains("has no email value");

    // Smart-card style: the Microsoft UPN otherName identifies the user.
    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(mail={{upn}}))");
    MvcResult byUpn = certLogin(TestCertificates.client("CN=BAKER.BOB.1234567890").upn("bob@example.com").issuedBy(ca));
    assertThat(status(byUpn)).as(byUpn.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(byUpn).get("user").get("username").asString()).isEqualTo("bob");
  }

  @Test
  void mapsBySubjectDn() throws Exception {
    props.getX509().setMapping("subject-dn");
    MvcResult ok = certLogin(cert("UID=carol,OU=people,DC=example,DC=com"));
    assertThat(status(ok)).as(ok.getResponse().getContentAsString()).isEqualTo(200);
    assertThat(body(ok).get("user").get("username").asString()).isEqualTo("carol");

    MvcResult outside = certLogin(cert("CN=portal,OU=system,DC=example,DC=com"));
    assertThat(status(outside)).isEqualTo(401);
  }

  @Test
  void canRequireTheCertificateToBePublishedOnTheAccount() throws Exception {
    props.getX509().setRequireCertificateMatch(true);
    X509Certificate aliceCert = cert("CN=alice");
    MvcResult before = certLogin(aliceCert);
    assertThat(status(before)).isEqualTo(401);
    assertThat(error(before)).contains("not registered");

    directory.modify(ALICE, List.of(new Modification(ModificationType.ADD, "userCertificate;binary", aliceCert.getEncoded())));
    assertThat(status(certLogin(aliceCert))).isEqualTo(200);
    // A different (valid) certificate for the same user is still refused.
    assertThat(status(certLogin(cert("CN=alice")))).isEqualTo(401);
  }

  @Test
  void acceptsCertificatesFromTrustedProxiesOnly() throws Exception {
    props.getX509().setSource("header");
    X509Certificate c = cert("CN=alice");
    String nginx = URLEncoder.encode(TestCertificates.pem(c), StandardCharsets.UTF_8).replace("+", "%20");

    MvcResult trusted = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").header("X-SSL-Client-Cert", nginx)).andReturn();
    assertThat(status(trusted)).as(trusted.getResponse().getContentAsString()).isEqualTo(200);

    MvcResult spoofed = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").header("X-SSL-Client-Cert", nginx).with(r -> {
      r.setRemoteAddr("203.0.113.9");
      return r;
    })).andReturn();
    assertThat(status(spoofed)).isEqualTo(401);
    assertThat(error(spoofed)).isEqualTo("No client certificate was presented");

    // X-Forwarded-For must not influence the proxy trust decision: the connecting peer decides.
    MvcResult forgedFor = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").header("X-SSL-Client-Cert", nginx)
        .header("X-Forwarded-For", "127.0.0.1").with(r -> {
          r.setRemoteAddr("203.0.113.9");
          return r;
        })).andReturn();
    assertThat(status(forgedFor)).isEqualTo(401);
    MvcResult viaProxy = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").header("X-SSL-Client-Cert", nginx)
        .header("X-Forwarded-For", "198.51.100.7")).andReturn();
    assertThat(status(viaProxy)).as(viaProxy.getResponse().getContentAsString()).isEqualTo(200);

    // Envoy's x-forwarded-client-cert format.
    props.getX509().setHeader("x-forwarded-client-cert");
    String xfcc = "By=spiffe://portal;Hash=abc;Cert=\"" + URLEncoder.encode(TestCertificates.pem(c), StandardCharsets.UTF_8) + "\";Subject=\"CN=alice\"";
    MvcResult envoy = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").header("x-forwarded-client-cert", xfcc)).andReturn();
    assertThat(status(envoy)).as(envoy.getResponse().getContentAsString()).isEqualTo(200);
    props.getX509().setHeader("X-SSL-Client-Cert");
  }

  @Test
  void autoLoginSignsInTransparentlyAndRespectsLogout() throws Exception {
    props.getX509().setAutoLogin(true);
    X509Certificate c = cert("CN=alice");

    MvcResult me = mvc.perform(get("/api/auth/me").with(x509(c))).andReturn();
    assertThat(body(me).get("user").get("username").asString()).isEqualTo("alice");
    MockHttpSession session = (MockHttpSession) me.getRequest().getSession(false);

    MvcResult logout = mvc.perform(post("/api/auth/logout").header("X-DSP-Request", "1").session(session)).andReturn();
    Cookie suppress = logout.getResponse().getCookie("DSP_X509_SIGNED_OUT");
    assertThat(suppress).isNotNull();

    MvcResult afterLogout = mvc.perform(get("/api/auth/me").with(x509(c)).cookie(suppress)).andReturn();
    assertThat(body(afterLogout).get("user").isNull()).isTrue();

    MvcResult explicit = mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").with(x509(c)).cookie(suppress)).andReturn();
    assertThat(status(explicit)).isEqualTo(200);
    assertThat(explicit.getResponse().getCookie("DSP_X509_SIGNED_OUT").getMaxAge()).isZero();
  }

  @Test
  void passwordSignInCanBeDisabled() throws Exception {
    props.setPasswordLoginEnabled(false);
    MvcResult pw = anonymous(json(post("/api/auth/login"), Map.of("username", "alice", "password", "password")));
    assertThat(status(pw)).isEqualTo(403);
    JsonNode auth = body(anonymous(get("/api/info"))).get("auth");
    assertThat(auth.get("password").asBoolean()).isFalse();
    assertThat(auth.get("x509").asBoolean()).isTrue();
    assertThat(status(certLogin(cert("CN=alice")))).isEqualTo(200);
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new java.util.ArrayList<>();
    array.forEach(n -> out.add(n.asString()));
    return out;
  }
}
