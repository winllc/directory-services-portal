package com.winllc.dsp.integration;

import com.winllc.dsp.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** The API token a {@code /api/v1} request was authenticated with (a request attribute). */
public record ApiTokenContext(String id, String name, Set<ApiScope> scopes, List<String> definitionIds) {
  public static final String ATTRIBUTE = ApiTokenContext.class.getName();

  public static ApiTokenContext of(HttpServletRequest request) {
    Object value = request.getAttribute(ATTRIBUTE);
    if (value instanceof ApiTokenContext c) return c;
    throw new ApiException(HttpStatus.UNAUTHORIZED, "API token required", "token_required");
  }

  public static ApiTokenContext current() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) return of(a.getRequest());
    throw new ApiException(HttpStatus.UNAUTHORIZED, "API token required", "token_required");
  }

  public boolean has(ApiScope scope) {
    return scopes.contains(scope);
  }

  /** 403 {@code insufficient_scope} unless the token has the scope. */
  public void require(ApiScope scope) {
    if (!has(scope)) throw new ApiException(HttpStatus.FORBIDDEN, "This token does not have the " + scope.id() + " scope", "insufficient_scope");
  }

  /** True when the token may reach this directory definition (no list: every one its creator can). */
  public boolean covers(String definitionId) {
    return definitionIds == null || definitionIds.isEmpty() || definitionIds.contains(definitionId);
  }
}
