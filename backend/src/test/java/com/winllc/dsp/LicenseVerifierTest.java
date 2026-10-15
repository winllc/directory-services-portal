package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.dsp.license.License;
import com.winllc.dsp.license.LicenseService;
import com.winllc.dsp.license.LicenseVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseVerifierTest {
  static final String PRODUCT = "directory-services-portal";
  final LicenseVerifier verifier = LicenseVerifier.forProduct(PRODUCT);
  @TempDir Path dir;

  @Test
  void acceptsTheTestSuiteLicense() throws IOException {
    License license = verifier.verify(Files.readString(Path.of("src/test/resources/license/test.lic")));
    assertThat(license.product()).isEqualTo(PRODUCT);
    assertThat(license.expires()).isNull();
    assertThat(license.covers("analysis")).isTrue();
    assertThat(license.covers("metrics")).isTrue();
  }

  @Test
  void acceptsAFeatureLimitedLicense() {
    License license = verifier.verify(TestLicenses.sign("metrics, schema-transfer", "2030-01-31"));
    assertThat(license.features()).containsExactlyInAnyOrder("metrics", "schema-transfer");
    assertThat(license.covers("analysis")).isFalse();
    assertThat(license.expires()).isEqualTo(LocalDate.of(2030, 1, 31));
  }

  @Test
  void rejectsATamperedLicense() {
    String tampered = TestLicenses.sign("metrics", "2030-01-31").replace("features=metrics", "features=*");
    assertThatThrownBy(() -> verifier.verify(tampered))
        .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
        .hasMessageContaining("signature does not match");
  }

  @Test
  void rejectsALicenseForAnotherProduct() {
    String other = TestLicenses.sign("*", "never", Map.of("product", "cert-alert"));
    assertThatThrownBy(() -> verifier.verify(other))
        .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
        .hasMessageContaining("for cert-alert");
  }

  @Test
  void rejectsALicenseWithoutSignature() {
    String unsigned = TestLicenses.sign("*", "never").replaceAll("(?m)^signature=.*$", "");
    assertThatThrownBy(() -> verifier.verify(unsigned))
        .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
        .hasMessageContaining("no signature");
  }

  @Test
  void anExpiredLicenseFallsBackToCoreFeatures() throws IOException {
    Path file = dir.resolve("license.lic");
    Files.writeString(file, TestLicenses.sign("*", "2026-06-30"));

    LicenseService onLastDay = service(file, "2026-06-30T23:59:00Z");
    assertThat(onLastDay.status().state()).isEqualTo(LicenseService.State.LICENSED);
    assertThat(onLastDay.isEnabled("metrics")).isTrue();

    LicenseService dayAfter = service(file, "2026-07-01T00:00:00Z");
    LicenseService.Status status = dayAfter.status();
    assertThat(status.state()).isEqualTo(LicenseService.State.EXPIRED);
    assertThat(status.features()).isEmpty();
    assertThat(status.problem()).contains("2026-06-30");
    assertThat(dayAfter.isEnabled("metrics")).isFalse();
  }

  @Test
  void aMissingFileMeansCoreFeaturesOnly() {
    LicenseService service = service(dir.resolve("absent.lic"), "2026-10-15T00:00:00Z");
    assertThat(service.status().state()).isEqualTo(LicenseService.State.MISSING);
    assertThat(service.status().problem()).isNull();
    assertThat(service.isEnabled("analysis")).isFalse();
  }

  @Test
  void anInvalidFileSaysWhy() throws IOException {
    Path file = dir.resolve("license.lic");
    Files.writeString(file, "not a license");
    LicenseService service = service(file, "2026-10-15T00:00:00Z");
    assertThat(service.status().state()).isEqualTo(LicenseService.State.INVALID);
    assertThat(service.status().problem()).contains("no signature");
    assertThat(service.isEnabled("analysis")).isFalse();
  }

  private LicenseService service(Path file, String now) {
    return new LicenseService(verifier, file, PRODUCT, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
  }
}
