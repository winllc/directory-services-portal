package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Searches the audit log of changes and sign-ins, newest first. */
@RestController
@RequestMapping("/api/admin/audit")
public class AdminAuditController {
  private final AuditLog audit;

  public AdminAuditController(AuditLog audit) {
    this.audit = audit;
  }

  @GetMapping
  public AuditLog.Page search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) String definitionId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize) {
    for (String v : new String[] {q, action, outcome, actor, target, definitionId}) {
      if (v != null && v.length() > 1024) throw ApiException.badRequest("Search text is too long");
    }
    if (page != null && (page < 1 || page > 10_000)) throw ApiException.badRequest("Invalid page");
    if (pageSize != null && (pageSize < 1 || pageSize > 200)) throw ApiException.badRequest("Invalid page size");
    return audit.query(new AuditLog.Query(q, action, outcome, actor, target, definitionId,
        instant("from", from, false), instant("to", to, true), page == null ? 1 : page, pageSize == null ? 50 : pageSize));
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
