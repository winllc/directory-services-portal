package com.winllc.dsp.webhooks;

import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox and delivery log of a single API instance: {@code webhook-deliveries.json} in the data
 * directory. Changes are kept in memory and written (atomically, the whole file) by {@link #flush},
 * which the delivery thread calls every couple of seconds and at shutdown, so an import of
 * thousands of rows doesn't rewrite the file per row; a crash loses at most the last seconds of
 * queued deliveries. At most {@value #MAX_PENDING} deliveries wait at once: beyond that new ones
 * are refused (and reported). Of the finished ones the newest {@value #KEEP_FINISHED} are kept (and
 * none past the retention).
 */
public class FileDeliveries implements Deliveries {
  private static final Logger log = LoggerFactory.getLogger(FileDeliveries.class);
  static final String FILE = "webhook-deliveries.json";
  static final int KEEP_FINISHED = 2000;
  static final int MAX_PENDING = 10_000;

  private final Path file;
  private final JsonMapper mapper;
  private final List<Delivery> all = new ArrayList<>();
  private boolean dirty;
  private int maxPending = MAX_PENDING;

  public FileDeliveries(Path file, JsonMapper mapper) {
    this.file = file;
    this.mapper = mapper;
    if (Files.exists(file)) {
      try {
        all.addAll(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), new TypeReference<List<Delivery>>() {}));
      } catch (IOException | RuntimeException e) {
        log.error("Could not read the webhook deliveries in {}: {}", file, LogSafe.of(e.getMessage()));
      }
    }
  }

  /** For tests. */
  void maxPending(int max) {
    this.maxPending = max;
  }

  private void save() {
    dirty = true;
  }

  @Override
  public synchronized void flush() {
    if (!dirty) return;
    dirty = false;
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(FILE + ".tmp");
      Files.deleteIfExists(tmp);
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      }
      Files.writeString(tmp, mapper.writeValueAsString(all), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException | RuntimeException e) {
      dirty = true;
      log.error("Could not store the webhook deliveries in {}: {}", file, LogSafe.of(e.getMessage()));
    }
  }

  @Override
  public synchronized int enqueue(List<Delivery> deliveries) {
    if (deliveries.isEmpty()) return 0;
    long pending = all.stream().filter(d -> PENDING.equals(d.status())).count();
    int room = (int) Math.max(0, Math.min(deliveries.size(), maxPending - pending));
    all.addAll(deliveries.subList(0, room));
    trim();
    save();
    return room;
  }

  @Override
  public synchronized List<Delivery> claim(Instant now, int limit, Duration lease, Set<String> skip) {
    List<Delivery> out = new ArrayList<>();
    for (int i = 0; i < all.size() && out.size() < limit; i++) {
      Delivery d = all.get(i);
      if (!PENDING.equals(d.status()) || d.nextAttemptAt().isAfter(now) || skip.contains(d.webhookId())) continue;
      Delivery claimed = new Delivery(d.id(), d.webhookId(), d.eventId(), d.eventType(), d.payload(), d.status(), d.attempts() + 1,
          now.plus(lease), d.createdAt(), d.lastAttemptAt(), d.lastStatus(), d.lastError(), d.deliveredAt());
      all.set(i, claimed);
      out.add(claimed);
    }
    if (!out.isEmpty()) save();
    return out;
  }

  @Override
  public synchronized int postpone(String webhookId, Instant until) {
    int moved = 0;
    for (int i = 0; i < all.size(); i++) {
      Delivery d = all.get(i);
      if (d.webhookId().equals(webhookId) && PENDING.equals(d.status()) && d.nextAttemptAt().isBefore(until)) {
        all.set(i, new Delivery(d.id(), d.webhookId(), d.eventId(), d.eventType(), d.payload(), d.status(), d.attempts(), until, d.createdAt(),
            d.lastAttemptAt(), d.lastStatus(), d.lastError(), d.deliveredAt()));
        moved++;
      }
    }
    if (moved > 0) save();
    return moved;
  }

  @Override
  public synchronized void record(Delivery delivery) {
    all.replaceAll(d -> d.id().equals(delivery.id()) ? delivery : d);
    trim();
    save();
  }

  @Override
  public synchronized List<Delivery> recent(String webhookId, int limit) {
    return all.stream().filter(d -> d.webhookId().equals(webhookId))
        .sorted(Comparator.comparing(Delivery::createdAt).reversed()).limit(limit).toList();
  }

  @Override
  public synchronized void cancel(String webhookId, String reason) {
    Instant now = Instant.now();
    boolean changed = false;
    for (int i = 0; i < all.size(); i++) {
      Delivery d = all.get(i);
      if (d.webhookId().equals(webhookId) && PENDING.equals(d.status())) {
        all.set(i, d.attempted(now, null, reason, FAILED, d.nextAttemptAt()));
        changed = true;
      }
    }
    if (changed) save();
  }

  @Override
  public synchronized void purge(Instant before) {
    if (all.removeIf(d -> !PENDING.equals(d.status()) && d.createdAt().isBefore(before))) save();
  }

  private void trim() {
    long finished = all.stream().filter(d -> !PENDING.equals(d.status())).count();
    if (finished <= KEEP_FINISHED) return;
    List<Delivery> oldest = all.stream().filter(d -> !PENDING.equals(d.status()))
        .sorted(Comparator.comparing(Delivery::createdAt)).limit(finished - KEEP_FINISHED).toList();
    all.removeAll(oldest);
  }
}
