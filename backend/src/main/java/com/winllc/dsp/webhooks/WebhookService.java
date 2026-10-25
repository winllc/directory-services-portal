package com.winllc.dsp.webhooks;

import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.service.EntryChanged;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.util.LogSafe;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outbound webhooks. Every entry change the portal makes ({@link EntryChanged}) becomes events,
 * queued for each enabled webhook that subscribes to them:
 *
 * <ul>
 *   <li>{@code entry.changed}: an entry was created, updated (or renamed) or deleted;
 *   <li>{@code group.membership_changed}: a member, uniqueMember or memberUid value was added or removed;
 *   <li>{@code form.submitted}: an entry form was saved (create, update, or a user's own profile).
 * </ul>
 *
 * <p>A background thread sends due deliveries (signed, see {@link WebhookSender#sign}), checking
 * the URL again with {@link UrlGuard} each time, and retries failures with growing pauses (30 s,
 * 2 min, 10 min, 30 min, 1 h, 3 h, 6 h) up to WEBHOOK_MAX_ATTEMPTS; a delivery that runs out is
 * marked failed and audited ({@code webhook.delivery-failed}).
 *
 * <p>Each run (every couple of seconds) sends the oldest due deliveries one at a time, at most
 * {@value #MAX_PER_RUN}, and starts none after the run budget ({@code WEBHOOK_RUN_BUDGET}). A
 * webhook gets at most {@value #ENDPOINT_TRIES} attempts and 20 s of a run, so a slow receiver
 * can't take every run. A receiver that gives no answer at all (connection refused or timed out,
 * TLS failure, no answer within the deadline) is backed off as a whole: all of its webhook's due
 * deliveries wait as long as the one just tried, and the run sends none of them, so a dead
 * receiver costs one attempt per backoff step however many deliveries wait for it.
 *
 * <p>Delivery is at least once: an attempt whose answer was lost (a timeout after the receiver
 * acted, a replica stopping mid-send) is made again, with the same {@code X-DSP-Event-Id}, which
 * receivers use to drop repeats.
 *
 * <p>A change whose deliveries can't be queued (the shared database is unreachable) is kept in
 * this replica's memory, up to {@value #SPOOL_MAX} changes in order, and queued again by the next
 * delivery run that can: an outage of the database loses no event unless the replica stops
 * during it. A full outbox ({@code FileDeliveries}' cap) still drops, by design, and audits it.
 */
@Service
public class WebhookService {
  private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
  /** Added to the send deadline: how long a claimed delivery stays leased. */
  static final Duration LEASE = Duration.ofMinutes(1);
  private static final Duration[] BACKOFF = {Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
      Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6)};
  /** Attempts per run at most. */
  static final int MAX_PER_RUN = 20;
  /** Attempts per webhook and run at most. */
  public static final int ENDPOINT_TRIES = 10;
  /** Time per webhook and run at most: once its attempts took this long, the run is done with it. */
  public static final Duration ENDPOINT_TIME = Duration.ofSeconds(20);
  /** Members listed per change in a group.membership_changed event (more: truncated is true). */
  static final int MAX_MEMBERS = 1000;
  /** Changes kept in memory while their deliveries can't be queued. */
  static final int SPOOL_MAX = 10_000;

  private final Webhooks webhooks;
  private volatile Deliveries deliveries;
  private final IntegrationProperties props;
  private final AuditLog audit;
  private final JsonMapper mapper;
  private volatile UrlGuard guard;
  private volatile WebhookSender sender;
  private final ScheduledExecutorService worker;
  private volatile Instant lastPurge = Instant.EPOCH;
  /** Held by a delivery run: runs never overlap (the background thread's and a test's). */
  private final Object running = new Object();
  private boolean paused;
  private volatile int endpointTries = ENDPOINT_TRIES;
  private volatile Duration endpointTime = ENDPOINT_TIME;

  public WebhookService(Webhooks webhooks, IntegrationProperties props, PortalProperties portal, AuditLog audit, JsonMapper mapper,
      Cluster cluster) {
    this.webhooks = webhooks;
    this.props = props;
    this.audit = audit;
    this.mapper = mapper;
    this.deliveries = cluster.shared() ? new JdbcDeliveries(cluster)
        : new FileDeliveries(Path.of(portal.getDataDir()).toAbsolutePath().resolve(FileDeliveries.FILE), mapper);
    this.guard = new UrlGuard(props.getWebhooks().allowedHostList(), InetAddress::getAllByName);
    try {
      this.sender = new WebhookSender(SSLContext.getDefault(), props.getWebhooks().getConnectTimeout(), props.getWebhooks().getReadTimeout(),
          props.getWebhooks().getSendTimeout());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("TLS is not available", e);
    }
    this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "dsp-webhooks");
      t.setDaemon(true);
      return t;
    });
    worker.scheduleWithFixedDelay(this::dispatchSafely, 2, 2, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    worker.shutdownNow();
    deliveries.flush();
  }

  public UrlGuard guard() {
    return guard;
  }

  public Deliveries deliveries() {
    return deliveries;
  }

  /** For tests: another resolver or trust store (e.g. a local HTTPS server). */
  public void use(UrlGuard guard, WebhookSender sender) {
    this.guard = guard;
    this.sender = sender;
  }

  /** For tests: another outbox (e.g. one that fails like an unreachable database); returns the one in use. */
  public Deliveries use(Deliveries outbox) {
    Deliveries before = this.deliveries;
    this.deliveries = outbox;
    return before;
  }

  /** For tests: stops (or resumes) the background runs; returns once a run in progress is over. */
  public void pauseBackground(boolean pause) {
    synchronized (running) {
      this.paused = pause;
    }
  }

  /** For tests: the per-webhook limits of a run (attempts, and the time they may take in all). */
  public void endpointLimits(int tries, Duration time) {
    this.endpointTries = tries;
    this.endpointTime = time;
  }

  // ---------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------

  /**
   * A change waiting to be queued: the change itself until the webhooks were read, then its
   * deliveries (fixed ids, so queuing them again after a partial failure adds nothing twice).
   */
  private static final class Spooled {
    final EntryChanged change;
    final Instant at;
    List<Deliveries.Delivery> deliveries;

    Spooled(EntryChanged change, Instant at) {
      this.change = change;
      this.at = at;
    }
  }

  private final java.util.Deque<Spooled> spool = new java.util.ArrayDeque<>();

  @EventListener
  public void onEntryChanged(EntryChanged change) {
    Spooled item = new Spooled(change, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    synchronized (spool) {
      // Behind changes still waiting: keep the order.
      if (!spool.isEmpty()) {
        keep(item, null);
        return;
      }
    }
    try {
      queue(item);
    } catch (RuntimeException e) {
      synchronized (spool) {
        keep(item, e);
      }
    }
  }

  /** Queues a change's deliveries; throws when they could not be queued (nothing is lost then). */
  private void queue(Spooled item) {
    if (item.deliveries == null) {
      EntryChanged change = item.change;
      List<Webhooks.Webhook> hooks = webhooks.all().stream().filter(Webhooks.Webhook::enabled).toList();
      List<Deliveries.Delivery> queued = new ArrayList<>();
      if (!hooks.isEmpty()) {
        for (Map.Entry<String, Map<String, Object>> e : events(change)) {
          String type = e.getKey();
          String payload = null;
          String eventId = null;
          for (Webhooks.Webhook w : hooks) {
            if (!w.wants(type, change.definitionId())) continue;
            if (payload == null) {
              eventId = "evt_" + UUID.randomUUID();
              payload = payload(eventId, type, actor(change), e.getValue(), item.at);
            }
            queued.add(newDelivery(w.id(), eventId, type, payload, item.at));
          }
        }
      }
      item.deliveries = queued;
    }
    if (item.deliveries.isEmpty()) return;
    int accepted = deliveries.enqueue(item.deliveries);
    if (accepted < item.deliveries.size()) dropped(item.deliveries.size() - accepted);
  }

  /** Keeps a change to queue later (the caller holds the spool's lock); a full spool drops it, audited. */
  private void keep(Spooled item, RuntimeException cause) {
    if (spool.size() >= SPOOL_MAX) {
      log.error("Could not queue webhooks for the change of {} and {} changes already wait: dropped", LogSafe.of(item.change.dn()), SPOOL_MAX);
      try {
        dropped(1);
      } catch (RuntimeException ignored) {
        // The audit store may be down too; the log says it.
      }
      return;
    }
    if (cause != null) {
      log.warn("Could not queue webhooks for the change of {} ({}): kept in memory, queued again when possible", LogSafe.of(item.change.dn()),
          LogSafe.of(cause.getMessage()));
    }
    spool.addLast(item);
  }

  /**
   * Queues the changes kept in memory, oldest first, until one still fails. Only delivery runs
   * call it (one at a time), so the head stays put while it is queued without holding the lock:
   * a change made meanwhile only waits for the lock to join the end.
   */
  void replaySpool() {
    int done = 0;
    while (true) {
      Spooled head;
      synchronized (spool) {
        head = spool.peekFirst();
      }
      if (head == null) break;
      try {
        queue(head);
      } catch (RuntimeException e) {
        if (done > 0) log.info("Queued webhooks for {} changes kept in memory; {} still wait", done, spooled());
        return;
      }
      synchronized (spool) {
        spool.removeFirst();
      }
      done++;
    }
    if (done > 0) log.info("Queued webhooks for {} changes kept in memory", done);
  }

  /** Changes waiting to be queued (for tests). */
  public int spooled() {
    synchronized (spool) {
      return spool.size();
    }
  }

  private volatile Instant lastDropReport = Instant.EPOCH;
  private final java.util.concurrent.atomic.AtomicLong droppedSinceReport = new java.util.concurrent.atomic.AtomicLong();

  /** The outbox is full: logged every time, audited at most once a minute with the count. */
  private void dropped(int count) {
    long total = droppedSinceReport.addAndGet(count);
    log.warn("The webhook outbox is full: {} deliveries were not queued", count);
    Instant now = Instant.now();
    if (Duration.between(lastDropReport, now).compareTo(Duration.ofMinutes(1)) < 0) return;
    lastDropReport = now;
    droppedSinceReport.addAndGet(-total);
    audit.event("webhook.delivery-dropped").system().detail("dropped", total)
        .failed("The webhook outbox is full: deliveries were not queued (receivers down for long?)");
  }

  /** Queues a ping for one webhook; returns the delivery's id. */
  public String ping(Webhooks.Webhook w, String by) {
    String eventId = "evt_" + UUID.randomUUID();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("message", "A test event from the Directory Services Portal");
    data.put("webhookId", w.id());
    Map<String, Object> actor = new LinkedHashMap<>();
    actor.put("username", by);
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Deliveries.Delivery d = newDelivery(w.id(), eventId, Webhooks.PING, payload(eventId, Webhooks.PING, actor, data, now), now);
    if (deliveries.enqueue(List.of(d)) == 0) throw new com.winllc.dsp.web.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        "The webhook outbox is full; try again when deliveries have caught up");
    deliveries.flush();
    return d.id();
  }

  private static Deliveries.Delivery newDelivery(String webhookId, String eventId, String type, String payload, Instant now) {
    return new Deliveries.Delivery(UUID.randomUUID().toString(), webhookId, eventId, type, payload, Deliveries.PENDING, 0, now, now, null, null,
        null, null);
  }

  private static Map<String, Object> actor(EntryChanged c) {
    Map<String, Object> actor = new LinkedHashMap<>();
    EntryChanged.Actor a = c.actor();
    actor.put("username", a.username());
    actor.put("authMethod", a.authMethod());
    if (a.apiClientId() != null) actor.put("apiClientId", a.apiClientId());
    return actor;
  }

  /** The events one change makes, in order, with their data. */
  static List<Map.Entry<String, Map<String, Object>>> events(EntryChanged c) {
    List<Map.Entry<String, Map<String, Object>>> out = new ArrayList<>();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("operation", c.operation());
    entry.put("definitionId", c.definitionId());
    entry.put("dn", c.dn());
    if (c.previousDn() != null) entry.put("previousDn", c.previousDn());
    entry.put("name", c.name());
    entry.put("attributes", c.changes().stream().map(AuditEvent.Change::attribute).toList());
    out.add(Map.entry("entry.changed", entry));

    if (!EntryChanged.DELETE.equals(c.operation())) {
      for (AuditEvent.Change ch : c.changes()) {
        String attr = ch.attribute().toLowerCase(Locale.ROOT);
        if (!Memberships.MEMBER_ATTRIBUTES.contains(attr)) continue;
        boolean dn = !attr.equals("memberuid");
        List<String> added = ch.after().stream().filter(v -> ch.before().stream().noneMatch(b -> same(b, v, dn))).toList();
        List<String> removed = ch.before().stream().filter(v -> ch.after().stream().noneMatch(a -> same(a, v, dn))).toList();
        if (added.isEmpty() && removed.isEmpty()) continue;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definitionId", c.definitionId());
        m.put("dn", c.dn());
        m.put("name", c.name());
        m.put("attribute", ch.attribute());
        m.put("added", added.stream().limit(MAX_MEMBERS).toList());
        m.put("removed", removed.stream().limit(MAX_MEMBERS).toList());
        m.put("truncated", added.size() > MAX_MEMBERS || removed.size() > MAX_MEMBERS);
        out.add(Map.entry("group.membership_changed", m));
      }
      Map<String, Object> form = new LinkedHashMap<>();
      form.put("definitionId", c.definitionId());
      form.put("formId", c.formId());
      form.put("operation", c.self() ? "self_update" : c.operation());
      form.put("dn", c.dn());
      form.put("name", c.name());
      out.add(Map.entry("form.submitted", form));
    }
    return out;
  }

  private static boolean same(String a, String b, boolean dn) {
    return dn ? Dns.equal(a, b) || a.equalsIgnoreCase(b) : a.equalsIgnoreCase(b);
  }

  private String payload(String eventId, String type, Map<String, Object> actor, Map<String, Object> data, Instant createdAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", eventId);
    body.put("type", type);
    body.put("createdAt", createdAt.toString());
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("product", audit.product());
    source.put("version", audit.version());
    source.put("installationId", audit.installationId());
    body.put("source", source);
    body.put("actor", actor);
    body.put("data", data);
    return mapper.writeValueAsString(body);
  }

  // ---------------------------------------------------------------------------
  // Delivery
  // ---------------------------------------------------------------------------

  private void dispatchSafely() {
    try {
      synchronized (running) {
        if (!paused) dispatch();
      }
    } catch (RuntimeException e) {
      log.warn("Webhook delivery run failed: {}", LogSafe.of(e.getMessage()));
    }
  }

  /** What an attempt came to, for the run's per-webhook limits. */
  private enum Outcome {
    /** The receiver answered (any HTTP status). */
    ANSWERED,
    /** No HTTP answer at all: the webhook's other due deliveries were postponed. */
    NO_ANSWER,
    /** Nothing was sent (the webhook is gone or disabled). */
    NOT_SENT
  }

  /** Sends the deliveries that are due; returns how many were attempted. */
  public int dispatch() {
    synchronized (running) {
      replaySpool();
      if (!props.getWebhooks().isEnabled()) return 0;
      Instant now = Instant.now();
      if (Duration.between(lastPurge, now).compareTo(Duration.ofHours(1)) >= 0) {
        lastPurge = now;
        deliveries.purge(now.minus(props.getWebhooks().getLogRetention()));
      }
      return run();
    }
  }

  private int run() {
    // One at a time, each claimed just before it is sent and leased for longer than one attempt
    // can take, so a delivery is never claimed again (here or by another replica) while it is
    // still being sent, however long the run. Runs hold no cluster lock: each replica runs its
    // own, and the claim (SKIP LOCKED) shares the due deliveries out.
    Duration lease = sender.deadline().plus(LEASE);
    long started = System.nanoTime();
    long budget = props.getWebhooks().getRunBudget().toNanos();
    long endpointNanos = endpointTime.toNanos();
    // Per webhook: attempts made and the time they took, in this run.
    Map<String, long[]> used = new HashMap<>();
    // Webhooks this run is done with: no answer, or their share of the run used up.
    Set<String> done = new HashSet<>();
    int count = 0;
    // No attempt starts once the budget is spent (the first always does, whatever the setting).
    while (count < MAX_PER_RUN && (count == 0 || System.nanoTime() - started < budget)) {
      List<Deliveries.Delivery> claimed = deliveries.claim(Instant.now(), 1, lease, done);
      if (claimed.isEmpty()) break;
      Deliveries.Delivery d = claimed.get(0);
      long began = System.nanoTime();
      Outcome outcome = attempt(d);
      count++;
      if (outcome == Outcome.NOT_SENT) continue;
      long[] u = used.computeIfAbsent(d.webhookId(), k -> new long[2]);
      u[0]++;
      u[1] += System.nanoTime() - began;
      if (outcome == Outcome.NO_ANSWER || u[0] >= endpointTries || u[1] >= endpointNanos) done.add(d.webhookId());
    }
    deliveries.flush();
    return count;
  }

  private Outcome attempt(Deliveries.Delivery d) {
    Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Webhooks.Webhook w = webhooks.get(d.webhookId());
    if (w == null || !w.enabled()) {
      deliveries.record(d.attempted(at, null, w == null ? "The webhook was deleted" : "The webhook is disabled", Deliveries.FAILED, d.nextAttemptAt()));
      return Outcome.NOT_SENT;
    }
    Integer status = null;
    String error;
    try {
      // One deadline for the whole attempt: the name lookup and the send.
      long started = System.nanoTime();
      UrlGuard.Target target = guard.resolve(UrlGuard.parse(w.url()), sender.deadline());
      byte[] body = d.payload().getBytes(StandardCharsets.UTF_8);
      long timestamp = at.getEpochSecond();
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("X-DSP-Event", d.eventType());
      headers.put("X-DSP-Event-Id", d.eventId());
      headers.put("X-DSP-Delivery", d.id());
      headers.put("X-DSP-Webhook-Id", w.id());
      headers.put(WebhookSender.TIMESTAMP_HEADER, Long.toString(timestamp));
      headers.put(WebhookSender.SIGNATURE_HEADER, WebhookSender.sign(w.secret(), timestamp, body));
      WebhookSender.Result r = sender.send(target, headers, body, sender.deadline().minusNanos(System.nanoTime() - started));
      if (r.delivered()) {
        deliveries.record(d.attempted(at, r.status(), null, Deliveries.DELIVERED, d.nextAttemptAt()));
        return Outcome.ANSWERED;
      }
      status = r.status() == 0 ? null : r.status();
      error = r.error() != null ? r.error() : "HTTP " + r.status();
    } catch (UrlGuard.Refused e) {
      error = "Refused: " + e.getMessage();
    }
    error = error.length() > 900 ? error.substring(0, 900) : error;
    int max = Math.max(1, props.getWebhooks().getMaxAttempts());
    Instant next = at.plus(BACKOFF[Math.max(0, Math.min(d.attempts() - 1, BACKOFF.length - 1))]);
    if (d.attempts() >= max) {
      deliveries.record(d.attempted(at, status, error, Deliveries.FAILED, d.nextAttemptAt()));
      log.warn("Webhook {} ({}): giving up on delivery {} of {} after {} attempts: {}", LogSafe.of(w.name()), w.id(), d.id(), d.eventType(),
          d.attempts(), LogSafe.of(error));
      audit.event("webhook.delivery-failed").system().target("webhook", w.id(), w.name()).detail("deliveryId", d.id())
          .detail("eventType", d.eventType()).detail("attempts", d.attempts()).detail("host", host(w.url())).failed(error);
    } else {
      deliveries.record(d.attempted(at, status, error, Deliveries.PENDING, next));
    }
    if (status != null) return Outcome.ANSWERED;
    // No HTTP answer at all (refused, unreachable, TLS failure, timeout, or the address refused by
    // the guard): the receiver, not this delivery, is the problem, so the webhook's other due
    // deliveries wait as long as this one rather than each taking an attempt of its own.
    int moved = deliveries.postpone(w.id(), next);
    if (moved > 0) {
      log.info("Webhook {} ({}) gave no answer: {} more deliveries wait until {}", LogSafe.of(w.name()), w.id(), moved, next);
    }
    return Outcome.NO_ANSWER;
  }

  /** The URL's host, for logs and the audit trail (never its path or query, which may hold a key). */
  public static String host(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? "?" : h;
    } catch (RuntimeException e) {
      return "?";
    }
  }
}
