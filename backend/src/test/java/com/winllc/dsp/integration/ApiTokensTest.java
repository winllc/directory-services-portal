package com.winllc.dsp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.cluster.Cluster;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ApiTokensTest {
  @TempDir Path dir;

  /** A clock the test moves. */
  static final class TestClock extends Clock {
    final AtomicLong millis = new AtomicLong(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli());

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    void advance(Duration d) {
      millis.addAndGet(d.toMillis());
    }
  }

  private ApiTokens tokens(TestClock clock) {
    return new ApiTokens(dir.resolve("api-tokens.json"), JsonMapper.builder().build(), Cluster.standalone(), clock);
  }

  @Test
  void expiresAndIsRevokedAndKeepsOnlyAHash() throws Exception {
    TestClock clock = new TestClock();
    ApiTokens tokens = tokens(clock);
    ApiTokens.Created created = tokens.create("crm", List.of("entries:read"), List.of(), Duration.ofDays(1), null, "admin", "uid=admin,dc=x");
    assertThat(created.secret()).startsWith(ApiTokens.PREFIX).hasSize(ApiTokens.PREFIX.length() + 43);
    assertThat(tokens.check(created.secret()).ok()).isTrue();
    assertThat(tokens.check(created.secret() + "x").problem()).isEqualTo("unknown");
    assertThat(tokens.check("Bearer " + created.secret()).problem()).isEqualTo("unknown");

    clock.advance(Duration.ofHours(23));
    assertThat(tokens.check(created.secret()).ok()).isTrue();
    clock.advance(Duration.ofHours(1));
    assertThat(tokens.check(created.secret()).problem()).isEqualTo("expired");
    assertThat(tokens.find(created.token().id()).status()).isEqualTo("expired");

    ApiTokens.Created other = tokens.create("erp", List.of("entries:read"), List.of(), Duration.ofDays(30), 10, "admin", "uid=admin,dc=x");
    tokens.revoke(other.token().id(), "admin");
    assertThat(tokens.check(other.secret()).problem()).isEqualTo("revoked");

    // Kept as a hash, in a file only the portal's user can read; read back by a new instance.
    Path file = dir.resolve("api-tokens.json");
    String stored = Files.readString(file);
    assertThat(stored).doesNotContain(created.secret()).doesNotContain(other.secret()).contains(created.token().id());
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    ApiTokens reloaded = tokens(clock);
    assertThat(reloaded.check(other.secret()).problem()).isEqualTo("revoked");
    assertThat(reloaded.list()).hasSize(2);
  }

  @Test
  void recordsTheLastUseAtMostOnceAMinute() throws Exception {
    TestClock clock = new TestClock();
    ApiTokens tokens = tokens(clock);
    ApiTokens.Created created = tokens.create("crm", List.of("entries:read"), List.of(), Duration.ofDays(1), null, "admin", "uid=admin,dc=x");
    tokens.check(created.secret());
    String first = tokens.find(created.token().id()).lastUsedAt();
    assertThat(first).isNotNull();
    clock.advance(Duration.ofSeconds(30));
    tokens.check(created.secret());
    assertThat(tokens.find(created.token().id()).lastUsedAt()).isEqualTo(first);
    clock.advance(Duration.ofSeconds(31));
    tokens.check(created.secret());
    assertThat(tokens.find(created.token().id()).lastUsedAt()).isNotEqualTo(first);
  }

  @Test
  void rateLimitsPerTokenWithABurstAndRefill() {
    AtomicLong nanos = new AtomicLong();
    ApiRateLimiter limiter = new ApiRateLimiter(nanos::get);
    for (int i = 0; i < 60; i++) assertThat(limiter.acquire("a", 60)).isZero();
    assertThat(limiter.acquire("a", 60)).isEqualTo(1);
    assertThat(limiter.acquire("b", 60)).isZero();
    nanos.addAndGet(1_000_000_000L);
    assertThat(limiter.acquire("a", 60)).isZero();
    assertThat(limiter.acquire("a", 60)).isPositive();
  }
}
