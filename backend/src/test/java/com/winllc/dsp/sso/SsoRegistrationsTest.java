package com.winllc.dsp.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.saml2.core.OpenSamlInitializationService;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

/**
 * The SAML IdP's metadata is read again after SSO_SAML_METADATA_REFRESH and after a response fails
 * signature validation (an IdP rolling its signing certificate), keeping the last good copy when a
 * read fails.
 */
class SsoRegistrationsTest {
  static final X509Certificate FIRST = certificate("CN=IdP signing 1");
  static final X509Certificate SECOND = certificate("CN=IdP signing 2");
  static final X509Certificate THIRD = certificate("CN=IdP signing 3");

  static {
    OpenSamlInitializationService.initialize();
  }

  @TempDir Path dir;
  Path metadata;
  MutableClock clock;
  SsoProperties props;

  @BeforeEach
  void setUp() throws Exception {
    metadata = dir.resolve("idp-metadata.xml");
    write(FIRST);
    clock = new MutableClock(Instant.parse("2026-11-21T08:00:00Z"));
    props = new SsoProperties();
    props.setPublicUrl("https://portal.example");
    props.getSaml().setMetadataLocation(metadata.toString());
  }

  @Test
  void readsTheMetadataAgainAfterItsLifetime() throws Exception {
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    assertThat(signer(saml)).isEqualTo(FIRST);
    write(SECOND);
    clock.advance(Duration.ofMinutes(59));
    assertThat(signer(saml)).isEqualTo(FIRST);
    clock.advance(Duration.ofMinutes(1));
    assertThat(signer(saml)).isEqualTo(SECOND);
  }

  @Test
  void keepsTheLastGoodMetadataWhenAReadFails() throws Exception {
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    assertThat(signer(saml)).isEqualTo(FIRST);
    Files.writeString(metadata, "<not metadata");
    clock.advance(Duration.ofHours(2));
    assertThat(signer(saml)).isEqualTo(FIRST);
    // Tried again a minute after the failure.
    write(SECOND);
    clock.advance(Duration.ofSeconds(30));
    assertThat(signer(saml)).isEqualTo(FIRST);
    clock.advance(Duration.ofSeconds(31));
    assertThat(signer(saml)).isEqualTo(SECOND);
  }

  @Test
  void zeroReadsTheMetadataOnce() throws Exception {
    props.getSaml().setMetadataRefresh(Duration.ZERO);
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    assertThat(signer(saml)).isEqualTo(FIRST);
    write(SECOND);
    clock.advance(Duration.ofDays(30));
    assertThat(signer(saml)).isEqualTo(FIRST);
  }

  @Test
  void readsTheMetadataAtOnceAfterASignatureFailureAtMostOnceAMinute() throws Exception {
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    assertThat(signer(saml)).isEqualTo(FIRST);
    // Unchanged metadata: nothing new to check the response with.
    assertThat(saml.reloadAfterSignatureFailure()).isNull();
    write(SECOND);
    clock.advance(Duration.ofSeconds(61));
    RelyingPartyRegistration fresh = saml.reloadAfterSignatureFailure();
    assertThat(fresh).isNotNull();
    assertThat(signer(fresh)).isEqualTo(SECOND);
    assertThat(signer(saml)).isEqualTo(SECOND);
    write(THIRD);
    assertThat(saml.reloadAfterSignatureFailure()).isNull();
    assertThat(signer(saml)).isEqualTo(SECOND);
    clock.advance(Duration.ofSeconds(61));
    assertThat(signer(saml.reloadAfterSignatureFailure())).isEqualTo(THIRD);
  }

  @Test
  void checksAResponseAgainWithTheIdpsNewCertificate() throws Exception {
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    RelyingPartyRegistration before = saml.findByRegistrationId(SsoRegistrations.SAML);
    List<X509Certificate> checkedWith = new ArrayList<>();
    AuthenticationProvider verifier = new AuthenticationProvider() {
      @Override
      public Authentication authenticate(Authentication a) {
        X509Certificate cert = signer(((Saml2AuthenticationToken) a).getRelyingPartyRegistration());
        checkedWith.add(cert);
        if (!cert.equals(SECOND)) throw new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_SIGNATURE, "bad"));
        return new TestingAuthenticationToken("alice", null);
      }

      @Override
      public boolean supports(Class<?> type) {
        return Saml2AuthenticationToken.class.isAssignableFrom(type);
      }
    };
    AuthenticationProvider provider = SsoSecurity.retryingSignature(verifier, saml);
    // The IdP rolled its certificate: the response is checked again with the new metadata.
    write(SECOND);
    assertThat(provider.authenticate(new Saml2AuthenticationToken(before, "<Response/>")).getName()).isEqualTo("alice");
    assertThat(checkedWith).containsExactly(FIRST, SECOND);
    // A forged signature fails again, without another read within the minute.
    checkedWith.clear();
    write(THIRD);
    assertThatThrownBy(() -> provider.authenticate(new Saml2AuthenticationToken(before, "<Response/>")))
        .isInstanceOf(Saml2AuthenticationException.class);
    assertThat(checkedWith).containsExactly(FIRST);
  }

  @Test
  void otherFailuresAreNotCheckedAgain() {
    SsoRegistrations.Saml saml = new SsoRegistrations.Saml(props, clock);
    RelyingPartyRegistration r = saml.findByRegistrationId(SsoRegistrations.SAML);
    int[] calls = {0};
    AuthenticationProvider failing = new AuthenticationProvider() {
      @Override
      public Authentication authenticate(Authentication a) {
        calls[0]++;
        throw new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_ASSERTION, "expired"));
      }

      @Override
      public boolean supports(Class<?> type) {
        return true;
      }
    };
    assertThatThrownBy(() -> SsoSecurity.retryingSignature(failing, saml).authenticate(new Saml2AuthenticationToken(r, "<Response/>")))
        .isInstanceOf(Saml2AuthenticationException.class);
    assertThat(calls[0]).isEqualTo(1);
  }

  private X509Certificate signer(SsoRegistrations.Saml saml) {
    return signer(saml.findByRegistrationId(SsoRegistrations.SAML));
  }

  private static X509Certificate signer(RelyingPartyRegistration r) {
    return r.getAssertingPartyMetadata().getVerificationX509Credentials().iterator().next().getCertificate();
  }

  private void write(X509Certificate cert) throws Exception {
    String encoded = Base64.getEncoder().encodeToString(cert.getEncoded());
    Files.writeString(metadata, """
        <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#" entityID="https://idp.test">
          <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
            <md:KeyDescriptor use="signing"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>
            <md:SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect" Location="https://idp.test/sso"/>
          </md:IDPSSODescriptor>
        </md:EntityDescriptor>""".formatted(encoded));
  }

  private static X509Certificate certificate(String subject) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(256);
      KeyPair keys = g.generateKeyPair();
      X500Name name = new X500Name(subject);
      Instant now = Instant.now();
      var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minusSeconds(3600)),
          Date.from(now.plus(Duration.ofDays(1))), name, keys.getPublic());
      return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
