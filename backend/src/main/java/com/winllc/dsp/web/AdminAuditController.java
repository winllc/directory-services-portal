package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditCatalog;
import com.winllc.dsp.audit.AuditDelivery;
import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditProperties;
import com.winllc.dsp.audit.AuditSearch;
import com.winllc.dsp.audit.AuditStore;
import com.winllc.dsp.audit.AuditTokens;
import com.winllc.dsp.model.SessionUser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The audit log for administrators: search (newest first, all retained history), export (CSV,
 * NDJSON), integrity verification, delivery status and the SIEM pull API's tokens. Reachable
 * whatever the license says (the audit trail is not a licensed feature).
 */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {
  private final AuditLog audit;
  private final AuditSearch search;
  private final AuditDelivery delivery;
  private final AuditTokens tokens;
  private final AuditProperties props;

  public AdminAuditController(AuditLog audit, AuditSearch search, AuditDelivery delivery, AuditTokens tokens, AuditProperties props) {
    this.audit = audit;
    this.search = search;
    this.delivery = delivery;
    this.tokens = tokens;
    this.props = props;
  }

  @GetMapping
  public AuditSearch.Page search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) String ip,
      @RequestParam(required = false) String definitionId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize) {
    AuditSearch.Query query = query(q, category, action, outcome, severity, actor, target, ip, definitionId, from, to);
    if (page != null && (page < 1 || page > 100_000)) throw ApiException.badRequest("Invalid page");
    if (pageSize != null && (pageSize < 1 || pageSize > 200)) throw ApiException.badRequest("Invalid page size");
    return search.page(query, page == null ? 1 : page, pageSize == null ? 50 : pageSize);
  }

  /** The filter choices: categories, outcomes, severities and the known actions. */
  @GetMapping("/actions")
  public Map<String, Object> actions() {
    List<Map<String, String>> actions = new ArrayList<>();
    AuditCatalog.actions().forEach((a, c) -> actions.add(Map.of("action", a, "category", c)));
    return Map.of("categories", AuditCatalog.CATEGORIES, "outcomes", AuditCatalog.OUTCOMES, "severities", AuditCatalog.SEVERITIES,
        "actions", actions);
  }

  /** The current filter's events (up to 100,000), oldest first, as CSV or NDJSON. Audited as audit.exported. */
  @GetMapping("/export")
  public void export(
      @RequestParam(defaultValue = "csv") String format,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) String ip,
      @RequestParam(required = false) String definitionId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletResponse response) throws IOException {
    if (!format.equals("csv") && !format.equals("ndjson")) throw ApiException.badRequest("Use format=csv or format=ndjson");
    AuditSearch.Query query = query(q, category, action, outcome, severity, actor, target, ip, definitionId, from, to);
    AuditSearch.Matches m = search.matches(query, AuditSearch.MAX_EXPORT);
    AuditLog.Draft event = audit.event("audit.exported").target("audit-log", audit.product(), "Audit log")
        .detail("format", format).detail("count", m.events().size()).detail("limited", m.limited());
    filterDetails(query).forEach(event::detail);
    event.success();

    String stamp = Instant.now().toString().substring(0, 10);
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Content-Disposition", "attachment; filename=\"audit-" + stamp + "." + format + "\"");
    response.setHeader("X-Audit-Count", String.valueOf(m.events().size()));
    if (m.limited()) response.setHeader("X-Audit-Limited", "true");
    response.setContentType(format.equals("csv") ? "text/csv" : "application/x-ndjson");
    Writer w = response.getWriter();
    if (format.equals("ndjson")) {
      for (AuditStore.Stored s : m.events()) w.write(s.line() + "\n");
    } else {
      w.write(String.join(",", CSV_COLUMNS) + "\r\n");
      for (AuditStore.Stored s : m.events()) {
        JsonNode n = search.parse(s.line());
        if (n != null) w.write(csvRow(n) + "\r\n");
      }
    }
    w.flush();
  }

  static final List<String> CSV_COLUMNS = List.of("seq", "time", "category", "action", "outcome", "severity", "actor_type",
      "actor_id", "actor_name", "source_ip", "user_agent", "target_type", "target_id", "target_name", "reason", "details",
      "request_id", "session", "id", "hash");

  private static String csvRow(JsonNode n) {
    JsonNode a = n.get("actor");
    JsonNode s = n.get("source");
    JsonNode t = n.get("target");
    List<String> cells = List.of(text(n, "seq"), text(n, "time"), text(n, "category"), text(n, "action"), text(n, "outcome"),
        text(n, "severity"), text(a, "type"), text(a, "id"), text(a, "name"), text(s, "ip"), text(s, "userAgent"), text(t, "type"),
        text(t, "id"), text(t, "name"), text(n, "reason"), n.get("details") == null ? "" : n.get("details").toString(),
        text(n, "requestId"), text(n, "session"), text(n, "id"), text(n, "hash"));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(csvCell(cells.get(i)));
    }
    return sb.toString();
  }

  /** RFC 4180 quoting; cells that a spreadsheet would run as a formula are prefixed with '. */
  static String csvCell(String v) {
    if (v == null || v.isEmpty()) return "";
    if ("=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) v = "'" + v;
    if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) return "\"" + v.replace("\"", "\"\"") + "\"";
    return v;
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v == null || v.isNull() ? "" : v.asString();
  }

  /** Walks the chain and records audit.verified (severity alert when it is broken). */
  @PostMapping("/verify")
  public Map<String, Object> verify() {
    AuditStore.Verification v = audit.store().verify();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("intact", v.intact());
    body.put("brokenSeq", v.brokenSeq());
    body.put("problem", v.problem());
    body.put("anchorSeq", v.anchorSeq());
    body.put("anchorIsGenesis", v.anchorIsGenesis());
    body.put("lastSeq", v.lastSeq());
    body.put("checked", v.checked());
    body.put("unreadableLines", v.unreadableLines());
    body.put("message", message(v));
    AuditLog.Draft event = audit.event("audit.verified").target("audit-log", audit.product(), "Audit log")
        .detail("intact", v.intact()).detail("brokenSeq", v.brokenSeq()).detail("anchorSeq", v.anchorSeq())
        .detail("lastSeq", v.lastSeq()).detail("checked", v.checked());
    if (v.intact()) event.success();
    else event.severity(AuditEvent.ALERT).failed(message(v));
    body.put("verifiedAt", Instant.now().toString());
    return body;
  }

  static String message(AuditStore.Verification v) {
    if (v.anchorSeq() == null) return "The audit log is empty.";
    String anchor = v.anchorIsGenesis() ? "from seq 1"
        : "from seq " + v.anchorSeq() + " (anchor: older events were pruned, so its prevHash is accepted as given)";
    if (!v.intact()) return "Broken at seq " + v.brokenSeq() + ": " + v.problem() + ". Checked " + anchor + ".";
    return "Intact: " + v.checked() + " event(s) " + anchor + " to seq " + v.lastSeq() + ".";
  }

  /** One row per output, the retention setting and the chain's extent. */
  @GetMapping("/status")
  public Map<String, Object> status() {
    AuditStore store = audit.store();
    AuditStore.Stored first = store.first();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("outputs", delivery.status());
    body.put("retentionDays", props.getRetentionDays());
    body.put("headSeq", store.headSeq());
    body.put("oldestSeq", first == null ? null : first.seq());
    body.put("oldestTime", first == null ? null : first.time());
    body.put("store", store.dir().toString());
    body.put("writer", "single synchronized writer (one portal instance per data directory)");
    return body;
  }

  // ---------------------------------------------------------------------------
  // API tokens for the pull API
  // ---------------------------------------------------------------------------

  @GetMapping("/tokens")
  public List<AuditTokens.View> listTokens() {
    return tokens.list();
  }

  public record TokenRequest(String name) {}

  /** Creates a token; the response is the only time its value is shown. */
  @PostMapping("/tokens")
  public ResponseEntity<AuditTokens.Created> createToken(@AuthenticationPrincipal SessionUser user, @RequestBody TokenRequest body)
      throws IOException {
    String name = body == null || body.name() == null ? "" : body.name().trim();
    if (name.isEmpty() || name.length() > 100) {
      throw ApiException.badRequest("Invalid request", Map.of("name", "Give the token a name of at most 100 characters"));
    }
    audit.requireWritable();
    AuditTokens.Created created = tokens.create(name, user.username());
    audit.event("audit.token-created").target("audit-token", created.token().id(), name)
        .detail("prefix", created.token().prefix()).detail("scope", "audit:read").success();
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @DeleteMapping("/tokens/{id}")
  public AuditTokens.View revokeToken(@AuthenticationPrincipal SessionUser user, @PathVariable String id) throws IOException {
    audit.requireWritable();
    AuditTokens.View revoked = tokens.revoke(id, user.username());
    if (revoked == null) throw ApiException.notFound("No such token");
    audit.event("audit.token-revoked").target("audit-token", revoked.id(), revoked.name()).detail("prefix", revoked.prefix()).success();
    return revoked;
  }

  // ---------------------------------------------------------------------------

  private static AuditSearch.Query query(String q, String category, String action, String outcome, String severity, String actor,
      String target, String ip, String definitionId, String from, String to) {
    for (String v : new String[] {q, category, action, outcome, severity, actor, target, ip, definitionId}) {
      if (v != null && v.length() > 1024) throw ApiException.badRequest("Search text is too long");
    }
    return new AuditSearch.Query(instant("from", from, false), instant("to", to, true), category, action, outcome, severity,
        actor, target, ip, q, definitionId);
  }

  private static Map<String, Object> filterDetails(AuditSearch.Query q) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (q.from() != null) m.put("filter.from", q.from().toString());
    if (q.to() != null) m.put("filter.to", q.to().toString());
    put(m, "filter.category", q.category());
    put(m, "filter.action", q.action());
    put(m, "filter.outcome", q.outcome());
    put(m, "filter.severity", q.severity());
    put(m, "filter.actor", q.actor());
    put(m, "filter.target", q.target());
    put(m, "filter.ip", q.ip());
    put(m, "filter.q", q.q());
    put(m, "filter.definitionId", q.definitionId());
    return m;
  }

  private static void put(Map<String, Object> m, String k, String v) {
    if (v != null && !v.isBlank()) m.put(k, v);
  }

  /** An ISO instant, or a date: the start of that day (UTC), or for {@code to} the end of it. */
  private static Instant instant(String name, String value, boolean endOfDay) {
    if (value == null || value.isBlank()) return null;
    try {
      if (value.length() == 10) {
        LocalDate day = LocalDate.parse(value);
        return (endOfDay ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
      }
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("Invalid date", Map.of(name, "Use YYYY-MM-DD or an ISO timestamp"));
    }
  }
}
