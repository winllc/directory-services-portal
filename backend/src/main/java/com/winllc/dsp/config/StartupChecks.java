package com.winllc.dsp.config;

import jakarta.annotation.PostConstruct;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/** Fails fast on configuration that would otherwise misbehave at runtime. */
@Configuration(proxyBeanMethods = false)
public class StartupChecks {
  private final PortalProperties props;
  private final ObjectProvider<EnsProperties> ens;

  public StartupChecks(PortalProperties props, ObjectProvider<EnsProperties> ens) {
    this.props = props;
    this.ens = ens;
  }

  @PostConstruct
  void check() {
    List<String> problems = props.getBanner().problems();
    if (!problems.isEmpty()) throw new IllegalStateException("Invalid banner configuration: " + String.join("; ", problems));
    List<String> x509 = props.getX509().problems();
    if (!x509.isEmpty()) {
      throw new IllegalStateException("Refusing to start: certificate sign-in through a proxy (X509_ENABLED=true, X509_SOURCE=header)"
          + " is not secure as configured: " + String.join("; ", x509) + ". Or turn it off with X509_ENABLED=false.");
    }
    EnsProperties shared = ens.getIfAvailable();
    if (shared != null && shared.getConsentBanner().isEnabled()) {
      try {
        shared.getConsentBanner().effectiveText();
      } catch (UncheckedIOException e) {
        throw new IllegalStateException("Refusing to start: the notice and consent banner is enabled but " + e.getMessage(), e);
      }
    }
  }
}
