package com.winllc.dsp.webhooks;

import com.winllc.dsp.cluster.Cluster;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox and delivery log in the shared database ({@code dsp_webhook_delivery}). Replicas claim
 * due deliveries with {@code FOR UPDATE SKIP LOCKED}, so each attempt is made by one replica.
 */
public class JdbcDeliveries implements Deliveries {
  private static final String COLUMNS = "id, webhook_id, event_id, event_type, payload, status, attempts, next_attempt_at, created_at, "
      + "last_attempt_at, last_status, last_error, delivered_at";

  private final JdbcTemplate jdbc;

  public JdbcDeliveries(Cluster cluster) {
    this.jdbc = cluster.jdbc();
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private static Delivery row(ResultSet rs, int i) throws SQLException {
    int status = rs.getInt("last_status");
    return new Delivery(rs.getString("id"), rs.getString("webhook_id"), rs.getString("event_id"), rs.getString("event_type"),
        rs.getString("payload"), rs.getString("status"), rs.getInt("attempts"), instant(rs, "next_attempt_at"), instant(rs, "created_at"),
        instant(rs, "last_attempt_at"), rs.wasNull() ? null : status, rs.getString("last_error"), instant(rs, "delivered_at"));
  }

  @Override
  public void enqueue(List<Delivery> deliveries) {
    for (Delivery d : deliveries) {
      jdbc.update("INSERT INTO dsp_webhook_delivery (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          d.id(), d.webhookId(), d.eventId(), d.eventType(), d.payload(), d.status(), d.attempts(), ts(d.nextAttemptAt()),
          ts(d.createdAt()), ts(d.lastAttemptAt()), d.lastStatus(), d.lastError(), ts(d.deliveredAt()));
    }
  }

  @Override
  public List<Delivery> claim(Instant now, int limit, Duration lease) {
    return jdbc.query("UPDATE dsp_webhook_delivery SET attempts = attempts + 1, next_attempt_at = ? WHERE id IN ("
        + "SELECT id FROM dsp_webhook_delivery WHERE status = 'pending' AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ? "
        + "FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS, JdbcDeliveries::row, ts(now.plus(lease)), ts(now), limit);
  }

  @Override
  public void record(Delivery d) {
    jdbc.update("UPDATE dsp_webhook_delivery SET status = ?, next_attempt_at = ?, last_attempt_at = ?, last_status = ?, last_error = ?, "
        + "delivered_at = ? WHERE id = ?", d.status(), ts(d.nextAttemptAt()), ts(d.lastAttemptAt()), d.lastStatus(), d.lastError(),
        ts(d.deliveredAt()), d.id());
  }

  @Override
  public List<Delivery> recent(String webhookId, int limit) {
    return jdbc.query("SELECT " + COLUMNS + " FROM dsp_webhook_delivery WHERE webhook_id = ? ORDER BY created_at DESC LIMIT ?",
        JdbcDeliveries::row, webhookId, limit);
  }

  @Override
  public void cancel(String webhookId, String reason) {
    jdbc.update("UPDATE dsp_webhook_delivery SET status = 'failed', last_error = ?, last_attempt_at = now() WHERE webhook_id = ? AND status = 'pending'",
        reason, webhookId);
  }

  @Override
  public void purge(Instant before) {
    jdbc.update("DELETE FROM dsp_webhook_delivery WHERE status <> 'pending' AND created_at < ?", ts(before));
  }
}
