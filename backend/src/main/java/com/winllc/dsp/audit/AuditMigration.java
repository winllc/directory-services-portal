package com.winllc.dsp.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Moves the portal's original audit files (one JSON object per line, {@code {"id":"audit-…","at":…}})
 * into the {@code ens-audit/1} store, once, at startup:
 *
 * <ol>
 *   <li>each original {@code audit-YYYY-MM.jsonl} is moved, unchanged, to {@code legacy/} next to
 *       the store, where it stays readable as it was;
 *   <li>their events are sorted by original time and appended to the new store, keeping their time
 *       and id, with seq numbers and hashes assigned now and {@code details.migrated=true};
 *   <li>{@code legacy/MIGRATED} records that it is done.
 * </ol>
 *
 * Interrupted half way, the next start removes what was migrated (lines marked migrated, nothing
 * else) and runs it again.
 */
final class AuditMigration {
  private static final Logger log = LoggerFactory.getLogger(AuditMigration.class);
  static final String LEGACY_DIR = "legacy";
  static final String MARKER = "MIGRATED";

  private final JsonMapper mapper;
  private final AuditLog audit;

  AuditMigration(JsonMapper mapper, AuditLog audit) {
    this.mapper = mapper;
    this.audit = audit;
  }

  void run(FileAuditStore store) {
    Path legacy = store.dir().resolve(LEGACY_DIR);
    try {
      for (Path f : store.legacyFiles()) {
        Files.createDirectories(legacy);
        Path to = legacy.resolve(f.getFileName());
        for (int i = 1; Files.exists(to); i++) to = legacy.resolve(f.getFileName() + "." + i);
        Files.move(f, to, StandardCopyOption.ATOMIC_MOVE);
        log.info("Moved the original audit file {} to {}", f.getFileName(), to);
      }
      if (!Files.isDirectory(legacy) || Files.exists(legacy.resolve(MARKER))) return;
      List<Path> sources = legacySources(legacy);
      if (sources.isEmpty()) return;
      if (!removePartialMigration(store)) {
        log.warn("Not migrating {}: the audit store already holds new events; the original files stay in {}", legacy, legacy);
        return;
      }
      List<Legacy> events = new ArrayList<>();
      int skipped = 0;
      for (Path f : sources) {
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
          if (line.isBlank()) continue;
          try {
            JsonNode n = mapper.readTree(line);
            Instant at = Instant.parse(n.get("at").asString());
            events.add(new Legacy(at, n));
          } catch (RuntimeException e) {
            skipped++;
          }
        }
      }
      events.sort(Comparator.comparing(Legacy::at)); // stable: file order within the same instant
      List<AuditEvent> converted = new ArrayList<>(events.size());
      for (Legacy l : events) converted.add(convert(l.at(), l.node()));
      store.appendAll(converted);
      Files.writeString(legacy.resolve(MARKER), "Migrated " + converted.size() + " event(s) to ens-audit/1 at " + AuditLog.now()
          + (skipped > 0 ? "; " + skipped + " unreadable line(s) left in the original files" : "") + "\n");
      log.info("Migrated {} audit event(s) from {} into the ens-audit/1 store ({} unreadable line(s) skipped)", converted.size(), legacy, skipped);
    } catch (IOException | RuntimeException e) {
      log.error("Could not migrate the original audit files in {}: {}", legacy, e.getMessage(), e);
    }
  }

  private static List<Path> legacySources(Path legacy) throws IOException {
    try (Stream<Path> s = Files.list(legacy)) {
      return s.filter(p -> p.getFileName().toString().matches("audit-\\d{4}-\\d{2}\\.jsonl(\\.\\d+)?")).sorted().toList();
    }
  }

  /** True when the store is empty, or holds only a previous, interrupted migration (removed here). */
  private static boolean removePartialMigration(FileAuditStore store) throws IOException {
    List<Path> files = store.storeFiles();
    for (Path f : files) {
      for (String l : FileAuditStore.readLines(f)) {
        if (!l.isBlank() && !l.contains("\"migrated\":true")) return false;
      }
    }
    for (Path f : files) Files.delete(f);
    store.reopen();
    return true;
  }

  private record Legacy(Instant at, JsonNode node) {}

  /** One original event in the new format: same time, id, action and facts; outcome "failed" is now "failure". */
  AuditEvent convert(Instant at, JsonNode n) {
    String action = text(n, "action");
    String outcome = text(n, "outcome");
    if ("failed".equals(outcome)) outcome = AuditEvent.FAILURE;
    if (!AuditCatalog.OUTCOMES.contains(outcome)) outcome = AuditEvent.SUCCESS;
    String oldId = text(n, "id");
    String id = oldId != null && oldId.startsWith("audit-") ? oldId.substring(6) : oldId;
    try {
      id = UUID.fromString(id).toString();
    } catch (RuntimeException e) {
      id = UUID.randomUUID().toString();
    }
    AuditLog.Draft d = audit.event(action).at(AuditLog.TIME.format(at), id);
    JsonNode actor = n.get("actor");
    JsonNode target = n.get("target");
    if (actor != null && actor.isObject()) {
      d.actor(new AuditEvent.Actor("user", text(actor, "username"), null, List.of()));
      d.detail("actorDn", text(actor, "dn")).detail("authMethod", text(actor, "authMethod"));
    } else if (action.equals("auth.login") && target != null) {
      d.anonymous(text(target, "id"));
    } else {
      d.anonymous(null);
    }
    d.source(text(n, "sourceAddress"), null);
    if (target != null && target.isObject()) d.target(text(target, "type"), text(target, "id"), text(target, "name"));
    d.definition(text(n, "definitionId"));
    JsonNode details = n.get("details");
    if (details != null && details.isObject()) {
      for (var e : details.properties()) d.detail(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString());
    }
    JsonNode changes = n.get("changes");
    if (changes != null && changes.isArray()) {
      List<AuditEvent.Change> list = new ArrayList<>();
      for (JsonNode c : changes) list.add(new AuditEvent.Change(text(c, "attribute"), texts(c.get("before")), texts(c.get("after"))));
      d.changes(list);
    }
    d.detail("legacyId", oldId).detail("migrated", true);
    // Not part of a request: no request id or session.
    AuditEvent e = d.build(outcome, text(n, "message"));
    return new AuditEvent(e.id(), 0, e.time(), e.product(), e.version(), e.host(), e.installationId(), e.category(), e.action(),
        e.outcome(), e.severity(), e.actor(), e.source(), e.target(), e.reason(), e.details(), null, null, null, null);
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static List<String> texts(JsonNode array) {
    List<String> out = new ArrayList<>();
    if (array != null && array.isArray()) array.forEach(v -> out.add(v.asString()));
    return out;
  }
}
