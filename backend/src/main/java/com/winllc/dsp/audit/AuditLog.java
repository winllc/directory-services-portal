package com.winllc.dsp.audit;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records who changed what through the portal, and every sign-in.
 *
 * <p>The directory's own logs only ever see the portal's service account, so this is the one
 * place that says which person made a change. Each event is:
 *
 * <ul>
 *   <li>appended, one JSON object per line, to {@code <audit dir>/audit-YYYY-MM.jsonl} and synced
 *       to disk before the call returns. Files are never rewritten or deleted by the portal;
 *   <li>written to the {@code audit} logger, so a log shipper can forward it to a SIEM;
 *   <li>kept in memory (the most recent {@code portal.audit.memory-events}) for the admin API.
 * </ul>
 *
 * <p>The audited change has already happened by the time it is recorded, so a failure to write
 * the file is logged as an error rather than failing the request.
 */
@Component
public class AuditLog {
  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
  /** Separate logger name so deployments can route audit events on their own. */
  private static final Logger auditLogger = LoggerFactory.getLogger("audit");
  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
  private static final int MAX_VALUE_LENGTH = 1000;

  private final JsonMapper mapper;
  private final Path dir;
  private final int capacity;
  private final Deque<AuditEvent> recent = new ArrayDeque<>();

  public AuditLog(JsonMapper mapper, PortalProperties props) {
    this.mapper = mapper;
    String configured = props.getAudit().getDir();
    this.dir = (configured == null || configured.isBlank()
        ? Path.of(props.getDataDir()).resolve("audit")
        : Path.of(configured)).toAbsolutePath();
    this.capacity = Math.max(props.getAudit().getMemoryEvents(), 100);
    loadRecent();
  }

  // ---------------------------------------------------------------------------
  // Recording
  // ---------------------------------------------------------------------------

  /** Start an event; finish it with {@link Draft#success()}, {@link Draft#denied} or {@link Draft#failed}. */
  public Draft event(String action) {
    return new Draft(action);
  }

  public final class Draft {
    private final String action;
    private SessionUser user;
    private AuditEvent.Target target;
    private String definitionId;
    private final List<AuditEvent.Change> changes = new ArrayList<>();
    private final Map<String, String> details = new LinkedHashMap<>();
    private AuditEvent finished;

    private Draft(String action) {
      this.action = action;
    }

    /** The acting user. Defaults to the user signed in on the current request. */
    public Draft by(SessionUser user) {
      this.user = user;
      return this;
    }

    public Draft target(String type, String id, String name) {
      this.target = new AuditEvent.Target(type, id, name);
      return this;
    }

    public Draft definition(String definitionId) {
      this.definitionId = definitionId;
      return this;
    }

    public Draft changes(Collection<AuditEvent.Change> changes) {
      this.changes.addAll(changes);
      return this;
    }

    public Draft detail(String key, Object value) {
      if (value != null) details.put(key, String.valueOf(value));
      return this;
    }

    public AuditEvent success() {
      return finish(AuditEvent.SUCCESS, null);
    }

    public AuditEvent denied(String message) {
      return finish(AuditEvent.DENIED, message);
    }

    public AuditEvent failed(String message) {
      return finish(AuditEvent.FAILED, message);
    }

    /** An event is recorded once; a later outcome for the same draft is ignored. */
    private AuditEvent finish(String outcome, String message) {
      if (finished != null) return finished;
      SessionUser who = user != null ? user : currentUser();
      AuditEvent.Actor actor = who == null ? null : new AuditEvent.Actor(who.username(), who.dn(), who.authMethod());
      AuditEvent event = new AuditEvent("audit-" + UUID.randomUUID(), Instant.now().toString(), action, outcome, actor,
          sourceAddress(), target, definitionId, message, List.copyOf(changes), Map.copyOf(details));
      record(event);
      finished = event;
      return event;
    }
  }

  private static SessionUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
  }

  private static String sourceAddress() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (!(attrs instanceof ServletRequestAttributes sra)) return null;
    HttpServletRequest request = sra.getRequest();
    return request.getRemoteAddr();
  }

  synchronized void record(AuditEvent event) {
    String line = mapper.writeValueAsString(event);
    auditLogger.info(line);
    try {
      Files.createDirectories(dir);
      Files.writeString(fileFor(event.at()), line + "\n", StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);
    } catch (IOException | RuntimeException e) {
      log.error("Could not write audit event {} to {}: {}", event.id(), dir, e.getMessage());
    }
    recent.addFirst(event);
    while (recent.size() > capacity) recent.removeLast();
  }

  private Path fileFor(String at) {
    return dir.resolve("audit-" + MONTH.format(Instant.parse(at)) + ".jsonl");
  }

  /** Load the newest events from the monthly files, so the admin page survives a restart. */
  private void loadRecent() {
    if (!Files.isDirectory(dir)) return;
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> p.getFileName().toString().matches("audit-\\d{4}-\\d{2}\\.jsonl"))
          .sorted(Comparator.reverseOrder())
          .toList();
    } catch (IOException e) {
      log.warn("Could not list audit directory {}: {}", dir, e.getMessage());
      return;
    }
    int skipped = 0;
    for (Path file : files) {
      List<String> lines;
      try {
        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        log.warn("Could not read audit file {}: {}", file, e.getMessage());
        continue;
      }
      for (int i = lines.size() - 1; i >= 0 && recent.size() < capacity; i--) {
        String line = lines.get(i).trim();
        if (line.isEmpty()) continue;
        try {
          recent.addLast(mapper.readValue(line, AuditEvent.class));
        } catch (RuntimeException e) {
          skipped++;
        }
      }
      if (recent.size() >= capacity) break;
    }
    if (skipped > 0) log.warn("Skipped {} unreadable audit line(s) in {}", skipped, dir);
    log.info("Audit log at {}; loaded {} recent event(s)", dir, recent.size());
  }

  // ---------------------------------------------------------------------------
  // Querying
  // ---------------------------------------------------------------------------

  /**
   * {@code action} matches exactly or as a category prefix ("entry" matches "entry.update");
   * {@code target} and {@code actor} match a DN by DN equality, otherwise as case-insensitive
   * text; {@code q} searches every text field.
   */
  public record Query(String q, String action, String outcome, String actor, String target, String definitionId,
      Instant from, Instant to, int page, int pageSize) {}

  public record Page(List<AuditEvent> events, int total, int page, int pageSize, int retained, int capacity, String oldest) {}

  public synchronized Page query(Query query) {
    Predicate<AuditEvent> match = e -> true;
    if (notBlank(query.action())) {
      String a = query.action().trim();
      match = match.and(e -> e.action().equals(a) || e.action().startsWith(a + "."));
    }
    if (notBlank(query.outcome())) match = match.and(e -> e.outcome().equals(query.outcome().trim()));
    if (notBlank(query.definitionId())) match = match.and(e -> query.definitionId().equals(e.definitionId()));
    if (notBlank(query.actor())) {
      String a = query.actor().trim();
      match = match.and(e -> e.actor() != null && (identifies(a, e.actor().dn()) || equalsIgnoreCase(a, e.actor().username())));
    }
    if (notBlank(query.target())) {
      List<String> names = namesOf(query.target().trim());
      match = match.and(e -> e.target() != null && names.stream().anyMatch(n -> identifies(n, e.target().id())));
    }
    if (query.from() != null) match = match.and(e -> !Instant.parse(e.at()).isBefore(query.from()));
    if (query.to() != null) match = match.and(e -> Instant.parse(e.at()).isBefore(query.to()));
    if (notBlank(query.q())) {
      String needle = query.q().trim().toLowerCase(Locale.ROOT);
      match = match.and(e -> text(e).contains(needle));
    }
    List<AuditEvent> all = recent.stream().filter(match).toList();
    int pageSize = Math.min(Math.max(query.pageSize(), 1), 200);
    int page = Math.max(query.page(), 1);
    int from = Math.min((page - 1) * pageSize, all.size());
    int to = Math.min(from + pageSize, all.size());
    String oldest = recent.isEmpty() ? null : recent.peekLast().at();
    return new Page(all.subList(from, to), all.size(), page, pageSize, recent.size(), capacity, oldest);
  }

  /**
   * Every DN a target has had, following recorded renames both ways, so an entry's history
   * includes what happened to it under earlier and later names.
   */
  private List<String> namesOf(String target) {
    List<String> names = new ArrayList<>(List.of(target));
    boolean grew = true;
    while (grew) {
      grew = false;
      for (AuditEvent e : recent) {
        String from = e.details().get("previousDn");
        if (from == null || e.target() == null || e.target().id() == null) continue;
        boolean knowsFrom = names.stream().anyMatch(n -> identifies(n, from));
        boolean knowsTo = names.stream().anyMatch(n -> identifies(n, e.target().id()));
        if (knowsFrom != knowsTo) {
          names.add(knowsFrom ? e.target().id() : from);
          grew = true;
        }
      }
    }
    return names;
  }

  /** A DN query matches by DN equality; anything else by case-insensitive equality. */
  private static boolean identifies(String query, String value) {
    if (value == null) return false;
    if (Dns.isValid(query) && Dns.isValid(value) && query.contains("=")) return Dns.equal(query, value);
    return equalsIgnoreCase(query, value);
  }

  private static boolean equalsIgnoreCase(String a, String b) {
    return b != null && a.equalsIgnoreCase(b);
  }

  private static String text(AuditEvent e) {
    StringBuilder sb = new StringBuilder(e.action()).append(' ').append(e.outcome());
    if (e.actor() != null) sb.append(' ').append(e.actor().username()).append(' ').append(e.actor().dn());
    if (e.target() != null) sb.append(' ').append(e.target().id()).append(' ').append(e.target().name());
    Stream.of(e.sourceAddress(), e.message()).filter(Objects::nonNull).forEach(s -> sb.append(' ').append(s));
    e.details().values().forEach(v -> sb.append(' ').append(v));
    e.changes().forEach(c -> sb.append(' ').append(c.attribute()));
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  // ---------------------------------------------------------------------------
  // Diffs of portal configuration objects
  // ---------------------------------------------------------------------------

  /**
   * Top-level properties that differ between two versions of a configuration object (a form, a
   * definition, a grant). Either side may be null for a create or a delete. Scalars are recorded
   * as text, anything else as compact JSON, truncated.
   */
  public List<AuditEvent.Change> diff(Object before, Object after, Set<String> ignore) {
    Map<String, Object> b = before == null ? Map.of() : asMap(before);
    Map<String, Object> a = after == null ? Map.of() : asMap(after);
    Set<String> keys = new LinkedHashSet<>(b.keySet());
    keys.addAll(a.keySet());
    List<AuditEvent.Change> out = new ArrayList<>();
    for (String k : keys) {
      if (ignore.contains(k) || Objects.equals(b.get(k), a.get(k))) continue;
      out.add(new AuditEvent.Change(k, render(b.get(k)), render(a.get(k))));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    return mapper.convertValue(value, LinkedHashMap.class);
  }

  private List<String> render(Object value) {
    if (value == null) return List.of();
    String s = value instanceof String || value instanceof Number || value instanceof Boolean
        ? String.valueOf(value)
        : mapper.writeValueAsString(value);
    return List.of(s.length() > MAX_VALUE_LENGTH ? s.substring(0, MAX_VALUE_LENGTH) + "…" : s);
  }
}
