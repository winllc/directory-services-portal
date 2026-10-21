package com.winllc.dsp.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import com.winllc.dsp.SharedDatabaseSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * License state in the shared database (PostgreSQL): the first use imports the installation id from
 * the state directory, replicas share one id, and an upgraded database with nothing to import
 * refuses to make a new id.
 */
@Testcontainers(disabledWithoutDocker = true)
class LicenseDatabaseTest {
  static final String PRODUCT = Licensing.PRODUCT;
  static final LicenseVerifier VERIFIER = LicenseVerifier.trusting("MCowBQYDK2VwAyEA6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw=");

  @TempDir Path dir;

  static HikariDataSource database(String target) {
    HikariDataSource ds = new HikariDataSource();
    ds.setJdbcUrl(SharedDatabaseSupport.newDatabase());
    ds.setUsername(SharedDatabaseSupport.postgres().getUsername());
    ds.setPassword(SharedDatabaseSupport.postgres().getPassword());
    var config = Flyway.configure().dataSource(ds);
    if (target != null) config.target(MigrationVersion.fromVersion(target));
    config.load().migrate();
    return ds;
  }

  static LicenseManager shared(HikariDataSource ds, Path licenseFile, Path stateDir) {
    return new LicenseManager(PRODUCT, VERIFIER, new JdbcLicenseStore(ds, PRODUCT).importingFileStateOnFirstUse(licenseFile, stateDir),
        Optional.empty(), Clock.systemUTC(), Optional.empty());
  }

  @Test
  void importsTheInstallationIdOnFirstUseAndReplicasShareIt() throws Exception {
    Path state = dir.resolve("state");
    Path file = dir.resolve("license.lic");
    String fileId = new LicenseManager(PRODUCT, VERIFIER, file, state, Optional.empty(), Clock.systemUTC()).installationId();
    try (HikariDataSource ds = database(null)) {
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", state.toString(), "ENS_LICENSE_FILE", file.toString()));
      assertThat(shared(ds, file, state).installationId()).isEqualTo(fileId);
      // A second replica, without the volume, gets the same id from the database.
      Path none = dir.resolve("none");
      assertThat(shared(ds, none.resolve("license.lic"), none).installationId()).isEqualTo(fileId);
    }
  }

  @Test
  void twoManagersOnANewDatabaseMakeOneId() {
    Path none = dir.resolve("none");
    try (HikariDataSource ds = database(null)) {
      // A new database is not an upgrade: no refusal, one new id for everybody.
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString()));
      String a = shared(ds, none.resolve("a.lic"), none.resolve("a")).installationId();
      String b = shared(ds, none.resolve("b.lic"), none.resolve("b")).installationId();
      assertThat(a).isEqualTo(b).startsWith("DP-");
    }
  }

  @Test
  void anUpgradedDatabaseWithNothingToImportRefusesANewId() throws Exception {
    Path none = dir.resolve("none");
    try (HikariDataSource ds = database("1")) {
      // In use before the license state moved in (a configuration stored), then upgraded.
      new JdbcTemplate(ds).update("INSERT INTO dsp_document (name, version, content) VALUES ('portal-config', 1, '{}')");
      Flyway.configure().dataSource(ds).load().migrate();

      assertThatThrownBy(() -> LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString())))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start").hasMessageContaining(LicenseStateGuard.OVERRIDE);
      // With the volume mounted the id is imported; the override accepts a new id.
      Path state = dir.resolve("state");
      String fileId = new LicenseManager(PRODUCT, VERIFIER, dir.resolve("license.lic"), state, Optional.empty(), Clock.systemUTC()).installationId();
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", state.toString()));
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString(), LicenseStateGuard.OVERRIDE, "true"));
      // Once the database holds the id, later starts need no volume.
      assertThat(shared(ds, dir.resolve("license.lic"), state).installationId()).isEqualTo(fileId);
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString()));
    }
  }

  static final String EXPECT = LicenseStateGuard.EXPECT;

  @Test
  void aFreshDatabaseBehindPodsWithoutTheVolumeNeverMakesAnId() throws Exception {
    Path none = dir.resolve("none");
    // An existing install going straight to the database and three replicas (no volume in any pod),
    // or a rebuilt PostgreSQL behind one: V1 and V2 on an empty database, so no "upgraded" marker.
    try (HikariDataSource ds = database(null)) {
      assertThatThrownBy(() -> LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString(), EXPECT, "true")))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("one replica first");
      // A one-replica start with the volume imports the id; then the pods without it start and share it.
      Path state = dir.resolve("state");
      String fileId = new LicenseManager(PRODUCT, VERIFIER, dir.resolve("license.lic"), state, Optional.empty(), Clock.systemUTC()).installationId();
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", state.toString()));
      assertThat(shared(ds, dir.resolve("license.lic"), state).installationId()).isEqualTo(fileId);
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString(), EXPECT, "true"));
      assertThat(shared(ds, none.resolve("license.lic"), none).installationId()).isEqualTo(fileId);
    }
  }

  @Test
  void aPodWithoutTheIdFileNeverWinsTheImportRace() throws Exception {
    Path none = dir.resolve("none");
    Path state = dir.resolve("state");
    String fileId = new LicenseManager(PRODUCT, VERIFIER, dir.resolve("license.lic"), state, Optional.empty(), Clock.systemUTC()).installationId();
    try (HikariDataSource ds = database(null)) {
      // Both start at once: the one without the id file is refused (and restarts), instead of
      // inserting a random id before the other imports the real one.
      assertThatThrownBy(() -> LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString(), EXPECT, "true")))
          .isInstanceOf(IllegalStateException.class);
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", state.toString()));
      assertThat(shared(ds, dir.resolve("license.lic"), state).installationId()).isEqualTo(fileId);
      LicenseStateGuard.check(ds, PRODUCT, Map.of("ENS_LICENSE_STATE_DIR", none.toString(), EXPECT, "true"));
    }
  }

  @Test
  void theOverrideIsHonouredOnlyWhileTheDatabaseHoldsNoLicenseState() {
    Path none = dir.resolve("none");
    try (HikariDataSource ds = database(null)) {
      Map<String, String> env = Map.of("ENS_LICENSE_STATE_DIR", none.toString(), EXPECT, "true", LicenseStateGuard.OVERRIDE, "true");
      // Partial loss: revisions remain but the id is gone. The override must not mint a new id.
      new JdbcTemplate(ds).update("INSERT INTO ens_license_revision (product, license_id, revision) VALUES (?, 'L-1', 2)", PRODUCT);
      assertThatThrownBy(() -> LicenseStateGuard.check(ds, PRODUCT, env))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("not honoured");
      new JdbcTemplate(ds).update("DELETE FROM ens_license_revision");
      LicenseStateGuard.check(ds, PRODUCT, env);
    }
  }
}
