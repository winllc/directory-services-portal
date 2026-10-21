package com.winllc.dsp.integration;

import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.AuthService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The user an API token acts as: its creator, as the directory has them now (groups and
 * administrator status read again at most once a minute), so a token never has more rights than
 * its creator currently does. Administrator rights a single sign-on provider asserted at the
 * creator's sign-in are not carried over: only the directory and ADMIN_USERS / ADMIN_GROUPS count.
 */
@Component
public class ApiTokenOwners {
  static final long TTL_NANOS = 60_000_000_000L;
  public static final String AUTH_METHOD = "api-token";

  private record Cached(SessionUser user, long at) {}

  private final AuthService auth;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public ApiTokenOwners(AuthService auth) {
    this.auth = auth;
  }

  /**
   * The creator with current rights, or null when their entry no longer exists.
   *
   * @throws RuntimeException when the directory can't be read
   */
  public SessionUser resolve(ApiTokens.Token token) {
    long now = System.nanoTime();
    Cached c = cache.get(token.id());
    if (c != null && now - c.at() < TTL_NANOS) return c.user();
    SessionUser stub = new SessionUser(token.createdBy(), token.createdByDn(), token.createdBy(), null, List.of(), false, AUTH_METHOD, null);
    SessionUser fresh = auth.refresh(stub);
    if (fresh == null) {
      cache.remove(token.id());
      return null;
    }
    cache.put(token.id(), new Cached(fresh, now));
    return fresh;
  }

  public void forget(String tokenId) {
    cache.remove(tokenId);
  }
}
