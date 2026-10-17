package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.web.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Authenticates users against the directory: finds the user's DN with the service account,
 * verifies the password with a bind, then resolves groups and administrator status.
 */
@Service
public class AuthService {
  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final Pattern USERNAME = Pattern.compile("^[\\p{L}\\p{N}._@+-]{1,128}$");
  static final int MAX_FAILURES = 8;
  private static final long FAILURE_WINDOW_MS = 15 * 60 * 1000L;

  private record Failures(int count, long first) {}

  private final LdapDirectory directory;
  private final PortalProperties props;
  private final AuditLog audit;
  private final Map<String, Failures> failures = new ConcurrentHashMap<>();

  public AuthService(LdapDirectory directory, PortalProperties props, AuditLog audit) {
    this.directory = directory;
    this.props = props;
    this.audit = audit;
  }

  /** Substitute placeholders with filter-escaped values and parse. */
  public static Filter template(String template, Map<String, String> values) {
    String f = template;
    for (Map.Entry<String, String> e : values.entrySet()) {
      f = f.replace("{{" + e.getKey() + "}}", Filter.encodeValue(e.getValue()));
    }
    try {
      return Filter.create(f);
    } catch (LDAPException e) {
      throw new IllegalStateException("Invalid filter template: " + template, e);
    }
  }

  public String findUserDn(String username) {
    Filter filter = template(props.getUserFilter(), Map.of("username", username));
    List<DirectoryEntry> found = directory.search(props.effectiveUserSearchBase(), SearchScope.SUB, filter, 2, List.of("1.1")).entries();
    return found.size() == 1 ? found.get(0).dn() : null;
  }

  /**
   * Audited as {@code auth.login} (method password): a failure names the attempted user as an
   * anonymous actor, and the failure that reaches the limit also records {@code auth.lockout}. The
   * success is recorded before the session exists, so a sign-in that can't be audited fails.
   */
  public SessionUser login(String username, String password, String clientKey) {
    // The attempted name is recorded as typed (truncated): it is what an investigation searches for.
    String attempted = username.length() > 128 ? username.substring(0, 128) : username;
    AuditLog.Draft event = audit.event("auth.login").target("user", attempted, null).detail("method", "password");
    if (!props.isPasswordLoginEnabled()) {
      event.anonymous(attempted).denied("Password sign-in is disabled");
      throw ApiException.forbidden("Password sign-in is disabled");
    }
    String throttleKey = clientKey + "|" + username.toLowerCase();
    Failures f = failures.get(throttleKey);
    if (f != null && System.currentTimeMillis() - f.first() < FAILURE_WINDOW_MS && f.count() >= MAX_FAILURES) {
      event.anonymous(attempted).denied("Too many failed attempts");
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
    }
    String dn = USERNAME.matcher(username).matches() && !password.isEmpty() ? findUserDn(username) : null;
    if (dn == null || !directory.authenticate(dn, password)) {
      int count = recordFailure(throttleKey);
      event.anonymous(attempted).failed(dn == null ? "Unknown user" : "Wrong password");
      if (count == MAX_FAILURES) {
        audit.event("auth.lockout").anonymous(attempted).target("user", attempted, null).severity(AuditEvent.ALERT)
            .detail("method", "password").detail("failures", count).detail("windowMinutes", FAILURE_WINDOW_MS / 60_000)
            .denied("Too many failed sign-in attempts: further attempts from this address are refused for 15 minutes");
      }
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    failures.remove(throttleKey);
    SessionUser user = buildUser(dn, username, "password", null);
    event.by(user).target("user", user.dn(), user.displayName()).success();
    return user;
  }

  private int recordFailure(String key) {
    long now = System.currentTimeMillis();
    return failures.compute(key, (k, f) -> f == null || now - f.first() > FAILURE_WINDOW_MS ? new Failures(1, now) : new Failures(f.count() + 1, f.first()))
        .count();
  }

  @Scheduled(fixedDelay = 60_000)
  void sweepFailures() {
    long now = System.currentTimeMillis();
    failures.entrySet().removeIf(e -> now - e.getValue().first() > FAILURE_WINDOW_MS);
  }

  public SessionUser buildUser(String dn, String username, String authMethod, String certificateSubject) {
    String ua = props.getUsernameAttribute();
    DirectoryEntry entry = directory.get(dn, List.of("cn", "displayName", "mail", ua, "memberOf"));
    Set<String> groups = new LinkedHashSet<>(entry == null ? List.of() : entry.values("memberOf"));
    if (props.getGroupFilter() != null && !props.getGroupFilter().isBlank()) {
      try {
        Filter filter = template(props.getGroupFilter(), Map.of("dn", dn, "username", username));
        directory.search(props.effectiveGroupSearchBase(), SearchScope.SUB, filter, 1000, List.of("1.1"))
            .entries()
            .forEach(g -> groups.add(g.dn()));
      } catch (RuntimeException e) {
        log.warn("Group lookup failed: {}", e.getMessage());
      }
    }
    String canonical = entry != null && entry.first(ua) != null ? entry.first(ua) : username;
    boolean isAdmin = props.adminUserList().stream().anyMatch(u -> u.equalsIgnoreCase(canonical))
        || props.adminGroupList().stream().anyMatch(g -> groups.stream().anyMatch(ug -> Dns.equal(ug, g)));
    String displayName = entry == null ? canonical
        : entry.first("displayName") != null ? entry.first("displayName")
        : entry.first("cn") != null ? entry.first("cn") : canonical;
    return new SessionUser(canonical, entry != null ? entry.dn() : dn, displayName, entry == null ? null : entry.first("mail"), List.copyOf(groups), isAdmin,
        authMethod, certificateSubject);
  }
}
