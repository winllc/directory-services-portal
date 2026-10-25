package com.winllc.dsp.sso;

import com.winllc.dsp.audit.AuditJson;
import com.winllc.dsp.cluster.Cluster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single sign-on state that must outlive one request: sign-in requests in flight (each used once,
 * for a few minutes) and the SAML assertion ids already accepted, so a captured response can't be
 * replayed. In memory alone; with the shared database in {@code dsp_sso_request} and
 * {@code dsp_saml_assertion}, so the provider's answer may reach any replica.
 */
@Component
public class SsoStateStore {
  /** Only what sign-in requests are made of may be read back. */
  private static final ObjectInputFilter CLASSES = ObjectInputFilter.Config.createFilter(
      "maxdepth=20;maxrefs=10000;maxbytes=262144;java.base/*;com.winllc.dsp.sso.*;org.springframework.security.**;!*");

  private record Entry(Object value, Instant expires) {}

  private final JdbcTemplate jdbc;
  private final Map<String, Entry> requests = new ConcurrentHashMap<>();
  private final Map<String, Instant> assertions = new ConcurrentHashMap<>();
  private final int maxPending;

  @org.springframework.beans.factory.annotation.Autowired
  public SsoStateStore(Cluster cluster, SsoProperties props) {
    this(cluster.shared() ? cluster.jdbc() : null, props.getMaxPendingRequests());
  }

  SsoStateStore(JdbcTemplate jdbc, int maxPending) {
    this.jdbc = jdbc;
    this.maxPending = Math.max(1, maxPending);
  }

  /** The sign-in request store is full (in memory only): no new sign-on can start until some expire. */
  public static final class FullException extends RuntimeException {
    FullException() {
      super("Too many sign-ons in progress; try again in a few minutes");
    }
  }

  /**
   * Keeps {@code value} under {@code handle} (stored by its SHA-256) for {@code ttl}. In memory, at
   * most {@code SSO_MAX_PENDING_REQUESTS} are kept: when full, expired ones are dropped first, and
   * if none has expired the new one is refused ({@link FullException}) rather than displacing a
   * sign-on in progress.
   */
  public void put(String handle, Serializable value, Duration ttl) {
    String key = AuditJson.sha256Hex(handle);
    Instant expires = Instant.now().plus(ttl);
    if (jdbc != null) {
      jdbc.update("INSERT INTO dsp_sso_request (id, payload, expires_at) VALUES (?, ?, ?)", key, serialize(value), Timestamp.from(expires));
      return;
    }
    if (requests.size() >= maxPending) {
      Instant now = Instant.now();
      requests.values().removeIf(e -> e.expires().isBefore(now));
      if (requests.size() >= maxPending) throw new FullException();
    }
    requests.put(key, new Entry(value, expires));
  }

  /** False when the in-memory store is full of unexpired sign-in requests (never with the database). */
  public boolean acceptsMore() {
    if (jdbc != null || requests.size() < maxPending) return true;
    Instant now = Instant.now();
    requests.values().removeIf(e -> e.expires().isBefore(now));
    return requests.size() < maxPending;
  }

  /** Sign-in requests kept in memory (for tests and metrics). */
  int pendingInMemory() {
    return requests.size();
  }

  /** The value under {@code handle}, or null (unknown or expired). */
  public Object peek(String handle) {
    if (handle == null) return null;
    String key = AuditJson.sha256Hex(handle);
    if (jdbc != null) {
      List<byte[]> rows = jdbc.query("SELECT payload FROM dsp_sso_request WHERE id = ? AND expires_at > now()", (rs, i) -> rs.getBytes(1), key);
      return rows.isEmpty() ? null : deserialize(rows.get(0));
    }
    Entry e = requests.get(key);
    return e == null || e.expires().isBefore(Instant.now()) ? null : e.value();
  }

  /** Removes and returns the value under {@code handle}: whoever takes it first gets it, once. */
  public Object take(String handle) {
    if (handle == null) return null;
    String key = AuditJson.sha256Hex(handle);
    if (jdbc != null) {
      List<byte[]> rows = jdbc.query("DELETE FROM dsp_sso_request WHERE id = ? RETURNING payload, expires_at > now()",
          (rs, i) -> rs.getBoolean(2) ? rs.getBytes(1) : null, key);
      return rows.isEmpty() || rows.get(0) == null ? null : deserialize(rows.get(0));
    }
    Entry e = requests.remove(key);
    return e == null || e.expires().isBefore(Instant.now()) ? null : e.value();
  }

  /**
   * Records an assertion id as used until {@code expires}; false when it was used before (a
   * replay).
   */
  public boolean firstUse(String assertionId, Instant expires) {
    if (jdbc != null) {
      return jdbc.update("INSERT INTO dsp_saml_assertion (id, expires_at) VALUES (?, ?) ON CONFLICT (id) DO NOTHING", assertionId,
          Timestamp.from(expires)) == 1;
    }
    return assertions.putIfAbsent(assertionId, expires) == null;
  }

  /** Every minute: expired requests (10 minutes) and assertion ids leave memory or the database promptly. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  void sweep() {
    Instant now = Instant.now();
    if (jdbc != null) {
      jdbc.update("DELETE FROM dsp_sso_request WHERE expires_at < now()");
      jdbc.update("DELETE FROM dsp_saml_assertion WHERE expires_at < now()");
      return;
    }
    requests.values().removeIf(e -> e.expires().isBefore(now));
    assertions.values().removeIf(e -> e.isBefore(now));
  }

  static byte[] serialize(Serializable value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(value);
    } catch (IOException e) {
      throw new IllegalStateException("Could not keep the sign-in request: " + e.getMessage(), e);
    }
    return bytes.toByteArray();
  }

  static Object deserialize(byte[] data) {
    try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
      in.setObjectInputFilter(CLASSES);
      return in.readObject();
    } catch (IOException | ClassNotFoundException | RuntimeException e) {
      return null;
    }
  }
}
