package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.AuthService;
import com.winllc.dsp.service.ConsentService;
import com.winllc.dsp.web.dto.LoginRequest;
import com.winllc.dsp.x509.CertificateRejectedException;
import com.winllc.dsp.x509.X509AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  /** Set on logout so certificate auto-login doesn't immediately sign the user back in. */
  static final String SUPPRESS_AUTO_LOGIN_COOKIE = "DSP_X509_SIGNED_OUT";

  private final AuthService auth;
  private final X509AuthService x509;
  private final SecurityContextRepository contexts;
  private final AuditLog audit;
  private final EnsProperties ens;
  private final ConsentService consent;

  public AuthController(AuthService auth, X509AuthService x509, SecurityContextRepository contexts, AuditLog audit, EnsProperties ens,
      ConsentService consent) {
    this.auth = auth;
    this.x509 = x509;
    this.contexts = contexts;
    this.audit = audit;
    this.ens = ens;
    this.consent = consent;
  }

  /** Acknowledge the notice-and-consent banner for this browser session (needed before any sign-in). */
  @PostMapping("/consent")
  public Map<String, Boolean> acknowledgeConsent(HttpServletRequest request) {
    consent.acknowledge(request);
    return Map.of("consentRequired", consent.required(request));
  }

  @PostMapping("/login")
  public Map<String, SessionUser> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    String attempted = body.username().trim();
    consent.requireAcknowledged(request, audit.event("auth.login").anonymous(attempted)
        .target("user", attempted.length() > 128 ? attempted.substring(0, 128) : attempted, null).detail("method", "password"));
    SessionUser user = auth.login(attempted, body.password(), request.getRemoteAddr());
    establishSession(user, request, response);
    return Map.of("user", user);
  }

  /** Sign in with the client certificate presented on this request. */
  @PostMapping("/x509")
  public Map<String, SessionUser> loginWithCertificate(HttpServletRequest request, HttpServletResponse response) {
    consent.requireAcknowledged(request, audit.event("auth.login").anonymous(null).detail("method", "x509"));
    SessionUser user = x509.authenticate(request);
    establishSession(user, request, response);
    setSuppressCookie(request, response, false);
    return Map.of("user", user);
  }

  /** {@code consentRequired}: the banner must be acknowledged again before the next sign-in. */
  @PostMapping("/logout")
  public Map<String, Boolean> logout(@AuthenticationPrincipal SessionUser user, HttpServletRequest request, HttpServletResponse response) {
    if (user != null) audit.event("auth.logout").by(user).target("user", user.dn(), user.displayName()).success();
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    if (x509.enabled()) setSuppressCookie(request, response, true);
    return Map.of("ok", true, "consentRequired", consent.enabled());
  }

  /**
   * Current session; {@code user} is null when signed out. With certificate auto-login enabled,
   * a browser presenting a valid certificate is signed in here transparently, once the
   * notice-and-consent banner (if enabled) has been acknowledged. {@code consentRequired}: the
   * banner must be acknowledged before signing in.
   */
  @GetMapping("/me")
  public Map<String, Object> me(@AuthenticationPrincipal SessionUser user, HttpServletRequest request, HttpServletResponse response) {
    Map<String, Object> body = new HashMap<>();
    SessionUser current = user;
    boolean consentRequired = current == null && consent.required(request);
    if (current == null && !consentRequired && x509.available() && autoLoginAllowed(request) && x509.hasCertificate(request)) {
      try {
        current = x509.authenticate(request);
        establishSession(current, request, response);
      } catch (CertificateRejectedException e) {
        body.put("certificateError", e.getMessage());
      }
    }
    body.put("user", current);
    body.put("consentRequired", consentRequired);
    return body;
  }

  private boolean autoLoginAllowed(HttpServletRequest request) {
    if (!x509Props().isAutoLogin()) return false;
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return true;
    for (Cookie c : cookies) if (SUPPRESS_AUTO_LOGIN_COOKIE.equals(c.getName())) return false;
    return true;
  }

  private com.winllc.dsp.config.PortalProperties.X509 x509Props() {
    return x509.properties();
  }

  private void setSuppressCookie(HttpServletRequest request, HttpServletResponse response, boolean suppress) {
    ResponseCookie cookie = ResponseCookie.from(SUPPRESS_AUTO_LOGIN_COOKIE, suppress ? "1" : "")
        .path("/")
        .httpOnly(true)
        .sameSite("Strict")
        // As the session cookie: Secure over TLS, or always with ENS_COOKIE_SECURE.
        .secure(request.isSecure() || ens.getCookie().isSecure())
        .maxAge(suppress ? -1 : 0)
        .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void establishSession(SessionUser user, HttpServletRequest request, HttpServletResponse response) {
    List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    if (user.isAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
    SecurityContextHolder.setContext(context);

    // Prevent session fixation: always issue a fresh session id on sign-in.
    request.getSession(true);
    request.changeSessionId();
    contexts.saveContext(context, request, response);
  }
}
