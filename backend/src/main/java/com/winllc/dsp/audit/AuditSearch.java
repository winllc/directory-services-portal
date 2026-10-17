package com.winllc.dsp.audit;

import com.winllc.dsp.ldap.Dns;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searches all retained history in the store, newest first. A search reads the files (skipping
 * months before {@code from}) and stops after {@code ens.audit.query-max-scan} lines, saying so.
 */
@Component
public class AuditSearch {
  public static final int MAX_EXPORT = 100_000;

  /**
   * Filters, all optional. {@code action} matches exactly or as a prefix ("entry" matches
   * "entry.update"); {@code actor} and {@code target} match as case-insensitive text, or a DN by DN
   * equality, and a target DN also finds the entry under its earlier and later names (renames);
   * {@code ip} matches the start of the source address; {@code q} searches the whole JSON line.
   */
  public record Query(Instant from, Instant to, String category, String action, String outcome, String severity,
      String actor, String target, String ip, String q, String definitionId) {

    public static Query none() {
      return new Query(null, null, null, null, null, null, null, null, null, null, null);
    }
  }

  public record Page(List<JsonNode> events, long total, int page, int pageSize, long scanned, boolean truncated,
      long headSeq, Long oldestSeq, String oldestTime) {}

  public record Matches(List<AuditStore.Stored> events, boolean truncated, boolean limited) {}

  private final AuditLog audit;
  private final AuditProperties props;
  private final JsonMapper mapper;

  public AuditSearch(AuditLog audit, AuditProperties props, JsonMapper mapper) {
    this.audit = audit;
    this.props = props;
    this.mapper = mapper;
  }

  public Page page(Query query, int page, int pageSize) {
    Predicate<JsonNode> match = matcher(query);
    String needle = lower(query.q());
    int skip = (page - 1) * pageSize;
    List<JsonNode> out = new ArrayList<>();
    long[] total = {0};
    long[] scanned = {0};
    boolean[] truncated = {false};
    int max = Math.max(props.getQueryMaxScan(), 1);
    audit.store().scanNewestFirst(monthFilter(query), s -> {
      if (++scanned[0] > max) {
        truncated[0] = true;
        scanned[0]--;
        return false;
      }
      if (needle != null && !s.line().toLowerCase(Locale.ROOT).contains(needle)) return true;
      JsonNode n = parse(s.line());
      if (n == null || !match.test(n)) return true;
      total[0]++;
      if (total[0] > skip && out.size() < pageSize) out.add(n);
      return true;
    });
    AuditStore.Stored first = audit.store().first();
    return new Page(out, total[0], page, pageSize, scanned[0], truncated[0], audit.store().headSeq(),
        first == null ? null : first.seq(), first == null ? null : first.time());
  }

  /** Up to {@code limit} matching events, oldest first (for export). */
  public Matches matches(Query query, int limit) {
    Predicate<JsonNode> match = matcher(query);
    String needle = lower(query.q());
    List<AuditStore.Stored> out = new ArrayList<>();
    long[] scanned = {0};
    boolean[] truncated = {false};
    boolean[] limited = {false};
    int max = Math.max(props.getQueryMaxScan(), 1);
    audit.store().scanNewestFirst(monthFilter(query), s -> {
      if (++scanned[0] > max) {
        truncated[0] = true;
        return false;
      }
      if (needle != null && !s.line().toLowerCase(Locale.ROOT).contains(needle)) return true;
      JsonNode n = parse(s.line());
      if (n == null || !match.test(n)) return true;
      if (out.size() >= limit) {
        limited[0] = true;
        return false;
      }
      out.add(s);
      return true;
    });
    Collections.reverse(out);
    return new Matches(out, truncated[0], limited[0]);
  }

  public JsonNode parse(String line) {
    try {
      return mapper.readTree(line);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static Predicate<YearMonth> monthFilter(Query q) {
    if (q.from() == null) return null;
    YearMonth from = YearMonth.from(q.from().atZone(ZoneOffset.UTC));
    // A file holds its month's events and possibly earlier ones (never later), so files before
    // the from month can be skipped.
    return m -> m == null || !m.isBefore(from);
  }

  private Predicate<JsonNode> matcher(Query q) {
    Predicate<JsonNode> p = n -> true;
    if (q.from() != null) p = p.and(n -> !time(n).isBefore(q.from()));
    if (q.to() != null) p = p.and(n -> time(n).isBefore(q.to()));
    if (notBlank(q.category())) p = p.and(n -> q.category().trim().equals(text(n, "category")));
    if (notBlank(q.action())) {
      String a = q.action().trim();
      p = p.and(n -> {
        String action = text(n, "action");
        return action != null && (action.equals(a) || action.startsWith(a + "."));
      });
    }
    if (notBlank(q.outcome())) {
      String o = "failed".equals(q.outcome().trim()) ? AuditEvent.FAILURE : q.outcome().trim();
      p = p.and(n -> o.equals(text(n, "outcome")));
    }
    if (notBlank(q.severity())) p = p.and(n -> q.severity().trim().equals(text(n, "severity")));
    if (notBlank(q.definitionId())) p = p.and(n -> q.definitionId().trim().equals(text(n.get("details"), "definitionId")));
    if (notBlank(q.ip())) {
      String ip = q.ip().trim();
      p = p.and(n -> {
        String v = text(n.get("source"), "ip");
        return v != null && v.startsWith(ip);
      });
    }
    if (notBlank(q.actor())) {
      String a = q.actor().trim();
      p = p.and(n -> {
        JsonNode actor = n.get("actor");
        String dn = text(n.get("details"), "actorDn");
        if (isDn(a)) return dn != null && sameDn(a, dn);
        return contains(text(actor, "id"), a) || contains(text(actor, "name"), a) || contains(dn, a);
      });
    }
    if (notBlank(q.target())) {
      String t = q.target().trim();
      if (isDn(t)) {
        List<String> names = namesOf(t);
        p = p.and(n -> {
          String id = text(n.get("target"), "id");
          return id != null && names.stream().anyMatch(name -> sameDn(name, id));
        });
      } else {
        p = p.and(n -> contains(text(n.get("target"), "id"), t) || contains(text(n.get("target"), "name"), t));
      }
    }
    return p;
  }

  /**
   * Every DN a target has had, following recorded renames ({@code details.previousDn}) both ways,
   * so an entry's history includes what happened to it under earlier and later names.
   */
  private List<String> namesOf(String target) {
    List<String[]> renames = new ArrayList<>();
    audit.store().scanNewestFirst(null, s -> {
      if (!s.line().contains("\"previousDn\":")) return true;
      JsonNode n = parse(s.line());
      String from = n == null ? null : text(n.get("details"), "previousDn");
      String to = n == null ? null : text(n.get("target"), "id");
      if (from != null && to != null) renames.add(new String[] {from, to});
      return true;
    });
    List<String> names = new ArrayList<>(List.of(target));
    boolean grew = true;
    while (grew) {
      grew = false;
      for (String[] r : renames) {
        boolean knowsFrom = names.stream().anyMatch(x -> sameDn(x, r[0]));
        boolean knowsTo = names.stream().anyMatch(x -> sameDn(x, r[1]));
        if (knowsFrom != knowsTo) {
          names.add(knowsFrom ? r[1] : r[0]);
          grew = true;
        }
      }
    }
    return names;
  }

  private static Instant time(JsonNode n) {
    try {
      return Instant.parse(text(n, "time"));
    } catch (RuntimeException e) {
      return Instant.EPOCH;
    }
  }

  private static boolean isDn(String s) {
    return s.contains("=") && Dns.isValid(s);
  }

  private static boolean sameDn(String a, String b) {
    return Dns.isValid(b) ? Dns.equal(a, b) : a.equalsIgnoreCase(b);
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  static String text(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  private static String lower(String s) {
    return notBlank(s) ? s.trim().toLowerCase(Locale.ROOT) : null;
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
