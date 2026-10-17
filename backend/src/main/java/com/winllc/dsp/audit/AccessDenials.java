package com.winllc.dsp.audit;

import com.winllc.dsp.model.SessionUser;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Records {@code access.denied} for every 403 the portal returns: role refused, license
 * ({@code license_required}, {@code license_read_only}, {@code feature_not_licensed}), CSRF and
 * the portal's own permission checks.
 *
 * <p>Identical denials (same actor, path and reason) within 60 seconds are collapsed so a polling
 * client can't flood the log: the first is recorded at once with {@code details.count=1}; repeats
 * in the window are counted and recorded as one event with {@code details.count} (and
 * {@code details.collapsed=true}) when the window closes.
 */
@Component
public class AccessDenials {
  static final Duration WINDOW = Duration.ofSeconds(60);

  private final AuditLog audit;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  /** A denial already recorded, and the repeats since. */
  private static final class Window {
    final Instant start;
    final SessionUser user;
    final String method;
    final String path;
    final String reason;
    final String message;
    final String ip;
    final String userAgent;
    int repeats;
    Instant last;

    Window(Instant start, SessionUser user, String method, String path, String reason, String message, String ip, String userAgent) {
      this.start = start;
      this.user = user;
      this.method = method;
      this.path = path;
      this.reason = reason;
      this.message = message;
      this.ip = ip;
      this.userAgent = userAgent;
      this.last = start;
    }
  }

  public AccessDenials(AuditLog audit) {
    this.audit = audit;
  }

  /**
   * Records (or counts) one 403. {@code reason} is a code: role_required, csrf, license_required,
   * license_read_only, feature_not_licensed, forbidden.
   */
  public void denied(HttpServletRequest request, String reason, String message) {
    SessionUser user = currentUser();
    String actorKey = user != null ? "user:" + user.username() : "anon:" + request.getRemoteAddr();
    String path = request.getRequestURI();
    String key = actorKey + "|" + path + "|" + reason;
    Instant now = Instant.now();
    Window[] flush = {null};
    boolean[] fresh = {false};
    windows.compute(key, (k, w) -> {
      if (w != null && Duration.between(w.start, now).compareTo(WINDOW) < 0) {
        w.repeats++;
        w.last = now;
        return w;
      }
      if (w != null && w.repeats > 0) flush[0] = w;
      fresh[0] = true;
      return new Window(now, user, request.getMethod(), path, reason, message, request.getRemoteAddr(), request.getHeader("User-Agent"));
    });
    if (flush[0] != null) recordRepeats(flush[0]);
    if (fresh[0]) {
      AuditLog.Draft d = audit.event("access.denied").detail("method", request.getMethod()).detail("path", path)
          .detail("reasonCode", reason).detail("count", 1);
      if (user != null) d.by(user);
      d.denied(message);
    }
  }

  /** Records the repeats of windows that have closed. */
  @Scheduled(fixedDelay = 10_000)
  public void flush() {
    Instant now = Instant.now();
    List<Window> closed = new ArrayList<>();
    windows.entrySet().removeIf(e -> {
      boolean done = Duration.between(e.getValue().start, now).compareTo(WINDOW) >= 0;
      if (done && e.getValue().repeats > 0) closed.add(e.getValue());
      return done;
    });
    closed.forEach(this::recordRepeats);
  }

  /** Records the repeats of every open window now (at shutdown, so counts aren't lost). */
  @PreDestroy
  public void closeWindows() {
    List<Window> open = new ArrayList<>();
    windows.entrySet().removeIf(e -> {
      if (e.getValue().repeats > 0) open.add(e.getValue());
      return true;
    });
    open.forEach(this::recordRepeats);
  }

  private void recordRepeats(Window w) {
    AuditLog.Draft d = audit.event("access.denied").source(w.ip, w.userAgent).detail("method", w.method).detail("path", w.path)
        .detail("reasonCode", w.reason).detail("count", w.repeats).detail("collapsed", true)
        .detail("firstAt", AuditLog.TIME.format(w.start)).detail("lastAt", AuditLog.TIME.format(w.last));
    if (w.user != null) d.by(w.user);
    else d.anonymous(null);
    d.denied(w.message);
  }

  private static SessionUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
  }
}
