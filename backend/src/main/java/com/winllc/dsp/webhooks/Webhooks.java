package com.winllc.dsp.webhooks;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedJsonList;
import com.winllc.dsp.config.PortalProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configured webhooks: in {@code webhooks.json} in the data directory (readable by the
 * portal's user only), or the shared database's {@value #DOCUMENT} document. A webhook's signing
 * secret has to be kept as it is (it signs every delivery): it is returned once, when created or
 * rotated, and never again by the API, in logs or in the audit trail.
 */
@Component
public class Webhooks {
  static final String DOCUMENT = "webhooks";
  static final String FILE = "webhooks.json";
  public static final String SECRET_PREFIX = "whsec_";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The event types a webhook can subscribe to. */
  public static final List<String> EVENT_TYPES = List.of("entry.changed", "group.membership_changed", "form.submitted");
  /** Sent only by "Send test event". */
  public static final String PING = "ping";

  public record Webhook(String id, String name, String url, String secret, List<String> events, List<String> definitionIds, boolean enabled,
      String createdBy, String createdAt, String updatedAt) {

    public boolean wants(String type, String definitionId) {
      return enabled && events.contains(type) && (definitionIds == null || definitionIds.isEmpty() || definitionIds.contains(definitionId));
    }
  }

  /** Without the secret. */
  public record View(String id, String name, String url, List<String> events, List<String> definitionIds, boolean enabled, String createdBy,
      String createdAt, String updatedAt) {}

  public record Created(View webhook, String secret) {}

  private final SharedJsonList<Webhook> hooks;

  @org.springframework.beans.factory.annotation.Autowired
  public Webhooks(PortalProperties props, JsonMapper mapper, Cluster cluster) {
    this(Path.of(props.getDataDir()).toAbsolutePath().resolve(FILE), mapper, cluster);
  }

  Webhooks(Path file, JsonMapper mapper, Cluster cluster) {
    this.hooks = new SharedJsonList<>(DOCUMENT, file, mapper, cluster, new TypeReference<List<Webhook>>() {});
  }

  static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
  }

  public static View view(Webhook w) {
    return new View(w.id(), w.name(), w.url(), w.events(), w.definitionIds(), w.enabled(), w.createdBy(), w.createdAt(), w.updatedAt());
  }

  public List<Webhook> all() {
    return hooks.list();
  }

  public Webhook get(String id) {
    return hooks.list().stream().filter(w -> w.id().equals(id)).findFirst().orElse(null);
  }

  public Created create(String name, String url, List<String> events, List<String> definitionIds, boolean enabled, String by) throws IOException {
    String secret = newSecret();
    String at = now();
    Webhook w = new Webhook(UUID.randomUUID().toString(), name, url, secret, List.copyOf(events), List.copyOf(definitionIds), enabled, by, at, at);
    hooks.change(list -> list.add(w));
    return new Created(view(w), secret);
  }

  /** Replaces a webhook's settings (not its secret); null when there is no such webhook. */
  public Webhook update(String id, String name, String url, List<String> events, List<String> definitionIds, boolean enabled) throws IOException {
    String at = now();
    hooks.change(list -> list.replaceAll(w -> w.id().equals(id)
        ? new Webhook(w.id(), name, url, w.secret(), List.copyOf(events), List.copyOf(definitionIds), enabled, w.createdBy(), w.createdAt(), at)
        : w));
    return get(id);
  }

  /** A new secret (returned this once); null when there is no such webhook. */
  public Created rotate(String id) throws IOException {
    String secret = newSecret();
    String at = now();
    hooks.change(list -> list.replaceAll(w -> w.id().equals(id)
        ? new Webhook(w.id(), w.name(), w.url(), secret, w.events(), w.definitionIds(), w.enabled(), w.createdBy(), w.createdAt(), at)
        : w));
    Webhook w = get(id);
    return w == null ? null : new Created(view(w), secret);
  }

  public boolean delete(String id) throws IOException {
    boolean[] removed = {false};
    hooks.change(list -> removed[0] = list.removeIf(w -> w.id().equals(id)));
    return removed[0];
  }
}
