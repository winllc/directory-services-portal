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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox and delivery log of a single API instance: {@code webhook-deliveries.json} in the data
 * directory, rewritten (atomically) on every change. Pending deliveries survive a restart; of the
 * finished ones the newest {@value #KEEP_FINISHED} are kept (and none past the retention).
 */
public class FileDeliveries implements Deliveries {
  private static final Logger log = LoggerFactory.getLogger(FileDeliveries.class);
  static final String FILE = "webhook-deliveries.json";
  static final int KEEP_FINISHED = 2000;

  private final Path file;
  private final JsonMapper mapper;
  private final List<Delivery> all = new ArrayList<>();

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

  private void save() {
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
      log.error("Could not store the webhook deliveries in {}: {}", file, LogSafe.of(e.getMessage()));
    }
  }

  @Override
  public synchronized void enqueue(List<Delivery> deliveries) {
    if (deliveries.isEmpty()) return;
    all.addAll(deliveries);
    trim();
    save();
  }

  @Override
  public synchronized List<Delivery> claim(Instant now, int limit, Duration lease) {
    List<Delivery> out = new ArrayList<>();
    for (int i = 0; i < all.size() && out.size() < limit; i++) {
      Delivery d = all.get(i);
      if (!PENDING.equals(d.status()) || d.nextAttemptAt().isAfter(now)) continue;
      Delivery claimed = new Delivery(d.id(), d.webhookId(), d.eventId(), d.eventType(), d.payload(), d.status(), d.attempts() + 1,
          now.plus(lease), d.createdAt(), d.lastAttemptAt(), d.lastStatus(), d.lastError(), d.deliveredAt());
      all.set(i, claimed);
      out.add(claimed);
    }
    if (!out.isEmpty()) save();
    return out;
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
