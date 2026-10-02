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
  }
}
