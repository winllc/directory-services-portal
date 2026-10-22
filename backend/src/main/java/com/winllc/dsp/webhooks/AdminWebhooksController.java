package com.winllc.dsp.webhooks;

import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrators' webhooks: create (the signing secret is in this one response), change, rotate
 * the secret, delete, send a test event and read the delivery log. The URL is checked by
 * {@link UrlGuard} when saved. Changes are audited, with the URL's host only (a path or query may
 * hold the receiver's key).
 */
@RestController
@RequestMapping("/api/admin/webhooks")
public class AdminWebhooksController {
  private final Webhooks webhooks;
  private final WebhookService service;
  private final EntryService entries;
  private final IntegrationProperties props;
  private final AuditLog audit;

  public AdminWebhooksController(Webhooks webhooks, WebhookService service, EntryService entries, IntegrationProperties props, AuditLog audit) {
    this.webhooks = webhooks;
    this.service = service;
    this.entries = entries;
    this.props = props;
    this.audit = audit;
  }

  public record WebhookRequest(String name, String url, List<String> events, List<String> definitionIds, Boolean enabled) {}

  public record Settings(boolean enabled, List<String> eventTypes, List<String> allowedHosts, int maxAttempts) {}

  /** A delivery as the log shows it (the payload is what was signed and sent). */
  public record DeliveryView(String id, String eventId, String eventType, String status, int attempts, String createdAt, String lastAttemptAt,
      Integer lastStatus, String lastError, String deliveredAt, String nextAttemptAt, String payload) {}

  @GetMapping
  public List<Webhooks.View> list() {
    return webhooks.all().stream().map(Webhooks::view).toList();
  }

  @GetMapping("/settings")
  public Settings settings() {
    return new Settings(props.getWebhooks().isEnabled(), Webhooks.EVENT_TYPES, props.getWebhooks().allowedHostList(),
        props.getWebhooks().getMaxAttempts());
  }

  private record Valid(String name, String url, List<String> events, List<String> definitionIds, boolean enabled) {}

  private Valid validate(WebhookRequest body) {
    String name = body.name() == null ? "" : body.name().trim();
    if (name.isEmpty() || name.length() > 100) throw ApiException.badRequest("Invalid request", Map.of("name", "Give the webhook a name of at most 100 characters"));
    String url = body.url() == null ? "" : body.url().trim();
    try {
      URI uri = UrlGuard.parse(url);
      service.guard().resolve(uri);
    } catch (UrlGuard.Refused e) {
      throw ApiException.badRequest("Invalid request", Map.of("url", e.getMessage()));
    }
    LinkedHashSet<String> events = new LinkedHashSet<>();
    for (String e : body.events() == null ? List.<String>of() : body.events()) {
      if (!Webhooks.EVENT_TYPES.contains(e)) throw ApiException.badRequest("Invalid request", Map.of("events", "Unknown event type"));
      events.add(e);
    }
    if (events.isEmpty()) throw ApiException.badRequest("Invalid request", Map.of("events", "Choose at least one event"));
    LinkedHashSet<String> defs = new LinkedHashSet<>();
    for (String id : body.definitionIds() == null ? List.<String>of() : body.definitionIds()) {
      try {
        defs.add(entries.definition(id).id());
      } catch (ApiException e) {
        throw ApiException.badRequest("Invalid request", Map.of("definitionIds", "Unknown directory"));
      }
    }
    return new Valid(name, url, List.copyOf(events), List.copyOf(defs), body.enabled() == null || body.enabled());
  }

  private Webhooks.Webhook find(String id) {
    Webhooks.Webhook w = id == null || id.length() > 64 ? null : webhooks.get(id);
    if (w == null) throw ApiException.notFound("No such webhook");
    return w;
  }

  private static Map<String, Object> settingsOf(String url, List<String> events, List<String> defs, boolean enabled) {
    return Map.of("host", WebhookService.host(url), "events", String.join(" ", events),
        "definitions", defs.isEmpty() ? "all" : String.join(" ", defs), "enabled", enabled);
  }

  @PostMapping
  public ResponseEntity<Webhooks.Created> create(@AuthenticationPrincipal SessionUser user, @RequestBody WebhookRequest body) throws IOException {
    if (!props.getWebhooks().isEnabled()) throw ApiException.badRequest("Webhooks are turned off (WEBHOOKS_ENABLED=false)");
    Valid v = validate(body);
    Webhooks.Created created = webhooks.create(v.name(), v.url(), v.events(), v.definitionIds(), v.enabled(), user.username());
    AuditLog.Draft event = audit.event("webhook.created").target("webhook", created.webhook().id(), v.name());
    settingsOf(v.url(), v.events(), v.definitionIds(), v.enabled()).forEach(event::detail);
    event.success();
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PutMapping("/{id}")
  public Webhooks.View update(@PathVariable String id, @RequestBody WebhookRequest body) throws IOException {
    Webhooks.Webhook before = find(id);
    Valid v = validate(body);
    Webhooks.Webhook after = webhooks.update(id, v.name(), v.url(), v.events(), v.definitionIds(), v.enabled());
    if (after == null) throw ApiException.notFound("No such webhook");
    if (!after.enabled()) service.deliveries().cancel(id, "The webhook is disabled");
    List<AuditEvent.Change> changes = new ArrayList<>();
    if (!before.name().equals(after.name())) changes.add(new AuditEvent.Change("name", List.of(before.name()), List.of(after.name())));
    if (!WebhookService.host(before.url()).equals(WebhookService.host(after.url())) || !before.url().equals(after.url())) {
      changes.add(new AuditEvent.Change("host", List.of(WebhookService.host(before.url())), List.of(WebhookService.host(after.url()))));
    }
    if (!before.events().equals(after.events())) changes.add(new AuditEvent.Change("events", before.events(), after.events()));
    if (!before.definitionIds().equals(after.definitionIds())) {
      changes.add(new AuditEvent.Change("definitions", before.definitionIds(), after.definitionIds()));
    }
    if (before.enabled() != after.enabled()) {
      changes.add(new AuditEvent.Change("enabled", List.of(Boolean.toString(before.enabled())), List.of(Boolean.toString(after.enabled()))));
    }
    audit.event("webhook.updated").target("webhook", id, after.name()).changes(changes).detail("urlChanged", !before.url().equals(after.url())).success();
    return Webhooks.view(after);
  }

  @PostMapping("/{id}/rotate-secret")
  public Webhooks.Created rotate(@PathVariable String id) throws IOException {
    Webhooks.Webhook w = find(id);
    Webhooks.Created created = webhooks.rotate(id);
    if (created == null) throw ApiException.notFound("No such webhook");
    audit.event("webhook.secret-rotated").target("webhook", id, w.name()).success();
    return created;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
    Webhooks.Webhook w = find(id);
    webhooks.delete(id);
    service.deliveries().cancel(id, "The webhook was deleted");
    audit.event("webhook.deleted").target("webhook", id, w.name()).detail("host", WebhookService.host(w.url())).success();
    return ResponseEntity.noContent().build();
  }

  /** Queues a {@code ping} event for this webhook (sent within seconds; see the delivery log). */
  @PostMapping("/{id}/test")
  public Map<String, String> test(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    Webhooks.Webhook w = find(id);
    if (!w.enabled()) throw ApiException.badRequest("Enable the webhook first");
    return Map.of("deliveryId", service.ping(w, user.username()));
  }

  @GetMapping("/{id}/deliveries")
  public List<DeliveryView> deliveries(@PathVariable String id) {
    find(id);
    return service.deliveries().recent(id, 100).stream().map(d -> new DeliveryView(d.id(), d.eventId(), d.eventType(), d.status(), d.attempts(),
        str(d.createdAt()), str(d.lastAttemptAt()), d.lastStatus(), d.lastError(), str(d.deliveredAt()),
        Deliveries.PENDING.equals(d.status()) ? str(d.nextAttemptAt()) : null, d.payload())).toList();
  }

  private static String str(java.time.Instant i) {
    return i == null ? null : i.toString();
  }
}
