package com.winllc.dsp.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the ENS license into the portal.
 *
 * <p>The license file and the installation's state directory come from the environment:
 * {@code ENS_LICENSE_FILE} (default {@code /etc/ens/directory-portal.lic}) and
 * {@code ENS_LICENSE_STATE_DIR} (default {@code /var/lib/ens/directory-portal}; the installation
 * id and the highest revisions seen live there). The container image sets both to
 * {@code /var/lib/ens/directory-portal}, a writable volume, so the license screen can install
 * uploads. The release date (for perpetual licenses' maintenance) is the build time from
 * Spring Boot's build-info.
 *
 * <p>Every {@code /api} request goes through {@link LicenseInterceptor}, except sign-in/out
 * ({@code /api/auth/**}), {@code /api/info}, {@code /api/health}, the license endpoints and the
 * audit trail (the admin audit page's API and the SIEM pull API {@code /api/audit/events}), which
 * must stay available while the portal is unlicensed or read-only.
 */
@Configuration
public class LicensingConfiguration implements WebMvcConfigurer {
  private static final Logger log = LoggerFactory.getLogger(LicensingConfiguration.class);

  /** Reachable whatever the license says. */
  static final String[] OPEN_PATHS = {"/api/auth/**", "/api/info", "/api/health", "/api/license", "/api/admin/license",
      "/api/admin/audit", "/api/admin/audit/**", "/api/audit/**",
      "/api/v1/openapi", "/api/v1/openapi.yaml"};

  private final ObjectProvider<Licensing> licensing;
  private final ObjectProvider<BuildProperties> build;

  public LicensingConfiguration(ObjectProvider<Licensing> licensing, ObjectProvider<BuildProperties> build) {
    this.licensing = licensing;
    this.build = build;
  }

  /** When this build was made (build-info); empty when run without it, e.g. from an IDE. */
  private Optional<Instant> releaseDate() {
    return Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getTime);
  }

  /**
   * Alone: the license file and state directory, as before. With the shared database
   * (DSP_DATABASE_URL): the license state in its tables, shared by every replica; the first use
   * imports the installation id, the revisions seen and the license from ENS_LICENSE_STATE_DIR and
   * ENS_LICENSE_FILE, so an existing installation keeps its id ({@link LicenseStateGuard} refuses
   * to start where it would get a new one instead).
   */
  @Bean
  LicenseManager licenseManager(ObjectProvider<DataSource> dataSource) {
    Optional<Instant> released = releaseDate();
    LicenseVerifier verifier = LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS);
    DataSource shared = dataSource.getIfAvailable();
    if (shared == null) return LicenseManager.fromEnvironment(Licensing.PRODUCT, verifier, released);
    LicenseStateGuard.check(shared, Licensing.PRODUCT, System.getenv());
    // The pool itself, not a transaction-aware proxy: the store manages its own transactions.
    return LicenseManager.fromEnvironment(Licensing.PRODUCT, verifier, released, shared);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new LicenseInterceptor(licensing))
        .addPathPatterns("/api/**")
        .excludePathPatterns(OPEN_PATHS);
  }

  @EventListener(ApplicationReadyEvent.class)
  void logLicense() {
    LicenseManager manager = licensing.getObject().manager();
    try {
      LicenseCheck check = manager.current();
      log.info("License: {} ({}, {}). Installation id {}; license file {}; release date {}.", check.message(), check.state(),
          check.mode(), manager.installationId(), manager.licenseLocation(), releaseDate().map(Object::toString).orElse("unknown"));
    } catch (IllegalStateException e) {
      log.error("License: {}. Make ENS_LICENSE_STATE_DIR a directory this user can write (see README, Licensing).", e.getMessage());
    }
  }
}
