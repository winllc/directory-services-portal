package com.winllc.dsp.license;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to start where the shared database would otherwise give this installation a new
 * installation id, which breaks every license bound to the old one: the database holds no id yet,
 * it was already in use before the license state moved into it (an upgrade: the
 * {@value #UPGRADED_MARKER} marker written by migration V2), and there is no state directory or
 * license file to import the id from (the license volume isn't mounted). The same with
 * {@value #EXPECT}=true (no license volume in this pod), whatever the database's history. Set
 * {@value #OVERRIDE}=true to accept a new id (e.g. a fresh install that was never licensed): it is
 * honoured only while the database holds no license state at all, and logged at ERROR every start.
 */
public final class LicenseStateGuard {
  private static final Logger log = LoggerFactory.getLogger(LicenseStateGuard.class);
  public static final String UPGRADED_MARKER = "license/database-upgraded";
  public static final String OVERRIDE = "ENS_LICENSE_ALLOW_NEW_INSTALLATION_ID";
  /**
   * Set (by the Helm chart) where the license volume is replaced by an empty directory: this
   * instance must find the installation id in the database, never make one.
   */
  public static final String EXPECT = "ENS_LICENSE_EXPECT_INSTALLATION_ID";
  /** FileLicenseStore's installation id file in ENS_LICENSE_STATE_DIR. */
  static final String INSTALLATION_ID_FILE = "installation-id";

  private LicenseStateGuard() {}

  public static void check(DataSource db, String product, Map<String, String> env) {
    Path stateDir = Path.of(env.getOrDefault("ENS_LICENSE_STATE_DIR", "/var/lib/ens/" + product));
    Path licenseFile = Path.of(env.getOrDefault("ENS_LICENSE_FILE", "/etc/ens/" + product + ".lic"));
    boolean allow = "true".equalsIgnoreCase(env.getOrDefault(OVERRIDE, "").trim());
    boolean expect = "true".equalsIgnoreCase(env.getOrDefault(EXPECT, "").trim());
    if (allow) {
      log.error("{}=true is set: if the shared database ever has no installation id, a NEW one would be made and licenses bound"
          + " to the old one would stop working. Remove it once this installation has its id.", OVERRIDE);
    }
    boolean hasId;
    boolean upgraded;
    boolean anyState;
    try (Connection c = db.getConnection()) {
      hasId = exists(c, "SELECT 1 FROM ens_license_installation WHERE product = ?", product);
      upgraded = exists(c, "SELECT 1 FROM dsp_document WHERE name = ?", UPGRADED_MARKER);
      anyState = exists(c, "SELECT 1 FROM ens_license_installation UNION ALL SELECT 1 FROM ens_license_file"
          + " UNION ALL SELECT 1 FROM ens_license_revision LIMIT 1", null);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not read the license state in the shared database: " + e.getMessage(), e);
    }
    if (hasId) return;
    // Only the id file counts: a license imported without its id would be bound to an id this installation no longer has.
    if (Files.isRegularFile(stateDir.resolve(INSTALLATION_ID_FILE))) {
      log.info("License state: importing the installation id and license from {} and {} into the shared database", stateDir, licenseFile);
      return;
    }
    // A new id is only made for a new installation: not for a database that was in use before (an
    // upgrade), and not where the license volume was replaced by an empty one (EXPECT, set by the
    // chart): there the id must come from the volume, at a start that mounts it.
    if (!upgraded && !expect) return;
    if (allow && !anyState) {
      log.error("License state: no installation id to import ({} has none) and {}=true: a NEW installation id is made."
          + " Licenses bound to a previous id stop working.", stateDir, OVERRIDE);
      return;
    }
    throw new IllegalStateException("Refusing to start: the shared database has no installation id yet and there is none to import"
        + " (no " + stateDir.resolve(INSTALLATION_ID_FILE) + "). "
        + (expect ? "This pod runs without the license volume (" + EXPECT + "=true), so it must not make the id: start the portal"
            + " with one replica first (it mounts the license volume and imports the id), then scale out."
            : "This portal used the database before the license state moved into it, so starting would make a NEW installation id"
            + " and licenses bound to the old one would stop working. Mount the license volume (ENS_LICENSE_STATE_DIR) for this"
            + " start so its id is imported.")
        + (anyState ? " (" + OVERRIDE + " is not honoured: the database already holds license state.)"
            : " If this installation never had a license, set " + OVERRIDE + "=true once, then remove it."));
  }

  private static boolean exists(Connection c, String sql, String arg) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      if (arg != null) s.setString(1, arg);
      try (ResultSet r = s.executeQuery()) {
        return r.next();
      }
    }
  }
}
