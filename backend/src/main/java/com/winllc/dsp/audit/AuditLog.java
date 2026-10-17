package com.winllc.dsp.audit;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.model.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records who did what through the portal, in the shared {@code ens-audit/1} format.
 *
 * <p>The directory's own logs only ever see the portal's service account, so this is the one
 * place that says which person made a change. Each event is:
 *
 * <ul>
 *   <li>appended to the {@link AuditStore} (monthly JSONL files under {@code <data dir>/audit}) and
 *       synced to disk, hash-chained, in the request that caused it. A security action whose event
 *       can't be stored fails ({@link AuditUnavailableException}, 503); system events only log;
 *   <li>then written as one raw JSON line to the {@code ens.audit} logger (stdout and the optional
 *       rolling file, see {@link AuditOutputs});
 *   <li>delivered in the background to Splunk HEC and syslog when configured ({@link AuditDelivery}).
 * </ul>
 */
@Component
public class AuditLog {
  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);
  /** Dedicated logger (own appenders, additivity off) so log shippers can route audit events on their own. */
  public static final String LOGGER = "ens.audit";
  private static final Logger auditLogger = LoggerFactory.getLogger(LOGGER);
  static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private static final String REQUEST_ID = AuditLog.class.getName() + ".requestId";

  private final JsonMapper mapper;
  private final AuditStore store;
  private final ObjectProvider<Licensing> licensing;
  private final String version;
  private final String host;
  private volatile String installationId;

  public AuditLog(JsonMapper mapper, PortalProperties props, ObjectProvider<BuildProperties> build, ObjectProvider<Licensing> licensing) {
    this.mapper = mapper;
    this.licensing = licensing;
    BuildProperties b = build.getIfAvailable();
    this.version = b != null && b.getVersion() != null ? b.getVersion() : "dev";
    this.host = hostname();
    String configured = props.getAudit().getDir();
    Path dir = configured == null || configured.isBlank() ? Path.of(props.getDataDir()).resolve("audit") : Path.of(configured);
    this.store = new AuditStore(dir);
    new AuditMigration(mapper, this).run(store);
    log.info("Audit store at {}; chain head seq {}", store.dir(), store.headSeq());
  }

  public AuditStore store() {
    return store;
  }

  public String product() {
    return Licensing.PRODUCT;
  }

  public String version() {
    return version;
  }

  public String host() {
    return host;
  }

  /** From the license manager; null until it can be read. */
  public String installationId() {
    String id = installationId;
    if (id != null) return id;
    try {
      Licensing l = licensing.getIfAvailable();
      if (l != null) installationId = id = l.manager().installationId();
    } catch (RuntimeException e) {
      // The state directory isn't usable yet; recorded as null.
    }
    return id;
  }

  private static String hostname() {
    String env = System.getenv("HOSTNAME");
    if (env != null && !env.isBlank()) return env;
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException | RuntimeException e) {
      return "unknown";
    }
  }

  static String now() {
    return TIME.format(Instant.now());
  }

  /**
   * Fails with {@link AuditUnavailableException} when events can't be stored now. Called before a
   * security action that can't be undone, so it isn't performed unaudited.
   */
  public void requireWritable() {
    if (!store.writable()) throw new AuditUnavailableException("The audit log at " + store.dir() + " is not writable");
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
    private String category;
    private String severity;
    private String reason;
    private SessionUser user;
    private AuditEvent.Actor actor;
    private String anonymousId;
    private AuditEvent.Target target;
    private AuditEvent.Source source;
    private boolean sourceSet;
    private String time;
    private String id;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private AuditStore.Stored finished;
    private boolean done;

    private Draft(String action) {
      this.action = action;
    }

    /** The acting user. Defaults to the user signed in on the current request. */
    public Draft by(SessionUser user) {
      this.user = user;
      this.actor = null;
      return this;
    }

    /** An explicit actor (a service, the system). */
    public Draft actor(AuditEvent.Actor actor) {
      this.actor = actor;
      return this;
    }

    /** Not signed in; {@code attemptedId} is the user name tried (a failed sign-in), if any. */
    public Draft anonymous(String attemptedId) {
      this.anonymousId = attemptedId == null ? null : AuditDetails.truncate(attemptedId.length() > 128 ? attemptedId.substring(0, 128) : attemptedId);
      this.actor = new AuditEvent.Actor("anonymous", this.anonymousId, null, List.of());
      return this;
    }

    public Draft system() {
      this.actor = AuditEvent.Actor.SYSTEM;
      return this;
    }

    public Draft target(String type, String id, String name) {
      this.target = new AuditEvent.Target(type, AuditDetails.truncate(id), AuditDetails.truncate(name));
      return this;
    }

    public Draft source(String ip, String userAgent) {
      this.source = ip == null && userAgent == null ? null : new AuditEvent.Source(ip, AuditDetails.truncate(userAgent));
      this.sourceSet = true;
      return this;
    }

    public Draft category(String category) {
      this.category = category;
      return this;
    }

    public Draft severity(String severity) {
      this.severity = severity;
      return this;
    }

    /** The reason, for an outcome that has one without failing (e.g. why a session ended). */
    public Draft reason(String reason) {
      this.reason = reason;
      return this;
    }

    /** Keeps an original time and id (history migration). */
    Draft at(String time, String id) {
      this.time = time;
      this.id = id;
      return this;
    }

    public Draft definition(String definitionId) {
      return detail("definitionId", definitionId);
    }

    public Draft changes(Collection<AuditEvent.Change> changes) {
      changes.forEach(c -> AuditDetails.putChange(details, c));
      return this;
    }

    public Draft detail(String key, Object value) {
      if (value != null) AuditDetails.put(details, key, value);
      return this;
    }

    public AuditStore.Stored success() {
      return finish(AuditEvent.SUCCESS, null);
    }

    public AuditStore.Stored denied(String reason) {
      return finish(AuditEvent.DENIED, reason);
    }

    public AuditStore.Stored failed(String reason) {
      return finish(AuditEvent.FAILURE, reason);
    }

    /** An event is recorded once; a later outcome for the same draft is ignored. */
    private AuditStore.Stored finish(String outcome, String reason) {
      if (done) return finished;
      done = true;
      finished = record(build(outcome, reason != null ? reason : this.reason));
      return finished;
    }

    AuditEvent build(String outcome, String reason) {
      String cat = category != null ? category : AuditCatalog.category(action);
      String sev = severity != null ? severity : AuditCatalog.severity(cat, action, outcome);
      HttpServletRequest request = currentRequest();
      AuditEvent.Actor who = actor;
      Map<String, Object> d = new LinkedHashMap<>();
      if (who == null) {
        SessionUser u = user != null ? user : currentUser();
        if (u != null) {
          who = new AuditEvent.Actor("user", u.username(), u.displayName(), u.isAdmin() ? List.of("ADMIN", "USER") : List.of("USER"));
          d.put("actorDn", AuditDetails.truncate(u.dn()));
          d.put("authMethod", u.authMethod());
        } else {
          who = request != null ? new AuditEvent.Actor("anonymous", null, null, List.of()) : AuditEvent.Actor.SYSTEM;
        }
      }
      d.putAll(details);
      AuditEvent.Source src = sourceSet ? source : request == null ? null
          : new AuditEvent.Source(request.getRemoteAddr(), AuditDetails.truncate(request.getHeader("User-Agent")));
      return new AuditEvent(id != null ? id : UUID.randomUUID().toString(), 0, time != null ? time : now(), product(), version, host,
          installationId(), cat, action, outcome, sev, who, src, target, AuditDetails.truncate(reason), d,
          request == null ? null : requestId(request), request == null ? null : sessionHash(request), null, null);
    }
  }

  /**
   * Stores one event and writes it to the outputs. Returns null when it could not be stored and
   * the action may go on regardless (system events, denials); otherwise throws.
   */
  AuditStore.Stored record(AuditEvent event) {
    boolean required = AuditCatalog.mustBeRecorded(event.category(), event.action());
    try {
      return store.append(event, s -> emit(s.line()));
    } catch (IOException e) {
      log.error("Could not store audit event {} ({}) in {}: {}", event.id(), event.action(), store.dir(), e.getMessage());
      if (required) throw new AuditUnavailableException("The audit log could not be written: " + e.getMessage());
      return null;
    }
  }

  private static void emit(String line) {
    try {
      auditLogger.info(line);
    } catch (RuntimeException e) {
      log.error("Could not write an audit event to the {} logger: {}", LOGGER, e.getMessage());
    }
  }

  private static SessionUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof SessionUser u ? u : null;
  }

  static HttpServletRequest currentRequest() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
  }

  /** One id per request, shared by every event it records. */
  static String requestId(HttpServletRequest request) {
    Object id = request.getAttribute(REQUEST_ID);
    if (id == null) {
      id = UUID.randomUUID().toString();
      request.setAttribute(REQUEST_ID, id);
    }
    return id.toString();
  }

  /** The first 16 hex digits of sha256(session id): correlates events without revealing the session. */
  static String sessionHash(HttpServletRequest request) {
    try {
      HttpSession session = request.getSession(false);
      return session == null ? null : AuditJson.sha256Hex(session.getId()).substring(0, 16);
    } catch (IllegalStateException e) {
      return null;
    }
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
    return List.of(AuditDetails.truncate(s));
  }
}
