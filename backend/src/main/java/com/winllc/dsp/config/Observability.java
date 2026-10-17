package com.winllc.dsp.config;

import com.enssolutions.license.LicenseState;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.license.Licensing;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Locale;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.EndpointFilter;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Health and metrics (ENS platform baseline). The readiness group covers what the API can't serve
 * without: the directory ({@code ldap}), the audit log ({@code auditLog}) and the data disk; the
 * liveness group only the application's own state. The Prometheus endpoint exists only on a
 * separate management port ({@code MANAGEMENT_SERVER_PORT}), never on the port users reach.
 */
@Configuration(proxyBeanMethods = false)
public class Observability {

  /** The directory answers (a root DSE read through the service account's pool). */
  @Bean
  HealthIndicator ldapHealthIndicator(LdapDirectory directory) {
    return () -> {
      try {
        directory.ping();
        return Health.up().build();
      } catch (RuntimeException e) {
        return Health.down().withDetail("error", e.getMessage()).build();
      }
    };
  }

  /** Audit events can be stored: changes and sign-ins are refused while they can't. */
  @Bean
  HealthIndicator auditLogHealthIndicator(AuditLog audit) {
    return () -> audit.store().writable()
        ? Health.up().build()
        : Health.down().withDetail("error", "The audit store is not writable or its disk is full").build();
  }

  /** {@code /actuator/prometheus} only on the management port; without one, not at all. */
  @Bean
  EndpointFilter<ExposableWebEndpoint> prometheusOnlyOnManagementPort(Environment environment) {
    EndpointId prometheus = EndpointId.of("prometheus");
    return endpoint -> !endpoint.getEndpointId().equals(prometheus) || ManagementPortType.get(environment) == ManagementPortType.DIFFERENT;
  }

  /**
   * {@code ens_license_mode{mode}} (1 for the mode in effect: full, read_only or unlicensed) and
   * {@code ens_license_days_remaining} (NaN for a perpetual license or none).
   */
  @Bean
  MeterBinder licenseMeters(Licensing licensing) {
    return registry -> {
      for (LicenseState.Mode mode : LicenseState.Mode.values()) {
        Gauge.builder("ens.license.mode", licensing, l -> l.current().mode() == mode ? 1 : 0)
            .description("1 for the license mode in effect").tag("mode", mode.name().toLowerCase(Locale.ROOT)).register(registry);
      }
      Gauge.builder("ens.license.days.remaining", licensing, l -> l.current().daysLeft().map(Long::doubleValue).orElse(Double.NaN))
          .description("Days until the license (or its grace period) ends").register(registry);
    };
  }
}
