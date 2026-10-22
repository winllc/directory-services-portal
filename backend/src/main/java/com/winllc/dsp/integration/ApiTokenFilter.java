package com.winllc.dsp.integration;

import com.winllc.dsp.audit.AccessDenials;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.util.LogSafe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /api/v1} requests by {@code Authorization: Bearer dspat_...} only: no
 * session is read or created, so the CSRF header isn't needed (a browser can't attach this header
 * to a cross-site request). A missing, unknown, expired or revoked token answers 401; a token over
 * its rate limit 429 with {@code Retry-After}. Rejections are audited as {@code access.denied}
 * (collapsed like other denials). The token value never reaches a log, the audit trail or a session.
 */
public class ApiTokenFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
  public static final String ROLE = "ROLE_API";

  private final ApiTokens tokens;
  private final ApiTokenOwners owners;
  private final ApiRateLimiter limiter;
  private final IntegrationProperties props;
  private final AccessDenials denials;

  public ApiTokenFilter(ApiTokens tokens, ApiTokenOwners owners, ApiRateLimiter limiter, IntegrationProperties props, AccessDenials denials) {
    this.tokens = tokens;
    this.owners = owners;
    this.limiter = limiter;
    this.props = props;
    this.denials = denials;
  }

  /** The OpenAPI document is public. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith(IntegrationApi.OPENAPI_PATH);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      unauthorized(response, "API token required: send Authorization: Bearer <token>", "token_required");
      return;
    }
    ApiTokens.Check check = tokens.check(header.substring(7).trim());
    if (!check.ok()) {
      // Failures only are counted, per client address (the address Spring resolved from the web
      // tier's X-Forwarded-For, as for sign-in throttling); a valid token is never blocked by them.
      String client = "bad-token:" + request.getRemoteAddr();
      long wait = limiter.acquire(client, props.getApi().getFailedTokenLimitPerMinute());
      if (wait > 0) {
        response.setHeader("Retry-After", Long.toString(wait));
        write(response, 429, "Too many invalid API tokens from this address; retry after " + wait + " s", "rate_limited");
        return;
      }
      String message = switch (check.problem()) {
        case "expired" -> "This API token has expired";
        case "revoked" -> "This API token has been revoked";
        default -> "Invalid API token";
      };
      if (denials != null) denials.denied(request, "api_token_" + check.problem(), message);
      unauthorized(response, message, "token_" + check.problem());
      return;
    }
    ApiTokens.Token token = check.token();
    long wait = limiter.acquire(token.id(), token.rateLimitPerMinute() != null ? token.rateLimitPerMinute() : props.getApi().getRateLimitPerMinute());
    if (wait > 0) {
      response.setHeader("Retry-After", Long.toString(wait));
      write(response, 429, "Too many requests for this API token; retry after " + wait + " s", "rate_limited");
      return;
    }
    ApiTokenOwners.Owner resolved;
    try {
      resolved = owners.resolve(token);
    } catch (RuntimeException e) {
      log.warn("Could not read the creator of API token {} from the directory: {}", LogSafe.of(token.name()), LogSafe.of(e.getMessage()));
      write(response, 503, "The directory is unavailable", "directory_unavailable");
      return;
    }
    if (resolved.user() == null) {
      String message = "missing".equals(resolved.problem()) ? "The user who created this API token no longer exists"
          : "The account of the user who created this API token is disabled or locked";
      if (denials != null) denials.denied(request, "api_token_owner_" + resolved.problem(), message);
      unauthorized(response, message, "token_owner_" + resolved.problem());
      return;
    }
    SessionUser owner = resolved.user();
    Set<ApiScope> scopes = EnumSet.noneOf(ApiScope.class);
    token.scopes().stream().map(ApiScope::parse).filter(Objects::nonNull).forEach(scopes::add);
    request.setAttribute(ApiTokenContext.ATTRIBUTE, new ApiTokenContext(token.id(), token.name(), scopes,
        token.definitionIds() == null ? List.of() : token.definitionIds()));
    request.setAttribute(AuditLog.API_CLIENT, new AuditLog.ApiClient(token.id(), token.name()));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of(new SimpleGrantedAuthority(ROLE))));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void unauthorized(HttpServletResponse response, String message, String code) throws IOException {
    response.setHeader("WWW-Authenticate", "Bearer realm=\"dsp\"");
    write(response, 401, message, code);
  }

  private static void write(HttpServletResponse response, int status, String message, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"code\":\"" + code + "\"}");
  }
}
