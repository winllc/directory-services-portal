package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.x509.RevocationChecker;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * CAC/PIV revocation checking (ENS_X509_REVOCATION and friends), the issuer allowlist and the
 * certificate placeholders {edipi}, {uuid} and {upn}, against a local OCSP responder and CRL
 * distribution point signed by the test CA.
 */
@ExtendWith(OutputCaptureExtension.class)
class X509RevocationTest extends ApiTestSupport {
  static TestCertificates.Ca ca;
  static TestCertificates.Ca otherCa;
  static Path caFile;
  static TestRevocationServer server;
  static String down;

  @Autowired PortalProperties props;
  @Autowired EnsProperties ens;
  @Autowired RevocationChecker revocation;
  @Autowired LdapDirectory directory;
  private String since;

  @BeforeAll
  static void setUp() throws Exception {
    ca = TestCertificates.ca("CN=Revocation Test CA,O=Example Corp");
    otherCa = TestCertificates.ca("CN=Other Trusted CA,O=Example Corp");
    caFile = Files.createTempFile("trusted-cas", ".pem");
    Files.writeString(caFile, TestCertificates.pem(ca.cert()) + TestCertificates.pem(otherCa.cert()));
    server = new TestRevocationServer(ca);
    try (ServerSocket s = new ServerSocket(0)) {
      down = "http://127.0.0.1:" + s.getLocalPort() + "/ocsp";
    }
  }

  @AfterAll
  static void tearDown() {
    server.close();
  }

  @BeforeEach
  void enable() throws InterruptedException {
    since = Instant.now().toString();
    Thread.sleep(2);
    PortalProperties.X509 x = props.getX509();
    x.setEnabled(true);
    x.setSource("servlet");
    x.setTrustedCaFile(caFile.toString());
    x.setMapping("filter");
    x.setUserFilter("(&(objectClass=inetOrgPerson)(uid={{cn}}))");
    x.setCheckRevocation(false);
    server.revoked.clear();
    server.crl = null;
    revocation.clearCache();
  }

  @AfterEach
  void restore() {
    props.getX509().setEnabled(false);
    props.getX509().setTrustedCaFile("");
    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(uid={{cn}}))");
    props.getX509().setCheckRevocation(false);
    EnsProperties.X509 r = ens.getX509();
    r.setRevocation("");
    r.setRevocationFail("");
    r.setOcspResponderUrl("");
    r.setCrlDir("");
    r.setRevocationCache(Duration.ofMinutes(10));
    r.setAllowedIssuers("");
  }

  private MvcResult certLogin(X509Certificate cert) throws Exception {
    return mvc.perform(post("/api/auth/x509").header("X-DSP-Request", "1").with(x509(cert))).andReturn();
  }

  private String error(MvcResult r) throws Exception {
    return body(r).get("error").asString();
  }

  private List<JsonNode> loginFailures() throws Exception {
    ens.getX509().setRevocation("off");
    Client admin = login("admin");
    List<JsonNode> out = new ArrayList<>();
    body(admin.get("/api/admin/audit?pageSize=50&action=auth.login&outcome=failure&from=" + URLEncoder.encode(since, StandardCharsets.UTF_8)))
        .get("events").forEach(out::add);
    return out;
  }

  private Path crlDir(java.security.cert.X509CRL... crls) throws Exception {
    Path dir = Files.createTempDirectory("crls");
    for (int i = 0; i < crls.length; i++) Files.write(dir.resolve("ca-" + i + ".crl"), crls[i].getEncoded());
    return dir;
  }

  /** Neither setting given: a live check, OCSP then the CRL, refusing (and auditing) what it finds revoked. */
  @Test
  void checksLiveByDefaultAndRefusesRevokedCertificates() throws Exception {
    assertThat(revocation.mode()).isEqualTo(RevocationChecker.Mode.OCSP_THEN_CRL);
    assertThat(revocation.defaulted()).isTrue();
    assertThat(revocation.failOpen()).isTrue();
    X509Certificate good = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    X509Certificate revoked = TestCertificates.client("CN=bob").ocsp(server.url("/ocsp")).issuedBy(ca);
    server.revoked.add(revoked.getSerialNumber());
    int before = server.ocspRequests.get();

    assertThat(status(certLogin(good))).isEqualTo(200);
    MvcResult refused = certLogin(revoked);
    assertThat(status(refused)).isEqualTo(401);
    assertThat(error(refused)).isEqualTo("The client certificate has been revoked");
    assertThat(server.ocspRequests.get()).isEqualTo(before + 2);

    // OCSP gives no answer: the CRL does, and a revoked certificate is still refused.
    X509Certificate crlRevoked = TestCertificates.client("CN=bob").ocsp(down).crl(server.url("/crl")).issuedBy(ca);
    server.crl = TestCertificates.crl(ca, crlRevoked);
    assertThat(error(certLogin(crlRevoked))).isEqualTo("The client certificate has been revoked");

    assertThat(loginFailures()).hasSize(2).allSatisfy(e -> {
      assertThat(e.get("reason").asString()).isEqualTo("certificate revoked");
      assertThat(e.get("details").get("reasonCode").asString()).isEqualTo("certificate_revoked");
    });
  }

  /** Neither setting given: a certificate nobody can answer for signs in, with a warning naming it. */
  @Test
  void softFailsByDefaultWhenNoResponderOrCrlAnswers(CapturedOutput output) throws Exception {
    X509Certificate unreachable = TestCertificates.client("CN=alice").ocsp(down).crl(down).issuedBy(ca);
    X509Certificate namesNothing = TestCertificates.client("CN=bob").issuedBy(ca);

    assertThat(status(certLogin(unreachable))).isEqualTo(200);
    assertThat(status(certLogin(namesNothing))).isEqualTo(200);
    assertThat(output).contains("Allowing client certificate CN=alice", "no OCSP responder or CRL answered for it (soft-fail;",
        "Allowing client certificate CN=bob");
    assertThat(loginFailures()).isEmpty();
  }

  /** Fail-closed is still there with the default mode, and is what a chosen mode means when the fail mode is unset. */
  @Test
  void failsClosedWhenSetOrWhenAModeIsChosen() throws Exception {
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(down).issuedBy(ca);

    ens.getX509().setRevocationFail("closed");
    assertThat(revocation.failOpen()).isFalse();
    assertThat(error(certLogin(cert))).isEqualTo("The revocation status of the client certificate could not be determined");

    // A site that chose a mode itself and left the fail mode unset was fail-closed before, and stays so.
    ens.getX509().setRevocationFail("");
    for (String mode : List.of("ocsp", "crl", "ocsp-then-crl")) {
      ens.getX509().setRevocation(mode);
      assertThat(revocation.defaulted()).isFalse();
      assertThat(revocation.failOpen()).as(mode).isFalse();
      assertThat(status(certLogin(cert))).as(mode).isEqualTo(401);
    }

    ens.getX509().setRevocation("");
    assertThat(loginFailures()).hasSize(4)
        .allSatisfy(e -> assertThat(e.get("reason").asString()).isEqualTo("revocation unknown"));
  }

  /** off still turns the check off: nobody is asked, and a revoked certificate signs in. */
  @Test
  void offStillChecksNothing() throws Exception {
    ens.getX509().setRevocation("off");
    assertThat(revocation.mode()).isEqualTo(RevocationChecker.Mode.OFF);
    X509Certificate revoked = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    server.revoked.add(revoked.getSerialNumber());
    int before = server.ocspRequests.get();
    assertThat(status(certLogin(revoked))).isEqualTo(200);
    assertThat(server.ocspRequests.get()).isEqualTo(before);
  }

  /** The default check without trusted CAs cannot verify any answer: it lets the certificate in, warning. */
  @Test
  void softFailsByDefaultWithoutTrustedCas(CapturedOutput output) throws Exception {
    props.getX509().setTrustedCaFile("");
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(output).contains("without checking it for revocation: there is no X509_TRUSTED_CA_FILE");

    ens.getX509().setRevocationFail("closed");
    assertThat(error(certLogin(cert))).isEqualTo("The revocation status of the client certificate could not be determined");
  }

  @Test
  void checksOcspAtTheCertificatesResponder() throws Exception {
    ens.getX509().setRevocation("ocsp");
    X509Certificate good = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    X509Certificate revoked = TestCertificates.client("CN=bob").ocsp(server.url("/ocsp")).issuedBy(ca);
    server.revoked.add(revoked.getSerialNumber());

    assertThat(status(certLogin(good))).isEqualTo(200);
    MvcResult refused = certLogin(revoked);
    assertThat(status(refused)).isEqualTo(401);
    assertThat(error(refused)).isEqualTo("The client certificate has been revoked");

    assertThat(loginFailures()).singleElement().satisfies(e -> {
      assertThat(e.get("reason").asString()).isEqualTo("certificate revoked");
      assertThat(e.get("details").get("reasonCode").asString()).isEqualTo("certificate_revoked");
      assertThat(e.get("details").get("method").asString()).isEqualTo("x509");
    });
  }

  @Test
  void cachesGoodAndRevokedAnswers() throws Exception {
    ens.getX509().setRevocation("ocsp");
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    int before = server.ocspRequests.get();
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(server.ocspRequests.get()).isEqualTo(before + 1);

    // ENS_X509_REVOCATION_CACHE=0: every sign-in asks.
    ens.getX509().setRevocationCache(Duration.ZERO);
    revocation.clearCache();
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(server.ocspRequests.get()).isEqualTo(before + 3);
  }

  @Test
  void usesTheConfiguredResponderOverTheCertificates() throws Exception {
    ens.getX509().setRevocation("ocsp");
    ens.getX509().setOcspResponderUrl(server.url("/ocsp"));
    X509Certificate noAia = TestCertificates.client("CN=alice").issuedBy(ca);
    X509Certificate wrongAia = TestCertificates.client("CN=bob").ocsp(down).issuedBy(ca);
    server.revoked.add(wrongAia.getSerialNumber());
    assertThat(status(certLogin(noAia))).isEqualTo(200);
    assertThat(error(certLogin(wrongAia))).isEqualTo("The client certificate has been revoked");
  }

  @Test
  void failsClosedUnlessToldToFailOpen() throws Exception {
    ens.getX509().setRevocation("ocsp");
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(down).issuedBy(ca);
    MvcResult refused = certLogin(cert);
    assertThat(status(refused)).isEqualTo(401);
    assertThat(error(refused)).isEqualTo("The revocation status of the client certificate could not be determined");

    ens.getX509().setRevocationFail("open");
    assertThat(status(certLogin(cert))).isEqualTo(200);

    ens.getX509().setRevocationFail("closed");
    assertThat(loginFailures()).singleElement()
        .satisfies(e -> assertThat(e.get("reason").asString()).isEqualTo("revocation unknown"));
  }

  @Test
  void readsCrlsFromTheDirectoryWithoutFetchingDistributionPoints() throws Exception {
    X509Certificate good = TestCertificates.client("CN=alice").crl(server.url("/crl")).issuedBy(ca);
    X509Certificate revoked = TestCertificates.client("CN=bob").crl(server.url("/crl")).issuedBy(ca);
    ens.getX509().setRevocation("crl");
    ens.getX509().setCrlDir(crlDir(TestCertificates.crl(ca, revoked)).toString());
    int fetched = server.crlRequests.get();

    assertThat(status(certLogin(good))).isEqualTo(200);
    assertThat(error(certLogin(revoked))).isEqualTo("The client certificate has been revoked");
    assertThat(server.crlRequests.get()).isEqualTo(fetched);

    // Only another issuer's CRL: the status can't be determined.
    ens.getX509().setCrlDir(crlDir(TestCertificates.crl(otherCa)).toString());
    revocation.clearCache();
    assertThat(error(certLogin(good))).isEqualTo("The revocation status of the client certificate could not be determined");
  }

  @Test
  void fetchesCrlsFromHttpDistributionPointsAndReusesThem() throws Exception {
    ens.getX509().setRevocation("crl");
    X509Certificate good = TestCertificates.client("CN=alice").crl(server.url("/crl")).issuedBy(ca);
    X509Certificate revoked = TestCertificates.client("CN=bob").crl(server.url("/crl")).issuedBy(ca);
    server.crl = TestCertificates.crl(ca, revoked);
    int fetched = server.crlRequests.get();
    assertThat(status(certLogin(good))).isEqualTo(200);
    assertThat(error(certLogin(revoked))).isEqualTo("The client certificate has been revoked");
    assertThat(server.crlRequests.get()).isEqualTo(fetched + 1);
  }

  @Test
  void fallsBackToCrlsWhenOcspDoesNotAnswer() throws Exception {
    ens.getX509().setRevocation("ocsp-then-crl");
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(down).issuedBy(ca);
    X509Certificate revoked = TestCertificates.client("CN=bob").ocsp(down).issuedBy(ca);
    ens.getX509().setCrlDir(crlDir(TestCertificates.crl(ca, revoked)).toString());
    assertThat(status(certLogin(cert))).isEqualTo(200);
    assertThat(error(certLogin(revoked))).isEqualTo("The client certificate has been revoked");
  }

  @Test
  void mapsTheOlderCheckRevocationSettingToOcspThenCrlFailingClosed() throws Exception {
    props.getX509().setCheckRevocation(true);
    ens.getX509().setRevocation("off");
    ens.getX509().setRevocationFail("open");
    assertThat(revocation.mode()).isEqualTo(RevocationChecker.Mode.OCSP_THEN_CRL);
    assertThat(revocation.failOpen()).isFalse();
    X509Certificate cert = TestCertificates.client("CN=alice").ocsp(down).issuedBy(ca);
    assertThat(status(certLogin(cert))).isEqualTo(401);
    X509Certificate checked = TestCertificates.client("CN=alice").ocsp(server.url("/ocsp")).issuedBy(ca);
    assertThat(status(certLogin(checked))).isEqualTo(200);
  }

  @Test
  void refusesIssuersOutsideTheAllowlist() throws Exception {
    ens.getX509().setAllowedIssuers("CN=Revocation Test CA, O=Example Corp;CN=Somebody Else");
    assertThat(status(certLogin(TestCertificates.client("CN=alice").issuedBy(ca)))).isEqualTo(200);
    MvcResult refused = certLogin(TestCertificates.client("CN=alice").issuedBy(otherCa));
    assertThat(status(refused)).isEqualTo(401);
    assertThat(error(refused)).contains("issuer is not allowed");
  }

  @Test
  void mapsByEdipiUuidAndUpnPlaceholders() throws Exception {
    directory.modify(ALICE, List.of(new Modification(ModificationType.REPLACE, "employeeNumber", "1234567890"),
        new Modification(ModificationType.REPLACE, "description", "4fd0a1b2-3c4d-5e6f-8a9b-0c1d2e3f4a5b")));

    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(employeeNumber={edipi}))");
    MvcResult byUpn = certLogin(TestCertificates.client("CN=Somebody").upn("1234567890@mil").issuedBy(ca));
    assertThat(body(byUpn).get("user").get("username").asString()).isEqualTo("alice");
    MvcResult byCn = certLogin(TestCertificates.client("CN=ANDERSON.ALICE.A.1234567890,OU=DoD,O=U.S. Government").issuedBy(ca));
    assertThat(body(byCn).get("user").get("username").asString()).isEqualTo("alice");

    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(description={{uuid}}))");
    MvcResult byUuid = certLogin(TestCertificates.client("CN=Card").uri("urn:uuid:4fd0a1b2-3c4d-5e6f-8a9b-0c1d2e3f4a5b").issuedBy(ca));
    assertThat(body(byUuid).get("user").get("username").asString()).isEqualTo("alice");

    // Values are filter-escaped where they are inserted.
    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(uid={upn}))");
    MvcResult injected = certLogin(TestCertificates.client("CN=x").upn("*)(uid=*").issuedBy(ca));
    assertThat(status(injected)).isEqualTo(401);
    assertThat(error(injected)).isEqualTo("No directory account matches this certificate");

    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(employeeNumber={edipi}))");
    MvcResult missing = certLogin(TestCertificates.client("CN=NoEdipi").issuedBy(ca));
    assertThat(error(missing)).isEqualTo("The certificate has no edipi value to identify the user");
  }
}
