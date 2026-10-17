package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.dsp.x509.CertificateIdentity;
import com.winllc.dsp.x509.CertificateParser;
import com.winllc.dsp.x509.CertificateRejectedException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class X509ParsingTest {
  private static final TestCertificates.Ca CA = TestCertificates.ca("CN=Test CA");
  private static final X509Certificate CERT = TestCertificates.client("EMAILADDRESS=jd@example.org,UID=jdoe,CN=Jane Doe,O=Example")
      .email("jane@example.com")
      .upn("1234567890@mil")
      .issuedBy(CA);

  @Test
  void decodesEveryForwardedHeaderFormat() throws Exception {
    String pem = TestCertificates.pem(CERT);
    byte[] der = CERT.getEncoded();
    assertThat(CertificateParser.parseHeader(pem)).isEqualTo(CERT);
    // Apache/HAProxy style: newlines replaced by spaces.
    assertThat(CertificateParser.parseHeader(pem.replace("\n", " "))).isEqualTo(CERT);
    // nginx $ssl_client_escaped_cert.
    assertThat(CertificateParser.parseHeader(URLEncoder.encode(pem, StandardCharsets.UTF_8).replace("+", "%20"))).isEqualTo(CERT);
    // AWS ALB: URL-encoded with '+', '=' and '/' left as-is.
    String alb = pem.replace("\n", "%0A").replace(" ", "%20");
    assertThat(CertificateParser.parseHeader(alb)).isEqualTo(CERT);
    // Base64 DER.
    assertThat(CertificateParser.parseHeader(Base64.getEncoder().encodeToString(der))).isEqualTo(CERT);
    // Envoy XFCC.
    String xfcc = "Hash=abc;Cert=\"" + URLEncoder.encode(pem, StandardCharsets.UTF_8) + "\";Subject=\"CN=x\"";
    assertThat(CertificateParser.parseHeader(xfcc)).isEqualTo(CERT);

    assertThatThrownBy(() -> CertificateParser.parseHeader("not a certificate")).isInstanceOf(CertificateRejectedException.class);
  }

  @Test
  void extractsIdentityValues() {
    CertificateIdentity id = CertificateIdentity.of(CERT);
    assertThat(id.cn()).isEqualTo("Jane Doe");
    assertThat(id.uid()).isEqualTo("jdoe");
    // The rfc822Name SAN wins over the legacy emailAddress subject attribute.
    assertThat(id.email()).isEqualTo("jane@example.com");
    assertThat(id.upn()).isEqualTo("1234567890@mil");
    assertThat(id.serial()).isEqualTo(CERT.getSerialNumber().toString(16));
    assertThat(id.placeholders()).containsKeys("subject", "cn", "uid", "email", "upn", "serial");

    X509Certificate legacy = TestCertificates.client("EMAILADDRESS=old@example.org,CN=Legacy").issuedBy(CA);
    assertThat(CertificateIdentity.of(legacy).email()).isEqualTo("old@example.org");
  }

  @Test
  void extractsTheDodEdipiAndThePivUuid() {
    // From the UPN 1234567890@mil.
    CertificateIdentity id = CertificateIdentity.of(CERT);
    assertThat(id.edipi()).isEqualTo("1234567890");
    assertThat(id.placeholders()).containsKeys("edipi").doesNotContainKey("uuid");
    // Else from a CN LAST.FIRST.MI.1234567890.
    X509Certificate cac = TestCertificates.client("CN=DOE.JANE.Q.9876543210,OU=USN,OU=PKI,OU=DoD,O=U.S. Government,C=US")
        .uri("urn:uuid:4FD0A1B2-3C4D-5E6F-8A9B-0C1D2E3F4A5B").issuedBy(CA);
    CertificateIdentity piv = CertificateIdentity.of(cac);
    assertThat(piv.edipi()).isEqualTo("9876543210");
    assertThat(piv.uuid()).isEqualTo("4FD0A1B2-3C4D-5E6F-8A9B-0C1D2E3F4A5B");
    // Neither.
    assertThat(CertificateIdentity.of(TestCertificates.client("CN=Jane Doe").upn("jdoe@example.org").issuedBy(CA)).edipi()).isNull();
    assertThat(CertificateIdentity.of(TestCertificates.client("CN=x").uri("https://example.org/x").issuedBy(CA)).uuid()).isNull();
  }
}
