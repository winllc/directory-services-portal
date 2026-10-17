package com.winllc.dsp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The startup checks, with the settings given as the environment variables of application.yml:
 * header mode (a proxy forwards the certificate) refuses to start unless the proxy is
 * authenticated by its secret and the API validates certificates against trusted CAs.
 */
@ExtendWith(OutputCaptureExtension.class)
class StartupChecksTest {
  static final String SECRET = "8d0c6b2e4f1a3c5e7b9d0f2a4c6e8b1d3f5a7c9e0b2d4f6a8c1e3b5d7f9a0c2e";
  static Path caFile;
  static Path secretFile;

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties({PortalProperties.class, EnsProperties.class})
  @Import(StartupChecks.class)
  static class Checks {}

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(Checks.class);

  @BeforeAll
  static void files() throws Exception {
    caFile = Files.createTempFile("ca", ".pem");
    Files.writeString(caFile, "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n");
    secretFile = Files.createTempFile("proxy-secret", "");
    Files.writeString(secretFile, SECRET + "\n");
  }

  private ApplicationContextRunner headerMode(String... more) {
    return runner.withPropertyValues("X509_ENABLED=true", "X509_SOURCE=header").withPropertyValues(more);
  }

  @Test
  void refusesHeaderModeWithoutProxySecretOrTrustedCas() {
    headerMode().run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause()
          .hasMessageContaining("Refusing to start")
          .hasMessageContaining("X509_PROXY_SECRET_FILE (or X509_PROXY_SECRET)")
          .hasMessageContaining("X509_TRUSTED_CA_FILE");
    });
  }

  @Test
  void refusesHeaderModeWithoutProxySecret() {
    headerMode("X509_TRUSTED_CA_FILE=" + caFile).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause()
          .hasMessageContaining("set X509_PROXY_SECRET_FILE (or X509_PROXY_SECRET)")
          .hasMessageNotContaining("set X509_TRUSTED_CA_FILE");
    });
    headerMode("X509_TRUSTED_CA_FILE=" + caFile, "X509_PROXY_SECRET=too-short").run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("must be at least 32 characters");
    });
    headerMode("X509_TRUSTED_CA_FILE=" + caFile, "X509_PROXY_SECRET_FILE=" + caFile.resolveSibling("missing-secret")).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("Cannot read X509_PROXY_SECRET_FILE");
    });
  }

  @Test
  void refusesHeaderModeWithoutTrustedCas() {
    headerMode("X509_PROXY_SECRET=" + SECRET).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause()
          .hasMessageContaining("set X509_TRUSTED_CA_FILE")
          .hasMessageNotContaining("X509_PROXY_SECRET_FILE (or");
    });
    headerMode("X509_PROXY_SECRET=" + SECRET, "X509_TRUSTED_CA_FILE=" + caFile.resolveSibling("missing-ca.pem")).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("cannot be read");
    });
  }

  @Test
  void startsWithTheProxySecretAndTrustedCas() {
    headerMode("X509_PROXY_SECRET_FILE=" + secretFile, "X509_TRUSTED_CA_FILE=" + caFile).run(ctx -> {
      assertThat(ctx).hasNotFailed();
      assertThat(ctx.getBean(PortalProperties.class).getX509().resolvedProxySecret()).isEqualTo(SECRET);
    });
    headerMode("X509_PROXY_SECRET=" + SECRET, "X509_TRUSTED_CA_FILE=" + caFile).run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void refusesAConsentBannerFileItCannotRead() {
    runner.withPropertyValues("ENS_CONSENT_BANNER_ENABLED=true", "ENS_CONSENT_BANNER_FILE=" + caFile.resolveSibling("missing-banner.txt"))
        .run(ctx -> {
          assertThat(ctx).hasFailed();
          assertThat(ctx.getStartupFailure()).hasStackTraceContaining("Cannot read ENS_CONSENT_BANNER_FILE");
        });
    runner.withPropertyValues("ENS_CONSENT_BANNER_ENABLED=true", "ENS_CONSENT_BANNER_FILE=" + secretFile).run(ctx -> assertThat(ctx).hasNotFailed());
    runner.withPropertyValues("ENS_CONSENT_BANNER_ENABLED=true").run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void refusesInvalidRevocationSettings() {
    runner.withPropertyValues("ENS_X509_REVOCATION=sometimes").run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("ENS_X509_REVOCATION must be one of off, ocsp, crl, ocsp-then-crl");
    });
    runner.withPropertyValues("ENS_X509_REVOCATION_FAIL=maybe", "ENS_X509_OCSP_RESPONDER_URL=ldap://x", "ENS_X509_CRL_DIR=/no/such/dir")
        .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
            .hasMessageContaining("ENS_X509_REVOCATION_FAIL must be closed or open")
            .hasMessageContaining("ENS_X509_OCSP_RESPONDER_URL must be an http:// or https:// URL")
            .hasMessageContaining("ENS_X509_CRL_DIR /no/such/dir is not a directory"));
    runner.withPropertyValues("ENS_X509_ALLOWED_ISSUERS=not a dn").run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void needsTrustedCasToCheckRevocation() {
    runner.withPropertyValues("X509_ENABLED=true", "ENS_X509_REVOCATION=ocsp").run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("needs X509_TRUSTED_CA_FILE");
    });
    runner.withPropertyValues("X509_ENABLED=true", "X509_CHECK_REVOCATION=true").run(ctx -> assertThat(ctx).hasFailed());
    runner.withPropertyValues("X509_ENABLED=true", "ENS_X509_REVOCATION=ocsp-then-crl", "X509_TRUSTED_CA_FILE=" + caFile)
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void warnsWhenCertificateSignInChecksNoRevocation(CapturedOutput output) {
    runner.withPropertyValues("X509_ENABLED=true").run(ctx -> assertThat(ctx).hasNotFailed());
    assertThat(output).contains("X.509 sign-in is enabled without revocation checking", "DISA STIG requires");
  }

  @Test
  void readsTheBindPasswordFromItsFile() throws Exception {
    java.nio.file.Path file = java.nio.file.Files.createTempFile("bind-password", "");
    java.nio.file.Files.writeString(file, " s3cret pass \n");
    PortalProperties.Ldap ldap = new PortalProperties().getLdap();
    ldap.setBindPassword("from-env");
    assertThat(ldap.resolvedBindPassword()).isEqualTo("from-env");
    ldap.setBindPasswordFile(file.toString());
    // Only the trailing new line goes: the password itself may have spaces.
    assertThat(ldap.resolvedBindPassword()).isEqualTo(" s3cret pass ");
    java.nio.file.Files.writeString(file, "");
    assertThat(ldap.resolvedBindPassword()).isEqualTo("from-env");

    runner.withPropertyValues("DIRECTORY_MODE=ldap", "LDAP_BIND_PASSWORD_FILE=" + file.resolveSibling("missing-password")).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).hasStackTraceContaining("Cannot read LDAP_BIND_PASSWORD_FILE");
    });
    runner.withPropertyValues("DIRECTORY_MODE=ldap", "LDAP_BIND_PASSWORD_FILE=" + file).run(ctx -> assertThat(ctx).hasNotFailed());
  }

  @Test
  void warnsAboutTrustingAnyLdapCertificate(CapturedOutput output) {
    runner.withPropertyValues("DIRECTORY_MODE=ldap", "LDAP_TLS_REJECT_UNAUTHORIZED=false").run(ctx -> assertThat(ctx).hasNotFailed());
    assertThat(output).contains("LDAP_TLS_REJECT_UNAUTHORIZED is false", "not FIPS 140-3 or DISA STIG appropriate");
  }

  @Test
  void leavesOtherModesAlone() {
    // Certificate sign-in off, or the API terminating mutual TLS itself (Tomcat checks the chain).
    runner.withPropertyValues("X509_ENABLED=false", "X509_SOURCE=header").run(ctx -> assertThat(ctx).hasNotFailed());
    runner.withPropertyValues("X509_ENABLED=true", "X509_SOURCE=servlet").run(ctx -> assertThat(ctx).hasNotFailed());
    runner.run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
