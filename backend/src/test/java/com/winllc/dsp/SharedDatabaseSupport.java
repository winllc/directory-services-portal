package com.winllc.dsp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for every shared-database test, and a fresh database in it for each
 * Spring context (so contexts kept in the test cache don't see each other's data, as separate
 * installations wouldn't).
 */
public final class SharedDatabaseSupport {
  private static PostgreSQLContainer postgres;

  private SharedDatabaseSupport() {}

  public static synchronized PostgreSQLContainer postgres() {
    if (postgres == null) {
      postgres = new PostgreSQLContainer("postgres:16-alpine");
      postgres.start();
    }
    return postgres;
  }

  /** A new, empty database; returns its JDBC URL. */
  public static String newDatabase() {
    PostgreSQLContainer pg = postgres();
    String name = "dsp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + name);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
  }

  /** Points the context at a new database. */
  static void register(DynamicPropertyRegistry registry) {
    String url = newDatabase();
    registry.add("portal.database.url", () -> url);
    registry.add("portal.database.username", () -> postgres().getUsername());
    registry.add("portal.database.password", () -> postgres().getPassword());
    // Changes made through another "replica" in a test are seen at once.
    registry.add("portal.database.config-refresh", () -> "0s");
  }
}
