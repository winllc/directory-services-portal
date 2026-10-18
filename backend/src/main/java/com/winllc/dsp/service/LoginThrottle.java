package com.winllc.dsp.service;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.audit.AuditJson;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Failed password sign-ins per (client address, user name) within the lockout window, counted from
 * the first failure. Alone they are kept in memory; with the shared database in
 * {@code dsp_login_failure} (as SHA-256 of the key), so the lockout holds whichever replica a
 * request reaches.
 */
@Component
public class LoginThrottle {
  private record Failures(int count, long first) {}

  private final JdbcTemplate jdbc;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  public LoginThrottle(Cluster cluster) {
    this.jdbc = cluster.shared() ? cluster.jdbc() : null;
  }

  /** Whether {@code key} has reached {@code maxFailures} within the window. */
  public boolean locked(String key, int maxFailures, long windowMs) {
    if (jdbc != null) {
      List<Integer> rows = jdbc.query("SELECT failures FROM dsp_login_failure WHERE throttle_key = ? AND first_at > now() - ? * interval '1 millisecond'",
          (rs, i) -> rs.getInt(1), AuditJson.sha256Hex(key), windowMs);
      return !rows.isEmpty() && rows.get(0) >= maxFailures;
    }
    Failures f = failures.get(key);
    return f != null && System.currentTimeMillis() - f.first() < windowMs && f.count() >= maxFailures;
  }

  /** Counts one more failure (a new window when the last one has passed); returns the count. */
  public int recordFailure(String key, long windowMs) {
    if (jdbc != null) {
      Integer count = jdbc.queryForObject("""
          INSERT INTO dsp_login_failure (throttle_key, failures, first_at) VALUES (?, 1, now())
          ON CONFLICT (throttle_key) DO UPDATE SET
            failures = CASE WHEN dsp_login_failure.first_at <= now() - ? * interval '1 millisecond' THEN 1
                            ELSE dsp_login_failure.failures + 1 END,
            first_at = CASE WHEN dsp_login_failure.first_at <= now() - ? * interval '1 millisecond' THEN now()
                            ELSE dsp_login_failure.first_at END
          RETURNING failures""", Integer.class, AuditJson.sha256Hex(key), windowMs, windowMs);
      return count == null ? 1 : count;
    }
    long now = System.currentTimeMillis();
    return failures.compute(key, (k, f) -> f == null || now - f.first() > windowMs ? new Failures(1, now) : new Failures(f.count() + 1, f.first()))
        .count();
  }

  /** A successful sign-in clears the count. */
  public void clear(String key) {
    if (jdbc != null) {
      jdbc.update("DELETE FROM dsp_login_failure WHERE throttle_key = ?", AuditJson.sha256Hex(key));
      return;
    }
    failures.remove(key);
  }

  /** Drops counts whose window has passed. */
  public void sweep(long windowMs) {
    if (jdbc != null) {
      jdbc.update("DELETE FROM dsp_login_failure WHERE first_at <= now() - ? * interval '1 millisecond'", windowMs);
      return;
    }
    long now = System.currentTimeMillis();
    failures.entrySet().removeIf(e -> now - e.getValue().first() > windowMs);
  }
}
