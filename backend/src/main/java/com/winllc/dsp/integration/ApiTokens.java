package com.winllc.dsp.integration;

import com.winllc.dsp.audit.AuditJson;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedJsonList;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.util.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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
 * Tokens for the integration API ({@code /api/v1}): {@code dspat_} plus 32 random bytes, base64url.
 * An administrator creates one with scopes, an expiry and optionally a list of directories; its
 * value is shown once and only its SHA-256 is kept (in {@code api-tokens.json} in the data
 * directory, or the shared database's {@value #DOCUMENT} document). Requests made with it act as
 * its creator, with the creator's current rights narrowed by the token's scopes and directories.
 */
@Component
public class ApiTokens {
  private static final Logger log = LoggerFactory.getLogger(ApiTokens.class);
  public static final String PREFIX = "dspat_";
  static final String DOCUMENT = "api-tokens";
  static final String FILE = "api-tokens.json";
  private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** As stored: {@code hash} is the SHA-256 (hex) of the whole token; the token itself is never kept. */
  public record Token(String id, String name, String hash, String prefix, List<String> scopes, List<String> definitionIds,
      String createdBy, String createdByDn, String createdAt, String expiresAt, Integer rateLimitPerMinute, String lastUsedAt,
      String revokedAt, String revokedBy) {

    Token used(String at) {
      return new Token(id, name, hash, prefix, scopes, definitionIds, createdBy, createdByDn, createdAt, expiresAt, rateLimitPerMinute, at,
          revokedAt, revokedBy);
    }

    Token revoked(String at, String by) {
      return new Token(id, name, hash, prefix, scopes, definitionIds, createdBy, createdByDn, createdAt, expiresAt, rateLimitPerMinute,
          lastUsedAt, at, by);
    }

    boolean expired(Instant now) {
      return expiresAt != null && !now.isBefore(Instant.parse(expiresAt));
    }
  }

  /** What administrators see: never the hash. {@code status} is active, expired or revoked. */
  public record View(String id, String name, String prefix, List<String> scopes, List<String> definitionIds, String createdBy,
      String createdAt, String expiresAt, Integer rateLimitPerMinute, String lastUsedAt, String revokedAt, String revokedBy, String status) {}

  /** A new token: {@code secret} is its value, returned this once. */
  public record Created(View token, String secret) {}

  /** The outcome of presenting a token: the token when it may be used, else why not. */
  public record Check(Token token, String problem) {
    public boolean ok() {
      return problem == null;
    }
  }

  private final SharedJsonList<Token> tokens;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public ApiTokens(PortalProperties props, JsonMapper mapper, Cluster cluster) {
    this(Path.of(props.getDataDir()).toAbsolutePath().resolve(FILE), mapper, cluster, Clock.systemUTC());
  }

  ApiTokens(Path file, JsonMapper mapper, Cluster cluster, Clock clock) {
    this.tokens = new SharedJsonList<>(DOCUMENT, file, mapper, cluster, new TypeReference<List<Token>>() {});
    this.clock = clock;
  }

  private String now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS).toString();
  }

  public List<View> list() {
    Instant now = clock.instant();
    return tokens.list().stream().map(t -> view(t, now)).toList().reversed();
  }

  public View find(String id) {
    Instant now = clock.instant();
    return tokens.list().stream().filter(t -> t.id().equals(id)).findFirst().map(t -> view(t, now)).orElse(null);
  }

  public Created create(String name, List<String> scopes, List<String> definitionIds, Duration lifetime, Integer rateLimit, String createdBy,
      String createdByDn) throws IOException {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    Token t = new Token(UUID.randomUUID().toString(), name, AuditJson.sha256Hex(secret), secret.substring(0, PREFIX.length() + 6),
        List.copyOf(scopes), List.copyOf(definitionIds), createdBy, createdByDn, now.toString(), now.plus(lifetime).toString(), rateLimit,
        null, null, null);
    tokens.change(list -> list.add(t));
    return new Created(view(t, now), secret);
  }

  /** Revokes a token; returns it, or null when there is no such token. */
  public View revoke(String id, String by) throws IOException {
    String at = now();
    tokens.change(list -> list.replaceAll(t -> t.id().equals(id) && t.revokedAt() == null ? t.revoked(at, by) : t));
    return find(id);
  }

  /**
   * Checks a presented token (the value after {@code Bearer }), recording its use when it is valid.
   * Compared by hash in constant time; the value itself is never logged or kept.
   */
  public Check check(String presented) {
    if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) return new Check(null, "unknown");
    byte[] hash = AuditJson.sha256Hex(presented).getBytes(StandardCharsets.US_ASCII);
    Instant now = clock.instant();
    for (Token t : tokens.list()) {
      if (!MessageDigest.isEqual(hash, t.hash().getBytes(StandardCharsets.US_ASCII))) continue;
      if (t.revokedAt() != null) return new Check(t, "revoked");
      if (t.expired(now)) return new Check(t, "expired");
      boolean stale = t.lastUsedAt() == null || Duration.between(Instant.parse(t.lastUsedAt()), now).compareTo(LAST_USED_RESOLUTION) >= 0;
      if (stale) {
        String at = now();
        try {
          tokens.change(list -> list.replaceAll(x -> x.id().equals(t.id()) ? x.used(at) : x));
        } catch (IOException e) {
          log.warn("Could not record the last use of API token {}: {}", LogSafe.of(t.name()), LogSafe.of(e.getMessage()));
        }
      }
      return new Check(t, null);
    }
    return new Check(null, "unknown");
  }

  static View view(Token t, Instant now) {
    String status = t.revokedAt() != null ? "revoked" : t.expired(now) ? "expired" : "active";
    return new View(t.id(), t.name(), t.prefix(), t.scopes(), t.definitionIds(), t.createdBy(), t.createdAt(), t.expiresAt(),
        t.rateLimitPerMinute(), t.lastUsedAt(), t.revokedAt(), t.revokedBy(), status);
  }
}
