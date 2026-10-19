// Ported from products-page licensing/java 1.2.0 (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Licenses signed with the published test key, and small JDBC helpers, for the store tests. */
final class TestLicenses {
    private TestLicenses() {}

    static LicenseVerifier verifier() throws Exception {
        if (LicenseManagerEnvironmentTest.verifier == null) LicenseManagerEnvironmentTest.load();
        return LicenseManagerEnvironmentTest.verifier;
    }

    /** A certalert subscription license with this id and revision, optionally bound to an installation. */
    static String license(String id, int revision, String installationId) throws Exception {
        verifier();
        if (installationId == null) return LicenseManagerEnvironmentTest.license(id, revision, "certalert");
        String saved = LicenseManagerEnvironmentTest.payload;
        try {
            LicenseManagerEnvironmentTest.payload = saved.replace("\"installationId\":null", "\"installationId\":\"" + installationId + "\"");
            return LicenseManagerEnvironmentTest.license(id, revision, "certalert");
        } finally {
            LicenseManagerEnvironmentTest.payload = saved;
        }
    }

    /** Runs a schema script from the jar: statements separated by semicolons, "--" comment lines. */
    static void runDdl(DataSource ds, String ddl) throws SQLException {
        StringBuilder sql = new StringBuilder();
        for (String line : ddl.split("\n")) {
            if (!line.trim().startsWith("--")) sql.append(line).append('\n');
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql.toString().split(";")) {
                if (!statement.isBlank()) s.execute(statement);
            }
        }
    }

    static void clear(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM ens_license_installation");
            s.execute("DELETE FROM ens_license_file");
            s.execute("DELETE FROM ens_license_revision");
        }
    }

    /** A DataSource over DriverManager: a new connection per call, like a pool would hand out. */
    static DataSource dataSource(String url, String user, String password) {
        return new DataSource() {
            @Override public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }
            @Override public Connection getConnection(String u, String p) throws SQLException {
                return DriverManager.getConnection(url, u, p);
            }
            @Override public PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                throw new SQLFeatureNotSupportedException();
            }
            @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
        };
    }

    /** The data source, refusing connections while {@code down} is set: a database fail-over. */
    static DataSource failingWhen(DataSource ds, java.util.concurrent.atomic.AtomicBoolean down) {
        DataSource plain = dataSource(null, null, null);
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(TestLicenses.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        if (down.get()) throw new SQLException("Connection refused (test)", "08001");
                        return ds.getConnection();
                    }
                    return method.invoke(plain, args);
                });
    }
}
