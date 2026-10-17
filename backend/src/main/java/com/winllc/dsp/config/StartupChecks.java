package com.winllc.dsp.config;

import jakarta.annotation.PostConstruct;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/** Fails fast on configuration that would otherwise misbehave at runtime. */
@Configuration(proxyBeanMethods = false)
public class StartupChecks {
  private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);
  private final PortalProperties props;
  private final ObjectProvider<EnsProperties> ens;

  public StartupChecks(PortalProperties props, ObjectProvider<EnsProperties> ens) {
    this.props = props;
    this.ens = ens;
  }

  @PostConstruct
  void check() {
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
   * Revocation settings must be valid, and checking needs the trusted CAs (they verify OCSP
   * responses and CRLs). X.509 sign-in without revocation checking starts, with a warning.
   */
  private void checkRevocation(EnsProperties.X509 revocation) {
    List<String> problems = revocation.problems();
    if (!problems.isEmpty()) throw new IllegalStateException("Invalid X.509 revocation settings: " + String.join("; ", problems));
    String mode = revocation.configuredMode();
    boolean on = props.getX509().isCheckRevocation() || !(mode.isEmpty() || mode.equals("off"));
    if (!props.getX509().isEnabled()) return;
    String ca = props.getX509().getTrustedCaFile();
    if (on && (ca == null || ca.isBlank())) {
      throw new IllegalStateException("Refusing to start: certificate revocation checking (ENS_X509_REVOCATION) needs X509_TRUSTED_CA_FILE,"
          + " the CA certificates that issue user certificates, to verify OCSP responses and CRLs");
    }
    if (!on) {
      log.warn("X.509 sign-in is enabled without revocation checking (ENS_X509_REVOCATION=off). DISA STIG requires checking client"
          + " certificates for revocation: set ENS_X509_REVOCATION to ocsp, crl or ocsp-then-crl.");
    }
  }
}
