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

  /** The creator as the directory has them now, or why the token may not act as them. */
  public record Owner(SessionUser user, String problem) {}

  /**
   * The creator with current rights; problem "missing" when their entry no longer exists, or
   * "disabled" when the directory marks their account disabled or locked. A usable owner is
   * cached for a minute; a refusal is not cached, so re-enabling takes effect at once and a
   * disabled account is refused on every request.
   *
   * @throws RuntimeException when the directory can't be read
   */
  public Owner resolve(ApiTokens.Token token) {
    long now = System.nanoTime();
    Cached c = cache.get(token.id());
    if (c != null && now - c.at() < TTL_NANOS) return new Owner(c.user(), null);
    SessionUser stub = new SessionUser(token.createdBy(), token.createdByDn(), token.createdBy(), null, List.of(), false, AUTH_METHOD, null);
    AuthService.Checked fresh = auth.refreshActive(stub);
    if (fresh == null || fresh.user() == null) {
      cache.remove(token.id());
      return new Owner(null, fresh == null ? "missing" : "disabled");
    }
    cache.put(token.id(), new Cached(fresh.user(), now));
    return new Owner(fresh.user(), null);
  }

  public void forget(String tokenId) {
    cache.remove(tokenId);
  }
}
