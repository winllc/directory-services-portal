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
 * {@code details.collapsed=true}) when the window closes. At most {@value #MAX_WINDOWS} windows are
 * open; beyond that a new denial is collapsed per actor and reason, whatever its path, so a client
 * walking distinct forbidden paths cannot grow the map without bound.
 *
 * <p>The same collapsing records a wrong or revoked token on the SIEM pull API as a failed
 * {@code auth.login} (method {@code api-token}), per source address.
 */
@Component
public class AccessDenials {
  static final Duration WINDOW = Duration.ofSeconds(60);
  /** Most windows open at once. */
  static final int MAX_WINDOWS = 10_000;
  int maxWindows = MAX_WINDOWS;

  private final AuditLog audit;
  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  /** A denial already recorded, and the repeats since. */
  private static final class Window {
    final String action;
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

    boolean anyPath;

    Window(String action, Instant start, SessionUser user, String method, String path, String reason, String message, String ip,
        String userAgent) {
      this.action = action;
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
    String actorKey = user != null ? "user:" + user.key() : "anon:" + request.getRemoteAddr();
    String path = request.getRequestURI();
    collapse(actorKey + "|" + path + "|" + reason, actorKey + "|*|" + reason,
        new Window("access.denied", Instant.now(), user, request.getMethod(), path, reason, message, request.getRemoteAddr(),
            request.getHeader("User-Agent")));
  }

  /**
   * A wrong or revoked token presented to the SIEM pull API: a failed sign-in, collapsed per source
   * address. The token itself is never recorded.
   */
  public void tokenRefused(HttpServletRequest request) {
    String key = "token|" + request.getRemoteAddr();
    collapse(key, key, new Window("auth.login", Instant.now(), null, request.getMethod(), request.getRequestURI(),
        "invalid or revoked token", null, request.getRemoteAddr(), request.getHeader("User-Agent")));
  }

  /**
   * Records {@code fresh} at once, unless a window with the same key is open, which then counts it.
   * With {@link #MAX_WINDOWS} open, a new window takes {@code whenFull} as its key instead.
   */
  private void collapse(String key, String whenFull, Window fresh) {
    String k = windows.size() >= maxWindows && !windows.containsKey(key) ? whenFull : key;
    fresh.anyPath = !k.equals(key);
    Instant now = fresh.start;
    Window[] flush = {null};
    boolean[] opened = {false};
    windows.compute(k, (ignored, w) -> {
      if (w != null && Duration.between(w.start, now).compareTo(WINDOW) < 0) {
        w.repeats++;
        w.last = now;
        return w;
      }
      if (w != null && w.repeats > 0) flush[0] = w;
      opened[0] = true;
      return fresh;
    });
    if (flush[0] != null) recordRepeats(flush[0]);
    if (opened[0]) recordFirst(fresh);
  }

  private void recordFirst(Window w) {
    if (w.action.equals("auth.login")) {
      audit.event("auth.login").anonymous(null).detail("method", "api-token").detail("path", w.path).detail("count", 1)
          .failed(w.reason);
      return;
    }
    AuditLog.Draft d = audit.event("access.denied").detail("method", w.method).detail("path", w.path)
        .detail("reasonCode", w.reason).detail("count", 1);
    if (w.anyPath) d.detail("anyPath", true);
    if (w.user != null) d.by(w.user);
    d.denied(w.message);
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
    if (w.action.equals("auth.login")) {
      audit.event("auth.login").source(w.ip, w.userAgent).anonymous(null).detail("method", "api-token").detail("path", w.path)
          .detail("count", w.repeats).detail("collapsed", true).detail("firstAt", AuditLog.TIME.format(w.start))
          .detail("lastAt", AuditLog.TIME.format(w.last)).failed(w.reason);
      return;
    }
    AuditLog.Draft d = audit.event("access.denied").source(w.ip, w.userAgent).detail("method", w.method).detail("path", w.path)
        .detail("reasonCode", w.reason).detail("count", w.repeats).detail("collapsed", true)
        .detail("firstAt", AuditLog.TIME.format(w.start)).detail("lastAt", AuditLog.TIME.format(w.last));
    if (w.anyPath) d.detail("anyPath", true);
    if (w.user != null) d.by(w.user);
    else d.anonymous(null);
    d.denied(w.message);
  }

  /** How many windows are open (tests). */
  int openWindows() {
    return windows.size();
  }

  private static SessionUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
  }
}
