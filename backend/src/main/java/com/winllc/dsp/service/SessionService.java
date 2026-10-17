package com.winllc.dsp.service;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditUnavailableException;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.model.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * Session controls (ENS platform baseline): an idle timeout (shorter for administrators), an
 * absolute lifetime, a limit on concurrent sessions per user, and groups and administrator status
 * read again from the directory every {@code GROUP_REFRESH_INTERVAL}.
 *
 * <p>Idle time counts user activity only: requests the web client marks as background polling
 * ({@value #BACKGROUND_HEADER}) keep the container session alive but are not activity, so an open
 * but unattended tab still times out. The container's own timeout (the same idle timeout) ends
 * sessions whose tab was closed. Every expiry is audited as {@code auth.session-expired} with the
 * reason {@code idle}, {@code absolute}, {@code concurrent} or {@code account-removed}.
 */
@Service
public class SessionService implements HttpSessionListener {
  private static final Logger log = LoggerFactory.getLogger(SessionService.class);
  /** Sent by the web client on polling requests, which are not user activity. */
  public static final String BACKGROUND_HEADER = "X-DSP-Background";
  public static final String SIGNED_IN_AT = SessionService.class.getName() + ".signedInAt";
  public static final String LAST_ACTIVITY = SessionService.class.getName() + ".lastActivity";
  public static final String GROUPS_RESOLVED_AT = SessionService.class.getName() + ".groupsResolvedAt";
  /** Set before the portal itself ends a session (sign-out or expiry), so the listener doesn't record it again. */
  static final String ENDED = SessionService.class.getName() + ".ended";
  private static final String KEY = SessionService.class.getName() + ".key";
  private static final String OWNER = SessionService.class.getName() + ".owner";

  private final EnsProperties ens;
  private final PortalProperties props;
  private final AuthService auth;
  private final AuditLog audit;
  private final SecurityContextRepository contexts;
  /** Signed-in sessions per user, oldest first. */
  private final Map<String, LinkedHashMap<String, HttpSession>> byUser = new ConcurrentHashMap<>();

  public SessionService(EnsProperties ens, PortalProperties props, AuthService auth, AuditLog audit, SecurityContextRepository contexts) {
    this.ens = ens;
    this.props = props;
    this.auth = auth;
    this.audit = audit;
    this.contexts = contexts;
  }

  /** A session that is no longer valid, and why. */
  public record Expired(String reason) {
    public String message() {
      return switch (reason) {
        case "idle" -> "Your session timed out after a period of inactivity. Sign in again.";
        case "absolute" -> "Your session has reached its maximum length. Sign in again.";
        case "concurrent" -> "You signed in elsewhere, which ended this session. Sign in again.";
        default -> "Your session has ended. Sign in again.";
      };
    }
  }

  /** Called once the session for a new sign-in exists (after its id was changed). */
  public void signedIn(HttpServletRequest request, SessionUser user) {
    HttpSession session = request.getSession(true);
    long now = System.currentTimeMillis();
    session.setAttribute(SIGNED_IN_AT, now);
    session.setAttribute(LAST_ACTIVITY, now);
    session.setAttribute(GROUPS_RESOLVED_AT, now);
    session.setMaxInactiveInterval(seconds(ens.getSession().idleTimeoutFor(user.isAdmin())));
    register(session, user);
  }

  /** Before sign-out invalidates the session. */
  public void signingOut(HttpSession session) {
    try {
      session.setAttribute(ENDED, "logout");
    } catch (IllegalStateException e) {
      // Already invalid.
    }
  }

  /**
   * Applies the session controls to a request of a signed-in user: ends the session when it is past
   * its absolute lifetime or idle timeout (or the account is gone), reads the user's groups again
   * when due, and counts the request as activity unless it is background polling.
   *
   * @return null to carry on, or why the session was ended
   */
  public Expired onRequest(HttpServletRequest request, HttpServletResponse response, HttpSession session, SessionUser user) {
    long now = System.currentTimeMillis();
    Long signedInAt = attribute(session, SIGNED_IN_AT);
    if (signedInAt == null) {
      // A session from before these controls (or signed in some other way): start counting now.
      signedIn(request, user);
      return null;
    }
    Duration absolute = ens.getSession().getAbsoluteTimeout();
    if (absolute != null && !absolute.isZero() && now - signedInAt >= absolute.toMillis()) return expire(session, user, "absolute");
    Long lastActivity = attribute(session, LAST_ACTIVITY);
    Duration idle = ens.getSession().idleTimeoutFor(user.isAdmin());
    if (lastActivity != null && idle != null && !idle.isZero() && now - lastActivity >= idle.toMillis()) return expire(session, user, "idle");

    Duration refresh = props.getGroupRefreshInterval();
    Long resolvedAt = attribute(session, GROUPS_RESOLVED_AT);
    if (refresh != null && !refresh.isZero() && resolvedAt != null && now - resolvedAt >= refresh.toMillis()) {
      session.setAttribute(GROUPS_RESOLVED_AT, now);
      SessionUser fresh;
      try {
        fresh = auth.refresh(user);
      } catch (RuntimeException e) {
        // Directory unavailable: keep what the session has and try again after the next interval.
        log.warn("Could not read the groups of {} again ({}); keeping those from sign-in", user.username(), e.getMessage());
        fresh = user;
      }
      if (fresh == null) return expire(session, user, "account-removed");
      if (!fresh.equals(user)) {
        if (fresh.isAdmin() != user.isAdmin()) {
          log.info("{} is {} an administrator", user.username(), fresh.isAdmin() ? "now" : "no longer");
          session.setMaxInactiveInterval(seconds(ens.getSession().idleTimeoutFor(fresh.isAdmin())));
        }
        SecurityContext context = context(fresh);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
      }
    }
    if (!"1".equals(request.getHeader(BACKGROUND_HEADER))) session.setAttribute(LAST_ACTIVITY, now);
    return null;
  }

  /** The security context of a signed-in user: ROLE_USER, plus ROLE_ADMIN for administrators. */
  public static SecurityContext context(SessionUser user) {
    List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    if (user.isAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
    return context;
  }

  /** Ends a session, recording why. The session ends even when the event can't be audited. */
  Expired expire(HttpSession session, SessionUser user, String reason) {
    try {
      session.setAttribute(ENDED, reason);
    } catch (IllegalStateException e) {
      return new Expired(reason);
    }
    recordExpiry(user, reason);
    try {
      session.invalidate();
    } catch (IllegalStateException e) {
      // Already invalid.
    }
    return new Expired(reason);
  }

  private void recordExpiry(SessionUser user, String reason) {
    if (user == null) return;
    try {
      audit.event("auth.session-expired").by(user).target("user", user.dn(), user.displayName()).reason(reason).success();
    } catch (AuditUnavailableException e) {
      log.error("Session of {} ended ({}) but could not be audited: {}", user.username(), reason, e.getMessage());
    }
  }

  private void register(HttpSession session, SessionUser user) {
    unregister(session);
    String owner = user.dn().toLowerCase(Locale.ROOT);
    String key = UUID.randomUUID().toString();
    session.setAttribute(KEY, key);
    session.setAttribute(OWNER, owner);
    List<Map.Entry<HttpSession, SessionUser>> evicted = new ArrayList<>();
    LinkedHashMap<String, HttpSession> sessions = byUser.computeIfAbsent(owner, k -> new LinkedHashMap<>());
    synchronized (sessions) {
      sessions.put(key, session);
      int max = ens.getSession().getMaxPerUser();
      while (max > 0 && sessions.size() > max) {
        String oldest = sessions.keySet().iterator().next();
        HttpSession old = sessions.remove(oldest);
        evicted.add(Map.entry(old, user));
      }
    }
    // Outside the lock: invalidating calls sessionDestroyed, which takes it again.
    for (Map.Entry<HttpSession, SessionUser> e : evicted) {
      SessionUser previous = userOf(e.getKey());
      expire(e.getKey(), previous != null ? previous : e.getValue(), "concurrent");
    }
  }

  private void unregister(HttpSession session) {
    String owner;
    String key;
    try {
      owner = (String) session.getAttribute(OWNER);
      key = (String) session.getAttribute(KEY);
    } catch (IllegalStateException e) {
      return;
    }
    if (owner == null || key == null) return;
    LinkedHashMap<String, HttpSession> sessions = byUser.get(owner);
    if (sessions == null) return;
    synchronized (sessions) {
      sessions.remove(key);
      if (sessions.isEmpty()) byUser.remove(owner, sessions);
    }
  }

  /** Signed-in sessions of a user (for tests and diagnostics). */
  public int sessionsOf(SessionUser user) {
    LinkedHashMap<String, HttpSession> sessions = byUser.get(user.dn().toLowerCase(Locale.ROOT));
    if (sessions == null) return 0;
    synchronized (sessions) {
      return sessions.size();
    }
  }

  /**
   * The container ended a session. One the portal did not end itself, of a signed-in user, that
   * had been idle for its whole timeout, timed out; sessions ended at shutdown are not recorded.
   */
  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    HttpSession session = event.getSession();
    unregister(session);
    try {
      if (session.getAttribute(ENDED) != null) return;
      SessionUser user = userOf(session);
      int timeout = session.getMaxInactiveInterval();
      if (user == null || timeout <= 0) return;
      if (System.currentTimeMillis() - session.getLastAccessedTime() >= timeout * 1000L - 1000) recordExpiry(user, "idle");
    } catch (IllegalStateException e) {
      // Attributes already gone.
    }
  }

  static SessionUser userOf(HttpSession session) {
    try {
      Object ctx = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      return ctx instanceof SecurityContext c && c.getAuthentication() != null && c.getAuthentication().getPrincipal() instanceof SessionUser u
          ? u : null;
    } catch (IllegalStateException e) {
      return null;
    }
  }

  private static Long attribute(HttpSession session, String name) {
    return session.getAttribute(name) instanceof Long l ? l : null;
  }

  private static int seconds(Duration d) {
    return d == null || d.isZero() ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.max(1, d.toSeconds()));
  }
}
