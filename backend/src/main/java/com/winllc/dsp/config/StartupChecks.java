package com.winllc.dsp.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.context.annotation.Configuration;

/** Fails fast on configuration that would otherwise misbehave at runtime. */
@Configuration(proxyBeanMethods = false)
public class StartupChecks {
  private final PortalProperties props;

  public StartupChecks(PortalProperties props) {
    this.props = props;
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
  }
}
