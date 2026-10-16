package com.winllc.dsp.audit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers stored events to one remote output (Splunk HEC, syslog) in the background, reading the
 * store from a persisted cursor (the last seq delivered, in {@link AuditState}). Delivery is
 * at-least-once: the cursor moves only after the output accepted a batch, so a crash in between
 * sends that batch again (Splunk can de-duplicate on {@code id}).
 *
 * <p>Failures are retried with exponential backoff capped at 5 minutes; requests never wait for
 * delivery. An output failing for more than 15 minutes records one {@code audit.delivery-failed}
 * (severity alert), and {@code audit.delivery-recovered} once it delivers again.
 */
public abstract class AuditForwarder {
  private static final Logger log = LoggerFactory.getLogger(AuditForwarder.class);

  /** Told when an output has been failing too long, and when it recovers. */
  public interface Listener {
    void failed(String output, String error, Instant since);

    void recovered(String output, Instant failingSince);
  }

  public record Status(String output, boolean enabled, String destination, long lastDeliveredSeq, String lastDeliveredAt,
      long backlog, String lastError, String lastErrorAt, String failingSince) {}

  protected final String name;
  private final AuditStore store;
  private final AuditState state;
  private final Listener listener;
  protected int batchSize = 100;
  long initialBackoffMillis = 1_000;
  long maxBackoffMillis = Duration.ofMinutes(5).toMillis();
  Duration alertAfter = Duration.ofMinutes(15);

  private volatile String lastError;
  private volatile Instant lastErrorAt;
  private volatile Instant failingSince;
  private volatile boolean alerted;
  private volatile boolean running;
  private Thread thread;

  protected AuditForwarder(String name, AuditStore store, AuditState state, Listener listener) {
    this.name = name;
    this.store = store;
    this.state = state;
    this.listener = listener;
  }

  public String name() {
    return name;
  }

  /** Where events go, for the status panel and the startup log (never includes a token). */
  public abstract String destination();

  /** Sends one batch; returns normally only when the output accepted all of it. */
  protected abstract void send(List<AuditStore.Stored> batch) throws IOException;

  /** Releases connections (on failure and at shutdown). */
  protected void close() {}

  public synchronized void start() {
    if (running) return;
    running = true;
    thread = new Thread(this::loop, "audit-" + name);
    thread.setDaemon(true);
    thread.start();
  }

  public void stop() {
    Thread t;
    synchronized (this) {
      running = false;
      t = thread;
      thread = null;
    }
    if (t != null) {
      t.interrupt();
      try {
        t.join(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    close();
  }

  private void loop() {
    long backoff = initialBackoffMillis;
    while (running) {
      try {
        if (deliverOnce()) {
          backoff = initialBackoffMillis;
        } else {
          store.awaitAfter(state.cursor(name).seq(), 1_000);
        }
      } catch (InterruptedException e) {
        return;
      } catch (IOException | RuntimeException e) {
        try {
          Thread.sleep(backoff);
        } catch (InterruptedException ie) {
          return;
        }
        backoff = Math.min(backoff * 2, maxBackoffMillis);
      }
    }
  }

  /**
   * Delivers the next batch after the cursor. Returns false when there was nothing to send;
   * throws (after recording the failure) when the output refused it.
   */
  boolean deliverOnce() throws IOException {
    long cursor = state.cursor(name).seq();
    List<AuditStore.Stored> batch = store.readAfter(cursor, batchSize);
    if (batch.isEmpty()) return false;
    try {
      send(batch);
    } catch (IOException | RuntimeException e) {
      failed(e);
      close();
      throw e;
    }
    long last = batch.get(batch.size() - 1).seq();
    try {
      state.setCursor(name, last, AuditLog.now());
    } catch (IOException e) {
      log.error("Audit output {}: delivered up to seq {} but could not save the cursor: {}", name, last, e.getMessage());
    }
    succeeded();
    return true;
  }

  private void failed(Exception e) {
    Instant now = Instant.now();
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    if (lastError == null || failingSince == null) log.warn("Audit output {} failed: {}; retrying with backoff", name, message);
    lastError = AuditDetails.truncate(message);
    lastErrorAt = now;
    if (failingSince == null) failingSince = now;
    if (!alerted && Duration.between(failingSince, now).compareTo(alertAfter) >= 0) {
      alerted = true;
      log.error("Audit output {} has been failing since {}: {}", name, failingSince, message);
      notify(() -> listener.failed(name, lastError, failingSince));
    }
  }

  private void succeeded() {
    Instant since = failingSince;
    if (since != null) log.info("Audit output {} is delivering again", name);
    failingSince = null;
    if (alerted) {
      alerted = false;
      notify(() -> listener.recovered(name, since));
    }
  }

  private void notify(Runnable r) {
    if (listener == null) return;
    try {
      r.run();
    } catch (RuntimeException e) {
      log.error("Could not record the delivery state of audit output {}: {}", name, e.getMessage());
    }
  }

  public Status status() {
    AuditState.Cursor c = state.cursor(name);
    return new Status(name, true, destination(), c.seq(), c.time(), Math.max(store.headSeq() - c.seq(), 0), lastError,
        lastErrorAt == null ? null : lastErrorAt.toString(), failingSince == null ? null : failingSince.toString());
  }

  public long cursor() {
    return state.cursor(name).seq();
  }

  /** The field value at its first occurrence in a stored line (for fields before any free text). */
  static String field(String line, String field) {
    String key = ",\"" + field + "\":\"";
    int at = line.indexOf(key);
    if (at < 0) return null;
    int start = at + key.length();
    int end = line.indexOf('"', start);
    return end < 0 ? null : line.substring(start, end);
  }
}
