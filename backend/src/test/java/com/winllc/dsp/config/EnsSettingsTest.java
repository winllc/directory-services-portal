package com.winllc.dsp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The shared ENS_* settings (platform baseline v1) bind under exactly those names, with the
 * documented defaults, and the portal's older names keep working as aliases.
 */
class EnsSettingsTest {

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({EnsProperties.class, PortalProperties.class})
  static class Settings {}

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(Settings.class);

  @Test
  void hasTheBaselineDefaults() {
    runner.run(ctx -> {
      EnsProperties ens = ctx.getBean(EnsProperties.class);
      assertThat(ens.getCsp().isReportOnly()).isFalse();
      assertThat(ens.getCookie().isSecure()).isFalse();
      assertThat(ens.getConsentBanner().isEnabled()).isFalse();
      assertThat(ens.getConsentBanner().effectiveTitle()).isEqualTo("U.S. Government (USG) Information System (IS) Notice and Consent");
      assertThat(ens.getConsentBanner().effectiveText())
          .startsWith("You are accessing a U.S. Government (USG) Information System (IS) that is provided for USG-authorized use only.")
          .contains("\n\n- At any time, the USG may inspect and seize data stored on this IS.\n\n")
          .endsWith("Such communications and work product are private and confidential. See User Agreement for details.");
      assertThat(ens.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
      assertThat(ens.getSession().idleTimeoutFor(true)).isEqualTo(Duration.ofMinutes(30));
      assertThat(ens.getSession().getAbsoluteTimeout()).isEqualTo(Duration.ofHours(12));
      assertThat(ens.getSession().getMaxPerUser()).isZero();
      // Unset: ocsp-then-crl, soft-fail.
      assertThat(ens.getX509().configuredMode()).isEmpty();
      assertThat(ens.getX509().isModeDefaulted()).isTrue();
      assertThat(ens.getX509().configuredFail()).isEmpty();
      assertThat(ens.getX509().failOpen()).isTrue();
      assertThat(ens.getX509().problems()).isEmpty();
      assertThat(ens.getX509().getRevocationCache()).isEqualTo(Duration.ofMinutes(10));
      assertThat(ens.getX509().allowedIssuerList()).isEmpty();
      assertThat(ctx.getEnvironment().getProperty("server.servlet.session.timeout")).isEqualTo("30m");
      assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("false");
    });
  }

  /** An unset fail mode is open only while the mode is unset too; a chosen mode keeps closed, and both stay settable. */
  @Test
  void theFailModeFollowsWhetherAModeWasChosen() {
    EnsProperties.X509 x509 = new EnsProperties.X509();
    assertThat(x509.failOpen()).isTrue();
    x509.setRevocationFail("closed");
    assertThat(x509.failOpen()).isFalse();
    x509.setRevocationFail("open");
    assertThat(x509.failOpen()).isTrue();

    x509.setRevocationFail("");
    for (String mode : EnsProperties.X509.MODES) {
      x509.setRevocation(mode);
      assertThat(x509.isModeDefaulted()).isFalse();
      assertThat(x509.failOpen()).as(mode).isFalse();
    }
    x509.setRevocation("ocsp");
    x509.setRevocationFail(" Open ");
    assertThat(x509.failOpen()).isTrue();
    x509.setRevocation("  ");
    x509.setRevocationFail(null);
    assertThat(x509.isModeDefaulted()).isTrue();
    assertThat(x509.failOpen()).isTrue();
    assertThat(x509.problems()).isEmpty();
  }

  @Test
  void bindsTheEnsVariables() {
    runner.withPropertyValues("ENS_CSP_REPORT_ONLY=true", "ENS_COOKIE_SECURE=true", "ENS_CONSENT_BANNER_ENABLED=true",
        "ENS_CONSENT_BANNER_TITLE=Notice", "ENS_CONSENT_BANNER_TEXT=First.\n\nSecond.", "ENS_SESSION_IDLE_TIMEOUT=20m",
        "ENS_SESSION_ADMIN_IDLE_TIMEOUT=15m", "ENS_SESSION_ABSOLUTE_TIMEOUT=8h", "ENS_SESSION_MAX_PER_USER=3",
        "ENS_X509_REVOCATION=ocsp-then-crl", "ENS_X509_OCSP_RESPONDER_URL=http://ocsp.local:8080", "ENS_X509_REVOCATION_FAIL=open",
        "ENS_X509_REVOCATION_CACHE=5m", "ENS_X509_ALLOWED_ISSUERS=CN=DOD ID CA-70,OU=PKI,OU=DoD,O=U.S. Government,C=US;CN=Other CA")
        .run(ctx -> {
          EnsProperties ens = ctx.getBean(EnsProperties.class);
          assertThat(ens.getCsp().isReportOnly()).isTrue();
          assertThat(ens.getCookie().isSecure()).isTrue();
          assertThat(ens.getConsentBanner().isEnabled()).isTrue();
          assertThat(ens.getConsentBanner().effectiveTitle()).isEqualTo("Notice");
          assertThat(ens.getConsentBanner().effectiveText()).isEqualTo("First.\n\nSecond.");
          assertThat(ens.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
          assertThat(ens.getSession().idleTimeoutFor(true)).isEqualTo(Duration.ofMinutes(15));
          assertThat(ens.getSession().idleTimeoutFor(false)).isEqualTo(Duration.ofMinutes(20));
          assertThat(ens.getSession().getAbsoluteTimeout()).isEqualTo(Duration.ofHours(8));
          assertThat(ens.getSession().getMaxPerUser()).isEqualTo(3);
          assertThat(ens.getX509().configuredMode()).isEqualTo("ocsp-then-crl");
          assertThat(ens.getX509().getOcspResponderUrl()).isEqualTo("http://ocsp.local:8080");
          assertThat(ens.getX509().failOpen()).isTrue();
          assertThat(ens.getX509().getRevocationCache()).isEqualTo(Duration.ofMinutes(5));
          assertThat(ens.getX509().allowedIssuerList()).extracting(Object::toString)
              .containsExactly("CN=DOD ID CA-70, OU=PKI, OU=DoD, O=U.S. Government, C=US", "CN=Other CA");
          assertThat(ens.getX509().problems()).isEmpty();
          assertThat(ctx.getEnvironment().getProperty("server.servlet.session.timeout")).isEqualTo("20m");
          assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        });
  }

  @Test
  void keepsThePortalsOlderNamesAsAliases() {
    runner.withPropertyValues("SESSION_TTL_MINUTES=480", "COOKIE_SECURE=true").run(ctx -> {
      EnsProperties ens = ctx.getBean(EnsProperties.class);
      assertThat(ens.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(480));
      assertThat(ens.getCookie().isSecure()).isTrue();
      assertThat(ctx.getEnvironment().getProperty("server.servlet.session.timeout")).isEqualTo("480m");
      assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
    });
    // The ENS_ name wins over the older one.
    runner.withPropertyValues("SESSION_TTL_MINUTES=480", "ENS_SESSION_IDLE_TIMEOUT=30m", "COOKIE_SECURE=false", "ENS_COOKIE_SECURE=true")
        .run(ctx -> {
          assertThat(ctx.getBean(EnsProperties.class).getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(30));
          assertThat(ctx.getEnvironment().getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        });
  }

  @Test
  void bindsFromRealEnvironmentVariablesByRelaxedBindingAlone() {
    // Without application.yml's placeholders: Spring's relaxed binding maps the names by itself.
    StandardEnvironment env = new StandardEnvironment();
    MutablePropertySources sources = env.getPropertySources();
    sources.addFirst(new SystemEnvironmentPropertySource("test-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
        "ENS_CONSENT_BANNER_ENABLED", "true", "ENS_SESSION_IDLE_TIMEOUT", "10m", "ENS_SESSION_MAX_PER_USER", "2",
        "ENS_X509_REVOCATION_FAIL", "open", "ENS_CSP_REPORT_ONLY", "true")));
    EnsProperties ens = new Binder(ConfigurationPropertySources.from(sources)).bind("ens", EnsProperties.class).get();
    assertThat(ens.getConsentBanner().isEnabled()).isTrue();
    assertThat(ens.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(ens.getSession().getMaxPerUser()).isEqualTo(2);
    assertThat(ens.getX509().failOpen()).isTrue();
    assertThat(ens.getCsp().isReportOnly()).isTrue();
  }
}
