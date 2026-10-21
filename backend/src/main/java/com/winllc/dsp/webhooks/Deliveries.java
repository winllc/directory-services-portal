package com.winllc.dsp.webhooks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The webhook outbox and delivery log. Alone, {@link FileDeliveries} (the data directory); with the
 * shared database, {@link JdbcDeliveries}, where each due delivery is claimed by one replica.
 */
public interface Deliveries {
  /** pending: waiting for its next attempt; delivered; failed: out of attempts, or cancelled. */
  String PENDING = "pending";
  String DELIVERED = "delivered";
  String FAILED = "failed";

  record Delivery(String id, String webhookId, String eventId, String eventType, String payload, String status, int attempts,
      Instant nextAttemptAt, Instant createdAt, Instant lastAttemptAt, Integer lastStatus, String lastError, Instant deliveredAt) {

    Delivery attempted(Instant at, Integer statusCode, String error, String newStatus, Instant next) {
      return new Delivery(id, webhookId, eventId, eventType, payload, newStatus, attempts, next, createdAt, at, statusCode, error,
          DELIVERED.equals(newStatus) ? at : deliveredAt);
    }
  }

  void enqueue(List<Delivery> deliveries);

  /**
   * Due pending deliveries, each counted as an attempt and leased until {@code now + lease} (another
   * replica, or this one after a restart, retries it after that if no outcome was recorded).
   */
  List<Delivery> claim(Instant now, int limit, Duration lease);

  /** Records an attempt's outcome. */
  void record(Delivery delivery);

  /** The most recent deliveries of a webhook, newest first. */
  List<Delivery> recent(String webhookId, int limit);

  /** Marks a webhook's pending deliveries failed (it was deleted or disabled). */
  void cancel(String webhookId, String reason);

  /** Forgets finished deliveries created before {@code before}. */
  void purge(Instant before);
}
