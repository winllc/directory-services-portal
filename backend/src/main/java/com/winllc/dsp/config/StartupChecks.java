package com.winllc.dsp.config;

import jakarta.annotation.PostConstruct;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import com.winllc.dsp.util.LogSafe;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Fails fast on configuration that would otherwise misbehave at runtime. */
@Configuration(proxyBeanMethods = false)
public class StartupChecks {
  private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
  /** OIDC claims a provider lets be changed or reassigned: a poor identity to map to a directory entry. */
  static final java.util.Set<String> MUTABLE_OIDC_CLAIMS = java.util.Set.of("preferred_username", "email", "upn", "unique_name", "name",
      "nickname", "given_name", "family_name", "username", "login");
  private final PortalProperties props;
  private final ObjectProvider<EnsProperties> ens;
  private final ObjectProvider<com.winllc.dsp.sso.SsoProperties> sso;
  private final Environment env;

  public StartupChecks(PortalProperties props, ObjectProvider<EnsProperties> ens, ObjectProvider<com.winllc.dsp.sso.SsoProperties> sso,
      Environment env) {
    this.props = props;
    this.ens = ens;
    this.sso = sso;
    this.env = env;
  }

  @PostConstruct
  void check() {
    checkMemoryMode();
    checkClientAddresses();
    if (!props.isMemoryMode()) {
      try {
        props.getLdap().resolvedBindPassword();
      } catch (UncheckedIOException e) {
        throw new IllegalStateException("Refusing to start: " + e.getMessage(), e);
      }
      if (!props.getLdap().isTlsRejectUnauthorized()) {
        log.warn("LDAP_TLS_REJECT_UNAUTHORIZED is false: the directory server's certificate is NOT verified (trust-all). This is"
            + " not FIPS 140-3 or DISA STIG appropriate; use it only in test setups and give the API a trust store instead.");
      }
    }
    List<String> problems = props.getBanner().problems();
    if (!problems.isEmpty()) throw new IllegalStateException("Invalid banner configuration: " + String.join("; ", problems));
    List<String> x509 = props.getX509().problems();
    if (!x509.isEmpty()) {
      throw new IllegalStateException("Refusing to start: certificate sign-in through a proxy (X509_ENABLED=true, X509_SOURCE=header)"
          + " is not secure as configured: " + String.join("; ", x509) + ". Or turn it off with X509_ENABLED=false.");
    }
    com.winllc.dsp.sso.SsoProperties single = sso.getIfAvailable();
    if (single != null) {
      List<String> ssoProblems = single.problems();
      if (!ssoProblems.isEmpty()) throw new IllegalStateException("Refusing to start: single sign-on: " + String.join("; ", ssoProblems));
      if (single.getOidc().enabled()) {
        log.info("Single sign-on: OpenID Connect ({}) offered", single.getOidc().getIssuerUri());
        String claim = single.getOidc().getUsernameClaim().trim();
        if (MUTABLE_OIDC_CLAIMS.contains(claim.toLowerCase(java.util.Locale.ROOT))) {
          log.warn("SSO_OIDC_USERNAME_CLAIM is {}, which the identity provider lets users or administrators change (a rename, an"
              + " address handed to someone else): a renamed account can then match, and sign in as, another directory entry."
              + " Map an immutable claim instead, held in a directory attribute: sub, or oid for Microsoft Entra ID (README, Single"
              + " sign-on: moving to an immutable claim).", LogSafe.of(claim));
        }
        if (!single.getOidc().isProviderLogout()) {
          log.warn("SSO_OIDC_PROVIDER_LOGOUT is false: signing out of the portal leaves the OpenID Connect provider's session, so"
              + " the next sign-on on this browser needs no password. Set it to true (and allow <PORTAL_PUBLIC_URL>/login?signed_out"
              + " as a post-logout redirect URI at the provider) to sign out there too.");
        }
      }
      if (single.getSaml().enabled()) {
        log.info("Single sign-on: SAML 2.0 (metadata {}) offered", single.getSaml().getMetadataLocation());
        if (single.getSaml().isSingleLogout() && !single.getSaml().signing()) {
          log.warn("SSO_SAML_SINGLE_LOGOUT is true but SSO_SAML_SIGNING_KEY_FILE and SSO_SAML_SIGNING_CERT_FILE are not set: a"
              + " LogoutRequest must be signed, so single logout stays off and signing out ends the portal's session only.");
        }
      }
    }
    EnsProperties shared = ens.getIfAvailable();
    if (shared != null) checkRevocation(shared.getX509());
    if (shared != null && shared.getConsentBanner().isEnabled()) {
      try {
        shared.getConsentBanner().effectiveText();
      } catch (UncheckedIOException e) {
        throw new IllegalStateException("Refusing to start: the notice and consent banner is enabled but " + e.getMessage(), e);
      }
    }
  }

  /**
   * The embedded demo directory has public credentials (admin/password) and seeds the demo
   * configuration: it starts only when DEMO_MODE=true says this is a demo, or when the API listens
   * on a loopback address only (SERVER_ADDRESS), where nobody else can reach it. DIRECTORY_MODE
   * defaults to memory, so this is what keeps a bare jar from serving a portal anyone can administer.
   */
  private void checkMemoryMode() {
    if (!props.isMemoryMode() || props.isDemoMode()) return;
    String address = env.getProperty("server.address", "");
    if (isLoopback(address)) return;
    String message = "Refusing to start: DIRECTORY_MODE=memory runs the embedded demo directory, whose administrator password"
        + " is public, and the API listens on " + (address.isBlank() ? "every interface" : LogSafe.of(address))
        + ". For a real directory set DIRECTORY_MODE=ldap and LDAP_URL; for a demo set DEMO_MODE=true (or"
        + " SERVER_ADDRESS=127.0.0.1 to keep it to this host).";
    log.error(message);
    throw new IllegalStateException(message);
  }

  /**
   * Per-address limits (failed API tokens, single sign-on starts, the sign-in lockout) key on the
   * client address as Spring resolves it. Behind a proxy with SERVER_FORWARD_HEADERS_STRATEGY=none
   * that is the proxy's address, so one noisy client makes every client behind it hit them. A proxy
   * is certain when certificates arrive in a header (X509_SOURCE=header) or the API listens on
   * loopback only; warned about rather than refused, so an existing installation still starts.
   */
  private void checkClientAddresses() {
    String strategy = env.getProperty("server.forward-headers-strategy", "none").trim();
    if (!strategy.isEmpty() && !strategy.equalsIgnoreCase("none")) return;
    boolean headerCertificates = props.getX509().isEnabled() && "header".equalsIgnoreCase(props.getX509().getSource());
    boolean loopback = isLoopback(env.getProperty("server.address", ""));
    if (!headerCertificates && !loopback) return;
    log.warn("The API is behind a proxy ({}) but SERVER_FORWARD_HEADERS_STRATEGY is none: every request seems to come from the"
        + " proxy's address, so the per-address limits (API_FAILED_TOKEN_LIMIT_PER_MINUTE, SSO_START_LIMIT_PER_MINUTE, the"
        + " sign-in lockout) count all clients behind it together, and the audit trail records the proxy as source.ip. Set"
        + " SERVER_FORWARD_HEADERS_STRATEGY=framework, with a proxy that overwrites X-Forwarded-For (the packaged web tier does).",
        headerCertificates ? "X509_SOURCE=header" : "SERVER_ADDRESS is a loopback address");
  }

  /** A literal loopback address or "localhost" (no name is looked up). */
  static boolean isLoopback(String address) {
    if (address == null) return false;
    String a = address.trim().toLowerCase(java.util.Locale.ROOT);
    if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
    if (a.equals("localhost") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")) return true;
    return a.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
  }

  /**
   * Revocation settings must be valid, and a check somebody chose needs the trusted CAs (they verify OCSP responses
   * and CRLs). Left unset, revocation is checked live (ocsp-then-crl) and soft-fails, and startup says so: what that
   * lets through and what to set instead. Without trusted CAs that default check cannot answer for anybody, which
   * is warned about rather than refused, so an upgrade starts. X.509 sign-in with the check off starts, with a
   * warning.
   */
  private void checkRevocation(EnsProperties.X509 revocation) {
    List<String> problems = revocation.problems();
    if (!problems.isEmpty()) throw new IllegalStateException("Invalid X.509 revocation settings: " + String.join("; ", problems));
    if (!props.getX509().isEnabled()) return;
    String mode = revocation.configuredMode();
    // The older X509_CHECK_REVOCATION=true: ocsp-then-crl, failing closed, while the mode is unset or off.
    boolean legacy = props.getX509().isCheckRevocation() && (mode.isEmpty() || mode.equals("off"));
    boolean on = legacy || !mode.equals("off");
    boolean defaulted = !legacy && mode.isEmpty();
    boolean failOpen = !legacy && revocation.failOpen();
    if (!on) {
      log.warn("X.509 sign-in is enabled without revocation checking (ENS_X509_REVOCATION=off). DISA STIG requires checking client"
          + " certificates for revocation: unset ENS_X509_REVOCATION, or set it to ocsp, crl or ocsp-then-crl.");
      return;
    }
    String ca = props.getX509().getTrustedCaFile();
    boolean noCa = ca == null || ca.isBlank();
    if (noCa && !(defaulted && failOpen)) {
      throw new IllegalStateException("Refusing to start: certificate revocation checking (ENS_X509_REVOCATION) needs X509_TRUSTED_CA_FILE,"
          + " the CA certificates that issue user certificates, to verify OCSP responses and CRLs");
    }
    if (noCa) {
      log.warn("Client certificates are to be checked for revocation by default (ocsp-then-crl, soft-fail), but there is no"
          + " X509_TRUSTED_CA_FILE to verify OCSP responses and CRLs with: no certificate can be checked, and each signs in"
          + " with a warning. Set X509_TRUSTED_CA_FILE to the CA certificates that issue user certificates, or"
          + " ENS_X509_REVOCATION=off to check nothing.");
    } else if (failOpen && defaulted && revocation.configuredFail().isEmpty()) {
      log.warn("Client certificates are checked for revocation at sign-in by default (ocsp-then-crl), soft-fail: one found revoked"
          + " is refused, and one no OCSP responder or CRL answers for signs in with a warning. Set ENS_X509_REVOCATION_FAIL=closed"
          + " to refuse those as DISA STIG expects, ENS_X509_CRL_DIR to read CRLs from disk where nothing can be fetched, or"
          + " ENS_X509_REVOCATION=off to check nothing.");
    } else if (failOpen) {
      log.warn("ENS_X509_REVOCATION_FAIL=open: a client certificate whose revocation status cannot be determined signs in anyway."
          + " DISA STIG expects closed.");
    }
    String crlDir = revocation.getCrlDir();
    String ocsp = revocation.getOcspResponderUrl();
    log.info("Client certificates are checked for revocation at sign-in: {}{}{}{}",
        legacy ? "ocsp-then-crl (X509_CHECK_REVOCATION)" : defaulted ? EnsProperties.X509.DEFAULT_MODE + " (the default)" : mode,
        failOpen ? ", soft-fail" : ", fail-closed",
        crlDir == null || crlDir.isBlank() ? "" : ", CRLs from " + crlDir.trim(),
        ocsp == null || ocsp.isBlank() ? "" : ", OCSP at " + ocsp.trim());
  }
}
