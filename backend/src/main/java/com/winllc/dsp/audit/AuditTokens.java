package com.winllc.dsp.audit;

import com.winllc.dsp.util.LogSafe;
import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.SharedDocuments;
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
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * API tokens for the SIEM pull API ({@code GET /api/audit/events}): {@code ensat_} plus 32 random
 * bytes, base64url. Shown once when created, then kept only as SHA-256 in
 * {@code audit-api-tokens.json} next to the store (with the shared database: the
 * {@value #DOCUMENT} document, read again whenever another replica changed it, so a revoked token
 * stops working everywhere at once). Their only scope is reading the audit trail.
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

  static final String DOCUMENT = "audit-api-tokens";

  private final Path file;
  private final JsonMapper mapper;
  private final SharedDocuments shared;
  private final List<Token> tokens = new ArrayList<>();
  private long version;

  public AuditTokens(AuditLog audit, JsonMapper mapper, Cluster cluster) {
    this.file = audit.store().dir().resolve(FILE);
    this.mapper = mapper;
    this.shared = cluster.shared() ? cluster.documents() : null;
    load();
  }

  private void load() {
    if (shared != null) {
      SharedDocuments.Doc doc = shared.get(DOCUMENT);
      if (!doc.exists() && Files.exists(file)) {
        try {
          doc = shared.createIfAbsent(DOCUMENT, mapper.writeValueAsString(parse(Files.readString(file, StandardCharsets.UTF_8))));
          log.info("Imported the audit API tokens from {} into the shared database", file);
        } catch (IOException | RuntimeException e) {
          log.error("Could not import the audit API tokens in {}: {}", file, e.getMessage());
        }
      }
      use(doc);
      return;
    }
    if (!Files.exists(file)) return;
    try {
      tokens.addAll(parse(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException | RuntimeException e) {
      log.error("Could not read the audit API tokens in {}: {}; no token will work until it is fixed", file, e.getMessage());
    }
  }

  private List<Token> parse(String json) {
    return new ArrayList<>(mapper.readValue(json, new TypeReference<List<Token>>() {}));
  }

  private void use(SharedDocuments.Doc doc) {
    tokens.clear();
    if (doc.exists()) tokens.addAll(parse(doc.content()));
    version = doc.version();
  }

  /** With the shared database: reads the tokens again when another replica changed them. */
  private void sync() {
    if (shared == null) return;
    try {
      if (shared.version(DOCUMENT) != version) use(shared.get(DOCUMENT));
    } catch (RuntimeException e) {
      log.warn("Could not check the shared audit API tokens for changes: {}", LogSafe.of(e.getMessage()));
    }
  }

  /** Applies a change to the latest shared tokens and stores them (shared database only). */
  private void changeShared(Consumer<List<Token>> change) throws IOException {
    try {
      use(shared.update(DOCUMENT, current -> {
        List<Token> list = current == null ? new ArrayList<>() : parse(current);
        change.accept(list);
        return mapper.writeValueAsString(list);
      }));
    } catch (RuntimeException e) {
      throw new IOException("Could not store the audit API tokens: " + e.getMessage(), e);
    }
  }

  private void save() throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(FILE + ".tmp");
    Files.writeString(tmp, mapper.writeValueAsString(tokens), StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  public synchronized List<View> list() {
    sync();
    return tokens.stream().map(AuditTokens::view).toList().reversed();
  }

  public synchronized Created create(String name, String createdBy) throws IOException {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Token t = new Token(UUID.randomUUID().toString(), name, AuditJson.sha256Hex(secret), secret.substring(0, PREFIX.length() + 6),
        createdBy, AuditLog.now(), null, null, null);
    if (shared != null) {
      changeShared(list -> list.add(t));
      return new Created(view(t), secret);
    }
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
    if (shared != null) {
      String at = AuditLog.now();
      changeShared(list -> list.replaceAll(t -> t.id().equals(id) && t.revokedAt() == null ? t.revoked(at, by) : t));
      return tokens.stream().filter(t -> t.id().equals(id)).findFirst().map(AuditTokens::view).orElse(null);
    }
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
    sync();
    byte[] hash = AuditJson.sha256Hex(presented).getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (!MessageDigest.isEqual(hash, t.hash().getBytes(StandardCharsets.US_ASCII))) continue;
      if (t.revokedAt() != null) return null;
      Instant now = Instant.now();
      boolean stale = t.lastUsedAt() == null || Duration.between(Instant.parse(t.lastUsedAt()), now).compareTo(LAST_USED_RESOLUTION) >= 0;
      if (stale) {
        String at = AuditLog.now();
        try {
          if (shared != null) {
            changeShared(list -> list.replaceAll(x -> x.id().equals(t.id()) ? x.used(at) : x));
            return tokens.stream().filter(x -> x.id().equals(t.id())).findFirst().map(AuditTokens::view).orElse(view(t));
          }
          tokens.set(i, t.used(at));
          save();
        } catch (IOException e) {
          log.warn("Could not record the last use of audit API token {}: {}", LogSafe.of(t.name()), LogSafe.of(e.getMessage()));
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
