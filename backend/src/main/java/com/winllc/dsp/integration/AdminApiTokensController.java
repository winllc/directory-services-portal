package com.winllc.dsp.integration;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.web.ApiException;
import java.io.IOException;
import java.time.Duration;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrators' management of integration API tokens: create (the value is in this one
 * response), list and revoke. Creating and revoking are audited ({@code api.token-created},
 * {@code api.token-revoked}). A token can't be given a directory its creator can't reach.
 */
@RestController
@RequestMapping("/api/admin/api-tokens")
public class AdminApiTokensController {
  private final ApiTokens tokens;
  private final ApiTokenOwners owners;
  private final ApiRateLimiter limiter;
  private final EntryService entries;
  private final IntegrationProperties props;
  private final AuditLog audit;

  public AdminApiTokensController(ApiTokens tokens, ApiTokenOwners owners, ApiRateLimiter limiter, EntryService entries,
      IntegrationProperties props, AuditLog audit) {
    this.tokens = tokens;
    this.owners = owners;
    this.limiter = limiter;
    this.entries = entries;
    this.props = props;
    this.audit = audit;
  }

  public record CreateRequest(String name, List<String> scopes, List<String> definitionIds, Integer expiresInDays, Integer rateLimitPerMinute) {}

  public record Scope(String id, String description) {}

  public record Settings(List<Scope> scopes, int maxTokenDays, int defaultRateLimitPerMinute) {}

  @GetMapping
  public List<ApiTokens.View> list() {
    return tokens.list();
  }

  /** The scopes a token can have and the limits that apply (for the admin page). */
  @GetMapping("/settings")
  public Settings settings() {
    List<Scope> scopes = new ArrayList<>();
    for (ApiScope s : ApiScope.values()) scopes.add(new Scope(s.id(), s.description()));
    return new Settings(scopes, props.getApi().getMaxTokenDays(), props.getApi().getRateLimitPerMinute());
  }

  @PostMapping
  public ResponseEntity<ApiTokens.Created> create(@AuthenticationPrincipal SessionUser user, @RequestBody CreateRequest body) throws IOException {
    String name = body.name() == null ? "" : body.name().trim();
    if (name.isEmpty() || name.length() > 100) throw ApiException.badRequest("Invalid request", Map.of("name", "Give the token a name of at most 100 characters"));
    LinkedHashSet<String> scopes = new LinkedHashSet<>();
    for (String s : body.scopes() == null ? List.<String>of() : body.scopes()) {
      if (ApiScope.parse(s) == null) throw ApiException.badRequest("Invalid request", Map.of("scopes", "Unknown scope"));
      scopes.add(s);
    }
    if (scopes.isEmpty()) throw ApiException.badRequest("Invalid request", Map.of("scopes", "Choose at least one scope"));
    int maxDays = Math.max(1, props.getApi().getMaxTokenDays());
    int days = body.expiresInDays() == null ? Math.min(90, maxDays) : body.expiresInDays();
    if (days < 1 || days > maxDays) throw ApiException.badRequest("Invalid request", Map.of("expiresInDays", "Between 1 and " + maxDays + " days"));
    Integer rate = body.rateLimitPerMinute();
    if (rate != null && (rate < 1 || rate > 6000)) throw ApiException.badRequest("Invalid request", Map.of("rateLimitPerMinute", "Between 1 and 6000"));
    // Never more than the creator: each listed directory must be one they can reach now (and the
    // token keeps checking their rights on every request).
    LinkedHashSet<String> defs = new LinkedHashSet<>();
    for (String id : body.definitionIds() == null ? List.<String>of() : body.definitionIds()) {
      DirectoryDefinition def;
      try {
        def = entries.definition(id);
      } catch (ApiException e) {
        throw ApiException.badRequest("Invalid request", Map.of("definitionIds", "Unknown directory"));
      }
      if (entries.access(user, def) == AccessLevel.NONE) throw ApiException.forbidden("You cannot give a token a directory you cannot access");
      defs.add(def.id());
    }
    ApiTokens.Created created = tokens.create(name, List.copyOf(scopes), List.copyOf(defs), Duration.ofDays(days), rate, user.username(), user.dn());
    audit.event("api.token-created").target("api-token", created.token().id(), name)
        .detail("prefix", created.token().prefix()).detail("scopes", String.join(" ", scopes))
        .detail("definitions", defs.isEmpty() ? "all accessible" : String.join(" ", defs))
        .detail("expiresAt", created.token().expiresAt()).detail("rateLimitPerMinute", rate).success();
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @DeleteMapping("/{id}")
  public ApiTokens.View revoke(@AuthenticationPrincipal SessionUser user, @PathVariable String id) throws IOException {
    if (id == null || id.length() > 64) throw ApiException.notFound("No such token");
    ApiTokens.View existing = tokens.find(id);
    if (existing == null) throw ApiException.notFound("No such token");
    ApiTokens.View revoked = tokens.revoke(id, user.username());
    owners.forget(id);
    limiter.forget(id);
    if (existing.revokedAt() == null) {
      audit.event("api.token-revoked").target("api-token", revoked.id(), revoked.name()).detail("prefix", revoked.prefix()).success();
    }
    return revoked;
  }
}
