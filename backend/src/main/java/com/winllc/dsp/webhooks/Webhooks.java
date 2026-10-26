package com.winllc.dsp.webhooks;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedJsonList;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.secrets.SecretBox;
import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configured webhooks: in {@code webhooks.json} in the data directory (readable by the
 * portal's user only), or the shared database's {@value #DOCUMENT} document. A webhook's signing
 * secret has to be kept as it is (it signs every delivery): it is returned once, when created or
 * rotated, and never again by the API, in logs or in the audit trail.
 *
 * <p>With {@code DSP_SECRETS_KEY} the secrets are stored encrypted ({@link SecretBox}, bound to
 * their webhook's id); a secret stored unencrypted (before the key was set) is encrypted at the
 * next start, and by every change of the list. Without the key they are stored as given, with a
 * warning at startup. A store holding secrets encrypted with a key other than the configured one
 * (or with a key while none is configured) is refused at startup rather than used: its webhooks
 * could not sign anything, and new secrets would be encrypted with a key that doesn't open the
 * others.
 */
@Component
public class Webhooks {
  private static final Logger log = LoggerFactory.getLogger(Webhooks.class);
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
  private final SecretBox box;

  @org.springframework.beans.factory.annotation.Autowired
  public Webhooks(PortalProperties props, JsonMapper mapper, Cluster cluster, SecretBox box) {
    this(Path.of(props.getDataDir()).toAbsolutePath().resolve(FILE), mapper, cluster, box);
  }

  Webhooks(Path file, JsonMapper mapper, Cluster cluster, SecretBox box) {
    this.hooks = new SharedJsonList<>(DOCUMENT, file, mapper, cluster, new TypeReference<List<Webhook>>() {});
    this.box = box;
    checkSecrets();
  }

  /** The context a webhook's secret is sealed for: it opens only as that webhook's. */
  private static String context(String id) {
    return "webhook:" + id;
  }

  /**
   * At startup: refuses a store sealed with another key (or with a key while none is set), warns
   * without a key, and encrypts secrets stored unencrypted when there is one.
   */
  private void checkSecrets() {
    List<Webhook> all = hooks.list();
    List<String> foreign = all.stream().map(w -> SecretBox.keyIdOf(w.secret())).filter(id -> id != null && !id.equals(box.keyId()))
        .distinct().toList();
    if (!foreign.isEmpty()) {
      throw new IllegalStateException("Refusing to start: webhook signing secrets are stored encrypted with key " + String.join(", ", foreign)
          + (box.enabled() ? ", but DSP_SECRETS_KEY is key " + box.keyId() : ", but DSP_SECRETS_KEY is not set")
          + ". Set DSP_SECRETS_KEY (or DSP_SECRETS_KEY_FILE) to the key they were stored with (every API replica needs the same key).");
    }
    long plain = all.stream().filter(w -> w.secret() != null && !SecretBox.sealed(w.secret())).count();
    if (!box.enabled()) {
      log.warn("DSP_SECRETS_KEY is not set: webhook signing secrets are stored unencrypted ({} now), readable by anyone who can read {}."
          + " Set DSP_SECRETS_KEY_FILE (32 random bytes, base64: openssl rand -base64 32) to store them encrypted.", plain,
          hooks.describe());
      return;
    }
    if (plain == 0) return;
    try {
      hooks.change(this::sealAll);
      log.info("Encrypted {} webhook signing secrets stored unencrypted (key {})", plain, box.keyId());
    } catch (IOException | RuntimeException e) {
      log.warn("Could not encrypt the {} webhook signing secrets stored unencrypted: {}; they are encrypted at the next change", plain,
          LogSafe.of(e.getMessage()));
    }
  }

  /** Seals every secret still stored unencrypted (with the key, if one is set). */
  private void sealAll(List<Webhook> list) {
    if (!box.enabled()) return;
    list.replaceAll(w -> w.secret() == null || SecretBox.sealed(w.secret()) ? w
        : new Webhook(w.id(), w.name(), w.url(), box.seal(w.secret(), context(w.id())), w.events(), w.definitionIds(), w.enabled(),
            w.createdBy(), w.createdAt(), w.updatedAt()));
  }

  /**
   * The secret a webhook's deliveries are signed with.
   *
   * @throws SecretBox.UnreadableSecretException when it is stored encrypted and can't be decrypted
   */
  public String signingSecret(Webhook w) {
    return box.open(w.secret(), context(w.id()));
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
    String id = UUID.randomUUID().toString();
    Webhook w = new Webhook(id, name, url, box.seal(secret, context(id)), List.copyOf(events), List.copyOf(definitionIds), enabled, by, at, at);
    hooks.change(list -> {
      list.add(w);
      sealAll(list);
    });
    return new Created(view(w), secret);
  }

  /** Replaces a webhook's settings (not its secret); null when there is no such webhook. */
  public Webhook update(String id, String name, String url, List<String> events, List<String> definitionIds, boolean enabled) throws IOException {
    String at = now();
    hooks.change(list -> {
      list.replaceAll(w -> w.id().equals(id)
          ? new Webhook(w.id(), name, url, w.secret(), List.copyOf(events), List.copyOf(definitionIds), enabled, w.createdBy(), w.createdAt(), at)
          : w);
      sealAll(list);
    });
    return get(id);
  }

  /** A new secret (returned this once); null when there is no such webhook. */
  public Created rotate(String id) throws IOException {
    String secret = newSecret();
    String at = now();
    hooks.change(list -> {
      list.replaceAll(w -> w.id().equals(id)
          ? new Webhook(w.id(), w.name(), w.url(), box.seal(secret, context(w.id())), w.events(), w.definitionIds(), w.enabled(), w.createdBy(),
              w.createdAt(), at)
          : w);
      sealAll(list);
    });
    Webhook w = get(id);
    return w == null ? null : new Created(view(w), secret);
  }

  public boolean delete(String id) throws IOException {
    boolean[] removed = {false};
    hooks.change(list -> {
      removed[0] = list.removeIf(w -> w.id().equals(id));
      sealAll(list);
    });
    return removed[0];
  }
}
