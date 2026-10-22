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

  private final LdapDirectory directory;
  private final PortalProperties props;
  private final AuditLog audit;
  private final LoginThrottle throttle;

  public AuthService(LdapDirectory directory, PortalProperties props, AuditLog audit, LoginThrottle throttle) {
    this.directory = directory;
    this.props = props;
    this.audit = audit;
    this.throttle = throttle;
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
   * anonymous actor, and the failure that reaches the limit ({@code LOGIN_MAX_FAILURES} within
   * {@code LOGIN_LOCKOUT_WINDOW}) also records {@code auth.lockout}. The success is recorded before
   * the session exists, so a sign-in that can't be audited fails.
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
    int maxFailures = Math.max(1, props.getLogin().getMaxFailures());
    // Counted before the password is tried, in one atomic step: concurrent attempts can't all
    // pass the check, so no more than LOGIN_MAX_FAILURES passwords are tried per window. A
    // success clears the count; an attempt the directory couldn't answer is not counted.
    int count = throttle.recordFailure(throttleKey, windowMs());
    if (count > maxFailures) {
      event.anonymous(attempted).denied("Too many failed attempts");
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
    }
    String dn;
    boolean ok;
    try {
      dn = USERNAME.matcher(username).matches() && !password.isEmpty() ? findUserDn(username) : null;
      ok = dn != null && directory.authenticate(dn, password);
    } catch (RuntimeException e) {
      throttle.undo(throttleKey);
      throw e;
    }
    if (!ok) {
      event.anonymous(attempted).failed(dn == null ? "Unknown user" : "Wrong password");
      if (count == maxFailures) {
        long minutes = Math.max(1, windowMs() / 60_000);
        audit.event("auth.lockout").anonymous(attempted).target("user", attempted, null).severity(AuditEvent.ALERT)
            .detail("method", "password").detail("failures", count).detail("windowMinutes", minutes)
            .denied("Too many failed sign-in attempts: further attempts from this address are refused for " + minutes + " minutes");
      }
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    throttle.clear(throttleKey);
    SessionUser user = buildUser(dn, username, "password", null);
    event.by(user).target("user", user.dn(), user.displayName()).success();
    return user;
  }

  private long windowMs() {
    return props.getLogin().getLockoutWindow().toMillis();
  }

  @Scheduled(fixedDelay = 60_000)
  void sweepFailures() {
    throttle.sweep(windowMs());
  }

  public SessionUser buildUser(String dn, String username, String authMethod, String certificateSubject) {
    DirectoryEntry entry = directory.get(dn, userAttributes());
    return toUser(entry, dn, username, authMethod, certificateSubject, false);
  }

  /**
   * The signed-in user as the directory has them now (groups and administrator status read again),
   * or null when their entry no longer exists.
   *
   * @throws RuntimeException when the directory can't be read: the caller keeps the user as they are
   */
  public SessionUser refresh(SessionUser user) {
    DirectoryEntry entry = directory.get(user.dn(), userAttributes());
    if (entry == null) return null;
    return toUser(entry, user.dn(), user.username(), user.authMethod(), user.certificateSubject(), true);
  }

  /** Account-lock attributes: 389-DS/Red Hat DS, Active Directory, and the password policy overlay. */
  static final List<String> LOCK_ATTRIBUTES = List.of("nsAccountLock", "userAccountControl", "pwdAccountLockedTime");
  private static final int ACCOUNTDISABLE = 0x2;

  /** A re-read user, or why their account may not be used (then {@code user} is null). */
  public record Checked(SessionUser user, String locked) {}

  /**
   * As {@link #refresh}, and also refuses an account the directory marks disabled or locked
   * ({@code nsAccountLock: true}, Active Directory's ACCOUNTDISABLE flag in userAccountControl,
   * or a {@code pwdAccountLockedTime}). Null when the entry no longer exists.
   */
  public Checked refreshActive(SessionUser user) {
    List<String> attrs = new java.util.ArrayList<>(userAttributes());
    attrs.addAll(LOCK_ATTRIBUTES);
    DirectoryEntry entry = directory.get(user.dn(), attrs);
    if (entry == null) return null;
    String locked = lockReason(entry);
    if (locked != null) return new Checked(null, locked);
    return new Checked(toUser(entry, user.dn(), user.username(), user.authMethod(), user.certificateSubject(), true), null);
  }

  static String lockReason(DirectoryEntry entry) {
    if ("true".equalsIgnoreCase(entry.first("nsAccountLock"))) return "nsAccountLock";
    String uac = entry.first("userAccountControl");
    if (uac != null) {
      try {
        if ((Long.parseLong(uac.trim()) & ACCOUNTDISABLE) != 0) return "userAccountControl ACCOUNTDISABLE";
      } catch (NumberFormatException e) {
        return "unreadable userAccountControl";
      }
    }
    if (!entry.values("pwdAccountLockedTime").isEmpty()) return "pwdAccountLockedTime";
    return null;
  }

  private List<String> userAttributes() {
    return List.of("cn", "displayName", "mail", props.getUsernameAttribute(), "memberOf");
  }

  /** {@code strict}: a failed group lookup fails, instead of leaving the user with only memberOf. */
  private SessionUser toUser(DirectoryEntry entry, String dn, String username, String authMethod, String certificateSubject, boolean strict) {
    String ua = props.getUsernameAttribute();
    Set<String> groups = new LinkedHashSet<>(entry == null ? List.of() : entry.values("memberOf"));
    if (props.getGroupFilter() != null && !props.getGroupFilter().isBlank()) {
      try {
        Filter filter = template(props.getGroupFilter(), Map.of("dn", dn, "username", username));
        directory.search(props.effectiveGroupSearchBase(), SearchScope.SUB, filter, 1000, List.of("1.1"))
            .entries()
            .forEach(g -> groups.add(g.dn()));
      } catch (RuntimeException e) {
        if (strict) throw e;
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
