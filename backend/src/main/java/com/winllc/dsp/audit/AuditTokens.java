package com.winllc.dsp.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * API tokens for the SIEM pull API ({@code GET /api/audit/events}): {@code ensat_} plus 32 random
 * bytes, base64url. Shown once when created, then kept only as SHA-256 in
 * {@code audit-api-tokens.json} next to the store. Their only scope is reading the audit trail.
 */
@Component
public class AuditTokens {
  private static final Logger log = LoggerFactory.getLogger(AuditTokens.class);
  public static final String PREFIX = "ensat_";
  static final String FILE = "audit-api-tokens.json";
  private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** As stored. {@code hash} is sha256 hex of the whole token. */
  public record Token(String id, String name, String hash, String prefix, String createdBy, String createdAt,
      String lastUsedAt, String revokedAt, String revokedBy) {

    Token used(String at) {
      return new Token(id, name, hash, prefix, createdBy, createdAt, at, revokedAt, revokedBy);
    }

    Token revoked(String at, String by) {
      return new Token(id, name, hash, prefix, createdBy, createdAt, lastUsedAt, at, by);
    }
  }

  /** What the admin page shows: never the hash. */
  public record View(String id, String name, String prefix, String createdBy, String createdAt, String lastUsedAt,
      String revokedAt, String revokedBy, boolean active) {}

  public record Created(View token, String secret) {}

  private final Path file;
  private final JsonMapper mapper;
  private final List<Token> tokens = new ArrayList<>();

  public AuditTokens(AuditLog audit, JsonMapper mapper) {
    this.file = audit.store().dir().resolve(FILE);
    this.mapper = mapper;
    load();
  }

  private void load() {
    if (!Files.exists(file)) return;
    try {
      tokens.addAll(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), new TypeReference<List<Token>>() {}));
    } catch (IOException | RuntimeException e) {
      log.error("Could not read the audit API tokens in {}: {}; no token will work until it is fixed", file, e.getMessage());
    }
  }

  private void save() throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(FILE + ".tmp");
    Files.writeString(tmp, mapper.writeValueAsString(tokens), StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized List<View> list() {
    return tokens.stream().map(AuditTokens::view).toList().reversed();
  }

  public synchronized Created create(String name, String createdBy) throws IOException {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Token t = new Token(UUID.randomUUID().toString(), name, AuditJson.sha256Hex(secret), secret.substring(0, PREFIX.length() + 6),
        createdBy, AuditLog.now(), null, null, null);
    tokens.add(t);
    try {
      save();
    } catch (IOException e) {
      tokens.remove(t);
      throw e;
    }
    return new Created(view(t), secret);
  }

  /** Revokes a token; returns it, or null when there is no such token. */
  public synchronized View revoke(String id, String by) throws IOException {
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (!t.id().equals(id)) continue;
      if (t.revokedAt() == null) {
        tokens.set(i, t.revoked(AuditLog.now(), by));
        save();
      }
      return view(tokens.get(i));
    }
    return null;
  }

  /** The active token matching an {@code Authorization: Bearer} value, recording its use; else null. */
  public synchronized View authenticate(String presented) {
    if (presented == null || !presented.startsWith(PREFIX) || presented.length() > 200) return null;
    byte[] hash = AuditJson.sha256Hex(presented).getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (!MessageDigest.isEqual(hash, t.hash().getBytes(StandardCharsets.US_ASCII))) continue;
      if (t.revokedAt() != null) return null;
      Instant now = Instant.now();
      boolean stale = t.lastUsedAt() == null || Duration.between(Instant.parse(t.lastUsedAt()), now).compareTo(LAST_USED_RESOLUTION) >= 0;
      if (stale) {
        tokens.set(i, t.used(AuditLog.now()));
        try {
          save();
        } catch (IOException e) {
          log.warn("Could not record the last use of audit API token {}: {}", t.name(), e.getMessage());
        }
      }
      return view(tokens.get(i));
    }
    return null;
  }

  private static View view(Token t) {
    return new View(t.id(), t.name(), t.prefix(), t.createdBy(), t.createdAt(), t.lastUsedAt(), t.revokedAt(), t.revokedBy(),
        t.revokedAt() == null);
  }
}
