package com.winllc.dsp;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The test suite's license: a {@link LicenseManager} that trusts the published TEST key (never the
 * production one's private half, which tests don't have), with its license file and state
 * directory in a temporary directory. Tests switch licenses by writing that file.
 */
final class TestLicensing {
  static final Path DIR;
  static final Path FILE;
  static final Path STATE;
  private static final AtomicLong MTIME = new AtomicLong(1_000_000_000_000L);

  static {
    try {
      DIR = Files.createTempDirectory("dsp-license");
      FILE = DIR.resolve("license.lic");
      STATE = DIR.resolve("state");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private TestLicensing() {}

  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    @Primary
    LicenseManager testLicenseManager() {
      return new LicenseManager("directory-portal", LicenseVerifier.trusting(TestLicenses.TEST_PUBLIC_KEY), FILE, STATE,
          Optional.empty(), Clock.systemUTC());
    }
  }

  /** Puts this license file in place (the manager notices the new modification time). */
  static void use(String licenseText) {
    try {
      Files.writeString(FILE, licenseText);
      Files.setLastModifiedTime(FILE, FileTime.fromMillis(MTIME.addAndGet(1000)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void remove() {
    try {
      Files.deleteIfExists(FILE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
