// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
package com.enssolutions.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * License state in the application's database, shared by every replica: one installation id,
 * one installed license and one revision history per product. Plain JDBC; tested on PostgreSQL,
 * MySQL 8.4 and H2.
 *
 * <p>The tables come from the application's own migrations: copy {@link Dialect#ddl()} (also in
 * the jar at {@code com/enssolutions/license/schema/<dialect>.sql}, and in licensing/README.md)
 * into a Flyway migration. The store never creates or alters tables.
 *
 * <p>Safe with any number of replicas:
 * <ul>
 *   <li>the installation id is inserted only if there is none; a replica that loses the race
 *       reads the winner's id;</li>
 *   <li>a revision is raised with {@code UPDATE ... WHERE revision < ?} (inserted only if
 *       absent), so concurrent raises end at the highest and nothing lowers it;</li>
 *   <li>the license row carries a version number, raised on every write; a {@link LicenseManager}
 *       asks for it at most every {@link #recheckInterval()} (default 5 seconds), so an upload on
 *       one replica is in force on the others within that time.</li>
 * </ul>
 *
 * <p>Each call takes a connection from the data source and gives it back before returning, in
 * auto-commit mode. Give it the application's pooled {@code DataSource} itself, not a
 * transaction-bound proxy.
 */
public final class JdbcLicenseStore implements LicenseStore {
    /** How often a {@link LicenseManager} looks for changes made on another replica, by default. */
    public static final Duration DEFAULT_RECHECK_INTERVAL = Duration.ofSeconds(5);

    private static final System.Logger LOG = System.getLogger(JdbcLicenseStore.class.getName());

    /** The databases the DDL is written for. */
    public enum Dialect {
        POSTGRESQL, MYSQL, H2;

        /** The {@code CREATE TABLE} statements for this database, as in the README. */
        public String ddl() {
            String name = "schema/" + name().toLowerCase(Locale.ROOT) + ".sql";
            try (InputStream in = JdbcLicenseStore.class.getResourceAsStream(name)) {
                if (in == null) throw new IllegalStateException("Missing resource " + name);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + name + ": " + e.getMessage(), e);
            }
        }
    }

    private final DataSource dataSource;
    private final String product;
    private final Duration recheckInterval;
    private final Path importLicenseFile;
    private final Path importStateDir;
    private volatile boolean importDone;
    private boolean importing;

    /** @param product the product id ({@code certalert}, ...): the key of this application's rows */
    public JdbcLicenseStore(DataSource dataSource, String product) {
        this(dataSource, product, DEFAULT_RECHECK_INTERVAL);
    }

    public JdbcLicenseStore(DataSource dataSource, String product, Duration recheckInterval) {
        this(dataSource, product, recheckInterval, null, null);
    }

    private JdbcLicenseStore(DataSource dataSource, String product, Duration recheckInterval,
                             Path importLicenseFile, Path importStateDir) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.product = Objects.requireNonNull(product, "product");
        if (recheckInterval.isNegative()) throw new IllegalArgumentException("negative recheck interval");
        this.recheckInterval = recheckInterval;
        this.importLicenseFile = importLicenseFile;
        this.importStateDir = importStateDir;
        this.importDone = importStateDir == null;
    }

    /**
     * This store, running {@link #importFileState(Path, Path)} on its first use rather than now:
     * by then the application's migrations have created the tables, whatever order its beans are
     * built in. If the import fails (the tables are missing, the database is down) the call that
     * triggered it fails and the next one tries again.
     */
    public JdbcLicenseStore importingFileStateOnFirstUse(Path licenseFile, Path stateDir) {
        return new JdbcLicenseStore(dataSource, product, recheckInterval,
                Objects.requireNonNull(licenseFile, "licenseFile"), Objects.requireNonNull(stateDir, "stateDir"));
    }

    private void importIfPending() throws IOException {
        if (importDone) return;
        synchronized (this) {
            if (importDone || importing) return; // importing: the import's own calls, on this thread
            importing = true;
            try {
                importFileState(importLicenseFile, importStateDir);
                importDone = true;
            } finally {
                importing = false;
            }
        }
    }

    @Override
    public Duration recheckInterval() {
        return recheckInterval;
    }

    @Override
    public String location() {
        return "the database (ens_license_file)";
    }

    /* ---- License --------------------------------------------------------------- */

    @Override
    public Optional<Object> licenseVersion() throws IOException {
        importIfPending();
        return run(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT license_version FROM ens_license_file WHERE product = ?")) {
                s.setString(1, product);
                try (ResultSet r = s.executeQuery()) {
                    return r.next() ? Optional.of((Object) r.getLong(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Optional<StoredLicense> readLicense() throws IOException {
        importIfPending();
        return run(c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "SELECT license_text, license_version FROM ens_license_file WHERE product = ?")) {
                s.setString(1, product);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    String text = r.getString(1);
                    return Optional.of(new StoredLicense(text, r.getLong(2)));
                }
            }
        });
    }

    @Override
    public void writeLicense(String text) throws IOException {
        importIfPending();
        if (text.length() > LicenseVerifier.MAX_FILE_CHARS) {
            throw new IOException("Not an ENS license: longer than " + LicenseVerifier.MAX_FILE_CHARS + " characters");
        }
        run(c -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (updateLicense(c, text) > 0) return null;
                if (insertIfAbsent(c, "INSERT INTO ens_license_file (product, license_text, license_version) VALUES (?, ?, 1)",
                        product, text)) return null;
                // Another replica inserted it between the two: update theirs.
            }
            throw new SQLException("Could not write the license row for " + product);
        });
    }

    private int updateLicense(Connection c, String text) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE ens_license_file SET license_text = ?, "
                + "license_version = license_version + 1, updated_at = CURRENT_TIMESTAMP WHERE product = ?")) {
            s.setString(1, text);
            s.setString(2, product);
            return s.executeUpdate();
        }
    }

    /* ---- Installation id ------------------------------------------------------ */

    /** The installation id in the database, if one has been made or imported. */
    public Optional<String> storedInstallationId() throws IOException {
        return run(this::selectInstallationId);
    }

    private Optional<String> selectInstallationId(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT installation_id FROM ens_license_installation WHERE product = ?")) {
            s.setString(1, product);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public String installationId(String candidate) throws IOException {
        importIfPending();
        return run(c -> {
            Optional<String> existing = selectInstallationId(c);
            if (existing.isPresent()) return existing.get();
            insertIfAbsent(c, "INSERT INTO ens_license_installation (product, installation_id) VALUES (?, ?)", product, candidate);
            // Ours, or the one another replica inserted first.
            return selectInstallationId(c).orElseThrow(() -> new SQLException("No installation id after inserting one"));
        });
    }

    /* ---- Highest revision seen, per license id -------------------------------- */

    @Override
    public int highestRevision(String licenseId) throws IOException {
        importIfPending();
        return run(c -> selectRevision(c, licenseId));
    }

    private int selectRevision(Connection c, String licenseId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT revision FROM ens_license_revision WHERE product = ? AND license_id = ?")) {
            s.setString(1, product);
            s.setString(2, licenseId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    @Override
    public int raiseRevision(String licenseId, int revision) throws IOException {
        importIfPending();
        return run(c -> {
            if (raise(c, licenseId, revision) == 0
                    && !insertIfAbsent(c, "INSERT INTO ens_license_revision (product, license_id, revision) VALUES (?, ?, ?)",
                            product, licenseId, revision)) {
                // There already, perhaps inserted by another replica just now: raise it if it is still lower.
                raise(c, licenseId, revision);
            }
            return selectRevision(c, licenseId);
        });
    }

    private int raise(Connection c, String licenseId, int revision) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE ens_license_revision SET revision = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE product = ? AND license_id = ? AND revision < ?")) {
            s.setInt(1, revision);
            s.setString(2, product);
            s.setString(3, licenseId);
            s.setInt(4, revision);
            return s.executeUpdate();
        }
    }

    /* ---- One-time import from a file state directory -------------------------- */

    /**
     * Adopts an existing installation's file state, once: if the database has no installation id
     * for this product yet, copies in the highest revisions from {@code revisions.properties},
     * the license file (unless the database already has a license), and last the installation id
     * from {@code installation-id}, so that licenses bound to that id keep working. Does nothing
     * when the database already has an installation id, or when there is nothing to import.
     *
     * <p>Safe to run on every replica at every start: the id is inserted only if absent, and
     * revisions are only ever raised. If it is interrupted, the next start finishes it.
     *
     * @return whether an installation id was imported
     */
    public boolean importFileState(Path licenseFile, Path stateDir) throws IOException {
        if (storedInstallationId().isPresent()) return false;
        FileLicenseStore files = new FileLicenseStore(licenseFile, stateDir);
        Map<String, Integer> revisions = files.revisions();
        for (Map.Entry<String, Integer> e : revisions.entrySet()) {
            raiseRevision(e.getKey(), e.getValue());
        }
        boolean license = false;
        if (licenseVersion().isEmpty()) {
            Optional<StoredLicense> text;
            try {
                text = files.readLicense();
            } catch (LicenseException e) {
                text = Optional.empty();
                LOG.log(System.Logger.Level.WARNING, "Not importing " + licenseFile + ": " + e.getMessage());
            }
            if (text.isPresent() && text.get().text().length() <= LicenseVerifier.MAX_FILE_CHARS) {
                String t = text.get().text();
                license = run(c -> insertIfAbsent(c,
                        "INSERT INTO ens_license_file (product, license_text, license_version) VALUES (?, ?, 1)", product, t));
            }
        }
        Optional<String> id = files.storedInstallationId();
        boolean imported = false;
        if (id.isPresent()) {
            String adopted = installationId(id.get());
            imported = adopted.equals(id.get());
            if (!imported) {
                LOG.log(System.Logger.Level.WARNING, "Installation id " + id.get() + " in " + stateDir
                        + " was not imported: the database already has " + adopted + " for " + product + ".");
            }
        }
        if (imported || license || !revisions.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Imported " + product + " license state into the database from "
                    + stateDir + " and " + licenseFile + ": installation id " + (imported ? id.get() : "(none)")
                    + ", " + revisions.size() + " revision(s), " + (license ? "the license file" : "no license file") + ".");
        }
        return imported;
    }

    /* ---- Plumbing --------------------------------------------------------------- */

    private interface Work<T> {
        T apply(Connection c) throws SQLException;
    }

    private <T> T run(Work<T> work) throws IOException {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            if (!autoCommit) c.setAutoCommit(true);
            try {
                return work.apply(c);
            } finally {
                if (!autoCommit) c.setAutoCommit(false);
            }
        } catch (SQLException e) {
            throw new IOException("License state in the database: " + e.getMessage(), e);
        }
    }

    /** Runs an INSERT; false if a row with that key is already there. */
    private static boolean insertIfAbsent(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (isDuplicateKey(e)) return false;
            throw e;
        }
    }

    /** SQLState class 23, integrity constraint violation: what all three databases say for a duplicate key. */
    static boolean isDuplicateKey(SQLException e) {
        for (SQLException x = e; x != null; x = x.getNextException()) {
            if (x instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
            String state = x.getSQLState();
            if (state != null && state.startsWith("23")) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "JdbcLicenseStore[" + product + "]";
    }
}
