package com.winllc.dsp.sso;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.service.ConsentService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.saml2.provider.service.authentication.AbstractSaml2AuthenticationRequest;
import org.springframework.security.saml2.provider.service.web.Saml2AuthenticationRequestRepository;
import org.springframework.stereotype.Component;

/**
 * Sign-in requests in flight, between the redirect to the provider and its answer. The answer
 * arrives as a cross-site navigation (OIDC: a GET; SAML: a POST), which never carries the
 * SameSite=Strict session cookie, so the request is kept server-side ({@link SsoStateStore}) under a
 * random handle in its own short-lived cookie: SameSite=Lax for OIDC, SameSite=None (Secure) for
 * SAML. Each request is used once; the OIDC state, nonce and PKCE verifier and the SAML request id
 * and RelayState are checked against it.
 */
@Component
public class SsoRequests {
  /** The cookie holding the handle of the sign-in request in flight. */
  public static final String COOKIE = "DSP_SSO";
  /** Request attribute: the {@link Pending} taken from the store when the provider answered. */
  public static final String PENDING = SsoRequests.class.getName() + ".pending";
  static final String COOKIE_PATH = "/api/auth/sso/";
  static final Duration LIFETIME = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** A request in flight and whether the notice-and-consent banner was acknowledged before it. */
  public record Pending(Serializable request, boolean consentAcknowledged) implements Serializable {}

  private final SsoStateStore store;
  private final ConsentService consent;
  private final EnsProperties ens;

  public SsoRequests(SsoStateStore store, ConsentService consent, EnsProperties ens) {
    this.store = store;
    this.consent = consent;
    this.ens = ens;
  }

  public final AuthorizationRequestRepository<OAuth2AuthorizationRequest> oidc = new AuthorizationRequestRepository<>() {
    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
      return store.peek(handle(request)) instanceof Pending p && p.request() instanceof OAuth2AuthorizationRequest r ? r : null;
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request, HttpServletResponse response) {
      if (authorizationRequest == null) {
        removeAuthorizationRequest(request, response);
        return;
      }
      save(authorizationRequest, request, response, "Lax");
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
      Pending p = take(request, response);
      return p != null && p.request() instanceof OAuth2AuthorizationRequest r ? r : null;
    }
  };

  public final Saml2AuthenticationRequestRepository<AbstractSaml2AuthenticationRequest> saml = new Saml2AuthenticationRequestRepository<>() {
    @Override
    public AbstractSaml2AuthenticationRequest loadAuthenticationRequest(HttpServletRequest request) {
      return store.peek(handle(request)) instanceof Pending p && p.request() instanceof AbstractSaml2AuthenticationRequest r
          && relayStateMatches(r, request) ? r : null;
    }

    @Override
    public void saveAuthenticationRequest(AbstractSaml2AuthenticationRequest authenticationRequest, HttpServletRequest request,
        HttpServletResponse response) {
      if (authenticationRequest == null) {
        removeAuthenticationRequest(request, response);
        return;
      }
      save(authenticationRequest, request, response, "None");
    }

    @Override
    public AbstractSaml2AuthenticationRequest removeAuthenticationRequest(HttpServletRequest request, HttpServletResponse response) {
      Pending p = take(request, response);
      return p != null && p.request() instanceof AbstractSaml2AuthenticationRequest r && relayStateMatches(r, request) ? r : null;
    }
  };

  /** The IdP returns the RelayState it was sent; a response with another one is not for this request. */
  private static boolean relayStateMatches(AbstractSaml2AuthenticationRequest r, HttpServletRequest request) {
    String sent = request.getParameter("RelayState");
    return r.getRelayState() == null || Objects.equals(sent, r.getRelayState());
  }

  private void save(Serializable value, HttpServletRequest request, HttpServletResponse response, String sameSite) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String handle = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    store.put(handle, new Pending(value, !consent.required(request)), LIFETIME);
    boolean secure = request.isSecure() || ens.getCookie().isSecure();
    // SameSite=None is only accepted on a Secure cookie; without TLS, fall back to Lax (SAML then needs HTTPS).
    String site = "None".equals(sameSite) && !secure ? "Lax" : sameSite;
    response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, handle).path(COOKIE_PATH).httpOnly(true).secure(secure)
        .sameSite(site).maxAge(LIFETIME).build().toString());
  }

  private Pending take(HttpServletRequest request, HttpServletResponse response) {
    String handle = handle(request);
    if (handle == null) return null;
    Pending p = store.take(handle) instanceof Pending x ? x : null;
    if (p != null) request.setAttribute(PENDING, p);
    if (response != null) {
      response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(COOKIE, "").path(COOKIE_PATH).httpOnly(true)
          .secure(request.isSecure() || ens.getCookie().isSecure()).maxAge(0).build().toString());
    }
    return p;
  }

  private static String handle(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    for (Cookie c : cookies) {
      if (COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank() && c.getValue().length() <= 64) return c.getValue();
    }
    return null;
  }
}
