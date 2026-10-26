package com.winllc.dsp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.SharedDatabaseSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-client-address limits (failed API tokens, single sign-on starts) with the shared database
 * (PostgreSQL): counted in dsp_rate_limit, so they hold across replicas.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedRateLimitTest {
  HikariDataSource ds;
  JdbcTemplate jdbc;

  @BeforeEach
  void database() {
    ds = new HikariDataSource();
    ds.setJdbcUrl(SharedDatabaseSupport.newDatabase());
    ds.setUsername(SharedDatabaseSupport.postgres().getUsername());
    ds.setPassword(SharedDatabaseSupport.postgres().getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
  }

  @AfterEach
  void close() {
    ds.close();
  }

  private ApiRateLimiter replica() {
    return new ApiRateLimiter(System::nanoTime, jdbc);
  }

  @Test
  void theLimitHoldsAcrossReplicasPerKey() {
    ApiRateLimiter a = replica();
    ApiRateLimiter b = replica();
    int allowed = 0;
    for (int i = 0; i < 10; i++) {
      if ((i % 2 == 0 ? a : b).acquireShared("bad-token:192.0.2.1", 5) == 0) allowed++;
    }
    // Five in all, not five on each replica.
    assertThat(allowed).isEqualTo(5);
    long wait = a.acquireShared("bad-token:192.0.2.1", 5);
    assertThat(wait).isBetween(1L, 12L);
    // Other addresses, and the other limit for the same address, have their own buckets.
    assertThat(b.acquireShared("bad-token:192.0.2.2", 5)).isZero();
    assertThat(b.acquireShared("sso-start:192.0.2.1", 5)).isZero();

    // A minute later (the database's clock) the bucket is full again.
    jdbc.update("UPDATE dsp_rate_limit SET updated_at = updated_at - interval '1 minute'");
    allowed = 0;
    for (int i = 0; i < 10; i++) {
      if ((i % 2 == 0 ? a : b).acquireShared("bad-token:192.0.2.1", 5) == 0) allowed++;
    }
    assertThat(allowed).isEqualTo(5);

    // Without the database each replica counts alone (as before): five on each.
    ApiRateLimiter alone1 = new ApiRateLimiter(System::nanoTime);
    ApiRateLimiter alone2 = new ApiRateLimiter(System::nanoTime);
    allowed = 0;
    for (int i = 0; i < 10; i++) {
      if ((i % 2 == 0 ? alone1 : alone2).acquireShared("bad-token:192.0.2.1", 5) == 0) allowed++;
    }
    assertThat(allowed).isEqualTo(10);
  }

  @Test
  void concurrentRequestsOnSeveralReplicasCountOnceEach() throws Exception {
    List<ApiRateLimiter> replicas = List.of(replica(), replica(), replica());
    ExecutorService pool = Executors.newFixedThreadPool(12);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int t = 0; t < 12; t++) {
        ApiRateLimiter r = replicas.get(t % replicas.size());
        tasks.add(() -> {
          int ok = 0;
          for (int i = 0; i < 10; i++) if (r.acquireShared("sso-start:198.51.100.7", 20) == 0) ok++;
          return ok;
        });
      }
      int allowed = 0;
      for (Future<Integer> f : pool.invokeAll(tasks)) allowed += f.get();
      // 20, and at most one more refilled while the 120 requests ran (20 a minute).
      assertThat(allowed).isBetween(20, 21);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aChangedLimitStartsFull() {
    ApiRateLimiter a = replica();
    for (int i = 0; i < 2; i++) assertThat(a.acquireShared("bad-token:192.0.2.9", 2)).isZero();
    assertThat(a.acquireShared("bad-token:192.0.2.9", 2)).isPositive();
    assertThat(a.acquireShared("bad-token:192.0.2.9", 4)).isZero();
  }

  @Test
  void idleBucketsAreSwept() {
    ApiRateLimiter a = replica();
    a.acquireShared("bad-token:192.0.2.3", 5);
    a.acquireShared("bad-token:192.0.2.4", 5);
    jdbc.update("UPDATE dsp_rate_limit SET updated_at = updated_at - interval '3 minutes' WHERE bucket = 'bad-token:192.0.2.3'");
    a.sweep();
    assertThat(jdbc.queryForList("SELECT bucket FROM dsp_rate_limit", String.class)).containsExactly("bad-token:192.0.2.4");
  }

  @Test
  void anUnreachableDatabaseCountsInMemoryMeanwhile() {
    DriverManagerDataSource down = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "y");
    ApiRateLimiter a = new ApiRateLimiter(System::nanoTime, new JdbcTemplate(down));
    assertThat(a.acquireShared("bad-token:192.0.2.5", 2)).isZero();
    assertThat(a.acquireShared("bad-token:192.0.2.5", 2)).isZero();
    assertThat(a.acquireShared("bad-token:192.0.2.5", 2)).isPositive();
  }
}
