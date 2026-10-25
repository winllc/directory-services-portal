package com.winllc.dsp.integration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A token bucket per API token: up to {@code limit} requests in a burst, refilled at
 * {@code limit} per minute. Kept in each API replica's memory: with several replicas behind a load
 * balancer, a token can make up to the limit on each of them. Also counts failed token attempts
 * ({@code bad-token:} keys) and single sign-on starts ({@code sso-start:} keys) per client address.
 */
@Component
public class ApiRateLimiter {
  private static final long MINUTE_NANOS = 60_000_000_000L;

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final LongSupplier nanos;

  @org.springframework.beans.factory.annotation.Autowired
  public ApiRateLimiter() {
    this(System::nanoTime);
  }

  ApiRateLimiter(LongSupplier nanos) {
    this.nanos = nanos;
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

  public void forget(String tokenId) {
    buckets.remove(tokenId);
  }

  /** Drops buckets that have been full (idle) for a while. */
  @Scheduled(fixedDelay = 60_000)
  void sweep() {
    long now = nanos.getAsLong();
    buckets.entrySet().removeIf(e -> now - e.getValue().at > 2 * MINUTE_NANOS);
  }
}
