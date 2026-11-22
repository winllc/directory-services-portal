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
import java.time.Clock;
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

  /**
   * SAML 2.0 from the IdP's metadata, read again every SSO_SAML_METADATA_REFRESH (an IdP rolls its
   * signing certificate) and, at most once a minute, when a response fails signature validation. A
   * failed read keeps the last good registration.
   */
  static final class Saml implements RelyingPartyRegistrationRepository, Iterable<RelyingPartyRegistration> {
    private final SsoProperties.Saml cfg;
    private final String base;
    private final Clock clock;
    private volatile RelyingPartyRegistration registration;
    private volatile Instant loadedAt;
    private volatile Instant failedAt;
    private Instant reloadedForSignatureAt;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    Saml(SsoProperties props) {
      this(props, Clock.systemUTC());
    }

    Saml(SsoProperties props, Clock clock) {
      this.cfg = props.getSaml();
      this.base = props.base();
      this.clock = clock;
    }

    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
      if (!SAML.equals(registrationId) || !cfg.enabled()) return null;
      RelyingPartyRegistration r = registration;
      if (r != null && !stale()) return r;
      // While another request reads the metadata again, the copy in use answers.
      if (r != null) {
        if (!lock.tryLock()) return r;
      } else {
        lock.lock();
      }
      try {
        r = registration;
        if (r != null && !stale()) return r;
        if (failedAt != null && failedAt.plus(RETRY).isAfter(clock.instant())) return r;
        RelyingPartyRegistration loaded = load();
        return loaded != null ? loaded : r;
      } finally {
        lock.unlock();
      }
    }

    private boolean stale() {
      Duration refresh = cfg.getMetadataRefresh();
      return refresh != null && refresh.isPositive() && !loadedAt.plus(refresh).isAfter(clock.instant());
    }

    /**
     * After a response failed signature validation: the metadata read again now (at most once a
     * minute, whatever the responses), when its signing certificates differ from those in use;
     * else null.
     */
    RelyingPartyRegistration reloadAfterSignatureFailure() {
      if (!cfg.enabled()) return null;
      lock.lock();
      try {
        Instant now = clock.instant();
        if (reloadedForSignatureAt != null && reloadedForSignatureAt.plus(RETRY).isAfter(now)) return null;
        reloadedForSignatureAt = now;
        RelyingPartyRegistration before = registration;
        RelyingPartyRegistration loaded = load();
        return loaded != null && (before == null || !sameVerification(before, loaded)) ? loaded : null;
      } finally {
        lock.unlock();
      }
    }

    private static boolean sameVerification(RelyingPartyRegistration a, RelyingPartyRegistration b) {
      return java.util.Set.copyOf(a.getAssertingPartyMetadata().getVerificationX509Credentials())
          .equals(java.util.Set.copyOf(b.getAssertingPartyMetadata().getVerificationX509Credentials()));
    }

    /** Reads the metadata and makes it the registration in use; null (logged) when it can't be read. */
    private RelyingPartyRegistration load() {
      RelyingPartyRegistration before = registration;
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
          if (before == null) {
            log.warn("Single sign-on: the SAML IdP's metadata offers sign-in by HTTP-POST only; the portal sends AuthnRequests by"
                + " HTTP-Redirect, which the IdP must accept at {}", built.getAssertingPartyMetadata().getSingleSignOnServiceLocation());
          }
          built = built.mutate().assertingPartyMetadata(p -> p.singleSignOnServiceBinding(Saml2MessageBinding.REDIRECT)).build();
        }
        built = withSingleLogout(built, before == null);
        registration = built;
        loadedAt = clock.instant();
        failedAt = null;
        if (before == null) {
          log.info("Single sign-on: SAML IdP {} ready", LogSafe.of(built.getAssertingPartyMetadata().getEntityId()));
        } else if (!sameVerification(before, built)) {
          log.info("Single sign-on: the SAML IdP's metadata has new signing certificates; using them");
        }
        return built;
      } catch (RuntimeException | IOException | java.security.GeneralSecurityException e) {
        failedAt = clock.instant();
        log.error("Single sign-on: cannot read the SAML IdP metadata {}{}: {}", cfg.getMetadataLocation(),
            before == null ? "" : " (the copy read before stays in use)", LogSafe.of(e.getMessage()));
        return null;
      }
    }

    /**
     * With SSO_SAML_SINGLE_LOGOUT (and the signing credential): this SP's SingleLogoutService
     * ({@code /api/auth/sso/saml/logout}, HTTP-Redirect), when the IdP's metadata offers one by
     * HTTP-Redirect. The browser goes there by navigation: an HTTP-POST form to the IdP would be
     * refused by the CSP's form-action 'self', so an IdP offering HTTP-POST first gets none.
     */
    private RelyingPartyRegistration withSingleLogout(RelyingPartyRegistration built, boolean first) {
      if (!cfg.singleLogoutConfigured()) return built;
      var idp = built.getAssertingPartyMetadata();
      if (idp.getSingleLogoutServiceLocation() == null) {
        if (first) log.warn("Single sign-on: SSO_SAML_SINGLE_LOGOUT is on, but the SAML IdP's metadata offers no SingleLogoutService; signing"
            + " out ends the portal's session only");
        return built;
      }
      if (idp.getSingleLogoutServiceBinding() != Saml2MessageBinding.REDIRECT) {
        if (first) log.warn("Single sign-on: the SAML IdP's metadata offers single logout by HTTP-POST first ({}); the portal sends"
            + " LogoutRequests by HTTP-Redirect only, so signing out ends the portal's session only", LogSafe.of(idp.getSingleLogoutServiceLocation()));
        return built;
      }
      String location = base + SsoSecurity.SAML_LOGOUT;
      if (first) log.info("Single sign-on: SAML single logout through {}", LogSafe.of(idp.getSingleLogoutServiceLocation()));
      return built.mutate().singleLogoutServiceLocation(location).singleLogoutServiceResponseLocation(location)
          .singleLogoutServiceBinding(Saml2MessageBinding.REDIRECT).build();
    }

    /** Whether single logout is in use: configured, and the IdP offers it as the portal sends it. */
    boolean singleLogout() {
      RelyingPartyRegistration r = cfg.singleLogoutConfigured() ? findByRegistrationId(SAML) : null;
      return r != null && r.getSingleLogoutServiceLocation() != null;
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
