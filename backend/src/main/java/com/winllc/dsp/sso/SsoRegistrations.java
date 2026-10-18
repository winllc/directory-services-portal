package com.winllc.dsp.sso;

import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.security.saml2.provider.service.registration.Saml2MessageBinding;

/**
 * The configured providers, read from the network on first use rather than at startup, so the
 * portal (and password sign-in) starts while an identity provider is unreachable. A failed read is
 * tried again after a minute.
 */
final class SsoRegistrations {
  private static final Logger log = LoggerFactory.getLogger(SsoRegistrations.class);
  static final String OIDC = "oidc";
  static final String SAML = "saml";
  private static final Duration RETRY = Duration.ofMinutes(1);

  private SsoRegistrations() {}

  /** OpenID Connect from the issuer's discovery document (whose issuer must match). */
  static final class Oidc implements ClientRegistrationRepository {
    private final SsoProperties.Oidc cfg;
    private final String base;
    private volatile ClientRegistration registration;
    private volatile Instant failedAt;

    Oidc(SsoProperties props) {
      this.cfg = props.getOidc();
      this.base = props.base();
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
      if (!OIDC.equals(registrationId) || !cfg.enabled()) return null;
      ClientRegistration r = registration;
      if (r != null) return r;
      synchronized (this) {
        if (registration != null) return registration;
        if (failedAt != null && failedAt.plus(RETRY).isAfter(Instant.now())) return null;
        try {
          String secret = cfg.resolvedClientSecret();
          ClientRegistration.Builder b = ClientRegistrations.fromIssuerLocation(cfg.getIssuerUri())
              .registrationId(OIDC)
              .clientId(cfg.getClientId())
              .scope(cfg.scopeList())
              .redirectUri(base + "/api/auth/sso/callback/{registrationId}")
              .userNameAttributeName("sub")
              .clientName(cfg.getLabel().isBlank() ? "Single sign-on" : cfg.getLabel());
          if (secret == null || secret.isBlank()) {
            // A public client: PKCE alone protects the code.
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
          } else {
            b.clientSecret(secret);
          }
          registration = b.build();
          failedAt = null;
          log.info("Single sign-on: OpenID Connect provider {} ready", cfg.getIssuerUri());
          return registration;
        } catch (RuntimeException e) {
          failedAt = Instant.now();
          log.error("Single sign-on: cannot read the OpenID Connect provider {}: {}", cfg.getIssuerUri(), LogSafe.of(e.getMessage()));
          return null;
        }
      }
    }
  }

  /** SAML 2.0 from the IdP's metadata. */
  static final class Saml implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {
    private final SsoProperties.Saml cfg;
    private final String base;
    private volatile RelyingPartyRegistration registration;
    private volatile Instant failedAt;

    Saml(SsoProperties props) {
      this.cfg = props.getSaml();
      this.base = props.base();
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
      if (!SAML.equals(registrationId) || !cfg.enabled()) return null;
      RelyingPartyRegistration r = registration;
      if (r != null) return r;
      synchronized (this) {
        if (registration != null) return registration;
        if (failedAt != null && failedAt.plus(RETRY).isAfter(Instant.now())) return null;
        try {
          RelyingPartyRegistration.Builder b = RelyingPartyRegistrations.fromMetadataLocation(cfg.resourceLocation())
              .registrationId(SAML)
              .entityId(cfg.getEntityId().isBlank() ? base + "/api/auth/sso/saml/metadata" : cfg.getEntityId())
              .assertionConsumerServiceLocation(base + "/api/auth/sso/saml/acs/{registrationId}")
              .assertionConsumerServiceBinding(Saml2MessageBinding.POST);
          if (cfg.signing()) {
            Saml2X509Credential signing = Saml2X509Credential.signing(privateKey(Path.of(cfg.getSigningKeyFile())),
                certificate(Path.of(cfg.getSigningCertificateFile())));
            b.signingX509Credentials(c -> c.add(signing));
          }
          RelyingPartyRegistration built = b.build();
          if (built.getAssertingPartyMetadata().getSingleSignOnServiceBinding() != Saml2MessageBinding.REDIRECT) {
            log.warn("Single sign-on: the SAML IdP's metadata offers sign-in by HTTP-POST only; the portal sends AuthnRequests by"
                + " HTTP-Redirect, which the IdP must accept at {}", built.getAssertingPartyMetadata().getSingleSignOnServiceLocation());
            built = built.mutate().assertingPartyMetadata(p -> p.singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)).build();
          }
          registration = built;
          failedAt = null;
          log.info("Single sign-on: SAML IdP {} ready", LogSafe.of(built.getAssertingPartyMetadata().getEntityId()));
          return registration;
        } catch (RuntimeException | IOException | java.security.GeneralSecurityException e) {
          failedAt = Instant.now();
          log.error("Single sign-on: cannot read the SAML IdP metadata {}: {}", cfg.getMetadataLocation(), LogSafe.of(e.getMessage()));
          return null;
        }
      }
    }

    @Override
    public Iterator<RelyingPartyRegistration> iterator() {
      RelyingPartyRegistration r = findByRegistrationId(SAML);
      return r == null ? List.<RelyingPartyRegistration>of().iterator() : List.of(r).iterator();
    }
  }

  static PrivateKey privateKey(Path pem) throws IOException, java.security.GeneralSecurityException {
    String text = Files.readString(pem);
    if (!text.contains("-----BEGIN PRIVATE KEY-----")) {
      throw new java.security.spec.InvalidKeySpecException("SSO_SAML_SIGNING_KEY_FILE must be an unencrypted PKCS#8 PEM key (BEGIN PRIVATE KEY)");
    }
    byte[] der = Base64.getMimeDecoder().decode(text.replaceAll("-----[A-Z ]+-----", ""));
    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
    for (String alg : List.of("RSA", "EC")) {
      try {
        return KeyFactory.getInstance(alg).generatePrivate(spec);
      } catch (java.security.spec.InvalidKeySpecException e) {
        // Try the next algorithm.
      }
    }
    throw new java.security.spec.InvalidKeySpecException("Unsupported key in " + pem + " (RSA or EC expected)");
  }

  static X509Certificate certificate(Path pem) throws IOException, java.security.GeneralSecurityException {
    try (InputStream in = Files.newInputStream(pem)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
  }
}
