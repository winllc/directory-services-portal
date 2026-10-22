package com.winllc.dsp.webhooks;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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

  /** Queues deliveries; returns how many were accepted (a full outbox refuses the rest). */
  int enqueue(List<Delivery> deliveries);

  /** Writes changes kept in memory to storage (the file store batches its writes). */
  default void flush() {}

  /**
   * Due pending deliveries, each counted as an attempt and leased until {@code now + lease} (another
   * replica, or this one after a restart, retries it after that if no outcome was recorded).
   */
  default List<Delivery> claim(Instant now, int limit, Duration lease) {
    return claim(now, limit, lease, Set.of());
  }

  /**
   * As {@link #claim(Instant, int, Duration)}, oldest due first, leaving the deliveries of the
   * webhooks in {@code skip} alone (not claimed, not counted): those a delivery run is done with.
   */
  List<Delivery> claim(Instant now, int limit, Duration lease, Set<String> skip);

  /**
   * Moves a webhook's pending deliveries that are due before {@code until} to {@code until} (never
   * earlier): its receiver gave no answer, so the whole webhook waits out the backoff, not one
   * delivery at a time. Returns how many were moved.
   */
  int postpone(String webhookId, Instant until);

  /** Records an attempt's outcome. */
  void record(Delivery delivery);

  /** The most recent deliveries of a webhook, newest first. */
  List<Delivery> recent(String webhookId, int limit);

  /** Marks a webhook's pending deliveries failed (it was deleted or disabled). */
  void cancel(String webhookId, String reason);

  /** Forgets finished deliveries created before {@code before}. */
  void purge(Instant before);
}
