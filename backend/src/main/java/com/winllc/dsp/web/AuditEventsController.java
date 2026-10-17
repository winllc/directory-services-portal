package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditStore;
import com.winllc.dsp.audit.AuditTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SIEM pull API: {@code GET /api/audit/events?after=<seq>&limit=<1..1000>} with
 * {@code Authorization: Bearer ensat_…}. Returns the stored lines after the cursor in seq order, as
 * NDJSON by default or {@code {"events":[…],"next":<seq>}} for {@code Accept: application/json}
 * (the lines are embedded verbatim, so their hashes can be checked).
 *
 * <p>No session, no CSRF header and no license needed (its own security filter chain, outside the
 * license interceptor): the audit trail stays readable while the portal is unlicensed or read-only.
 * Pulls are not audited (that would loop); the token's last use is recorded instead.
 */
@RestController
public class AuditEventsController {
  private final AuditLog audit;
  private final AuditTokens tokens;

  public AuditEventsController(AuditLog audit, AuditTokens tokens) {
    this.audit = audit;
    this.tokens = tokens;
  }

  @GetMapping("/api/audit/events")
  public void events(@RequestParam(required = false) String after, @RequestParam(required = false) String limit,
      HttpServletRequest request, HttpServletResponse response) throws IOException {
    String auth = request.getHeader("Authorization");
    String presented = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : null;
    if (tokens.authenticate(presented) == null) {
      response.setHeader("WWW-Authenticate", "Bearer realm=\"ens-audit\"");
      throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "A valid audit API token is required");
    }
    long afterSeq = parse(after, 0, 0, Long.MAX_VALUE, "after");
    int max = (int) parse(limit, 500, 1, 1000, "limit");
    List<AuditStore.Stored> events = audit.store().readAfter(afterSeq, max);
    long next = events.isEmpty() ? afterSeq : events.get(events.size() - 1).seq();
    String accept = request.getHeader("Accept");
    boolean json = accept != null && accept.contains("application/json") && !accept.contains("application/x-ndjson");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Audit-Next", String.valueOf(next));
    Writer w = response.getWriter();
    if (json) {
      response.setContentType("application/json");
      w.write("{\"events\":[");
      for (int i = 0; i < events.size(); i++) {
        if (i > 0) w.write(',');
        w.write(events.get(i).line());
      }
      w.write("],\"next\":" + next + "}");
    } else {
      response.setContentType("application/x-ndjson");
      for (AuditStore.Stored s : events) w.write(s.line() + "\n");
    }
    w.flush();
  }

  private static long parse(String value, long dflt, long min, long max, String name) {
    if (value == null || value.isBlank()) return dflt;
    try {
      long v = Long.parseLong(value.trim());
      if (v < min || v > max) throw ApiException.badRequest("Invalid " + name);
      return v;
    } catch (NumberFormatException e) {
      throw ApiException.badRequest("Invalid " + name);
    }
  }
}
