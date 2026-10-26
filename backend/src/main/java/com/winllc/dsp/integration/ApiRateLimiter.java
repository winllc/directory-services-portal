package com.winllc.dsp.integration;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.util.LogSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A token bucket per key: up to {@code limit} requests in a burst, refilled at {@code limit} per
 * minute.
 *
 * <ul>
 *   <li>{@link #acquire}: per API token, in each API replica's memory: with several replicas behind
 *       a load balancer, a token can make up to its limit on each of them.
 *   <li>{@link #acquireShared}: per client address, for failed token attempts ({@code bad-token:}
 *       keys) and single sign-on starts ({@code sso-start:} keys). With the shared database the
 *       buckets are in {@code dsp_rate_limit}, so the limit holds across replicas; alone (or while
 *       the database can't be reached) in memory.
 * </ul>
 */
@Component
public class ApiRateLimiter {
  private static final Logger log = LoggerFactory.getLogger(ApiRateLimiter.class);
  private static final long MINUTE_NANOS = 60_000_000_000L;
  /** Longest key stored in the database (client addresses are far shorter). */
  static final int MAX_KEY = 200;

  /**
   * Takes one from a key's bucket in one statement: the refill since the last use (by the
   * database's clock; a changed limit starts full), then one token if there is one. Returns
   * whether there was, and what is left.
   */
  private static final String REFILL = "least(EXCLUDED.per_minute, CASE WHEN r.per_minute = EXCLUDED.per_minute THEN r.tokens"
      + " ELSE EXCLUDED.per_minute END + greatest(0, extract(epoch FROM now() - r.updated_at)) * EXCLUDED.per_minute / 60.0)";
  private static final String TAKE = "INSERT INTO dsp_rate_limit AS r (bucket, tokens, per_minute, allowed, updated_at)"
      + " VALUES (?, ?, ?, true, now()) ON CONFLICT (bucket) DO UPDATE SET"
      + " allowed = " + REFILL + " >= 1,"
      + " tokens = " + REFILL + " - CASE WHEN " + REFILL + " >= 1 THEN 1 ELSE 0 END,"
      + " per_minute = EXCLUDED.per_minute, updated_at = now()"
      + " RETURNING allowed, tokens";

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final LongSupplier nanos;
  /** The shared database, or null (alone). */
  private final JdbcTemplate jdbc;
  private volatile Instant lastWarning = Instant.EPOCH;

  @org.springframework.beans.factory.annotation.Autowired
  public ApiRateLimiter(Cluster cluster) {
    this(System::nanoTime, cluster.shared() ? cluster.jdbc() : null);
  }

  ApiRateLimiter(LongSupplier nanos) {
    this(nanos, null);
  }

  ApiRateLimiter(LongSupplier nanos, JdbcTemplate jdbc) {
    this.nanos = nanos;
    this.jdbc = jdbc;
  }

  private static final class Bucket {
    double tokens;
    long at;
    int limit;

    Bucket(int limit, long at) {
      this.tokens = limit;
      this.at = at;
      this.limit = limit;
    }
  }

  /**
   * Takes one request from the token's bucket. Returns 0 when allowed, otherwise the seconds to wait
   * before the next request will be.
   */
  public long acquire(String tokenId, int limit) {
    int perMinute = Math.max(1, limit);
    long now = nanos.getAsLong();
    long[] wait = {0};
    buckets.compute(tokenId, (k, b) -> {
      if (b == null || b.limit != perMinute) b = new Bucket(perMinute, now);
      b.tokens = Math.min(perMinute, b.tokens + (now - b.at) * (double) perMinute / MINUTE_NANOS);
      b.at = now;
      if (b.tokens >= 1) {
        b.tokens -= 1;
      } else {
        double missing = 1 - b.tokens;
        wait[0] = Math.max(1, (long) Math.ceil(missing * 60.0 / perMinute));
      }
      return b;
    });
    return wait[0];
  }

  /**
   * As {@link #acquire}, for a limit that must hold across replicas (per client address): counted
   * in the shared database when there is one. Should the database fail, counted in memory (as
   * alone) rather than refusing or letting everything through, with a warning at most once a
   * minute.
   */
  public long acquireShared(String key, int limit) {
    if (jdbc == null) return acquire(key, limit);
    int perMinute = Math.max(1, limit);
    String bucket = key.length() > MAX_KEY ? key.substring(0, MAX_KEY) : key;
    try {
      Object[] row = jdbc.queryForObject(TAKE, (rs, i) -> new Object[] {rs.getBoolean(1), rs.getDouble(2)}, bucket, perMinute - 1.0, perMinute);
      if (row == null || Boolean.TRUE.equals(row[0])) return 0;
      double missing = 1 - (Double) row[1];
      return Math.max(1, (long) Math.ceil(missing * 60.0 / perMinute));
    } catch (DataAccessException e) {
      Instant now = Instant.now();
      if (Duration.between(lastWarning, now).compareTo(Duration.ofMinutes(1)) >= 0) {
        lastWarning = now;
        log.warn("Could not count {} in the shared database ({}): counting in this replica's memory meanwhile",
            LogSafe.of(bucket.substring(0, Math.max(0, bucket.indexOf(':')))), LogSafe.of(e.getMessage()));
      }
      return acquire(key, limit);
    }
  }

  public void forget(String tokenId) {
    buckets.remove(tokenId);
  }

  /** Drops buckets that have been full (idle) for a while, here and in the shared database. */
  @Scheduled(fixedDelay = 60_000)
  void sweep() {
    long now = nanos.getAsLong();
    buckets.entrySet().removeIf(e -> now - e.getValue().at > 2 * MINUTE_NANOS);
    if (jdbc == null) return;
    try {
      jdbc.update("DELETE FROM dsp_rate_limit WHERE updated_at < now() - interval '2 minutes'");
    } catch (DataAccessException e) {
      log.debug("Could not sweep dsp_rate_limit: {}", LogSafe.of(e.getMessage()));
    }
  }
}
