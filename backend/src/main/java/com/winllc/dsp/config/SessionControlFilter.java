package com.winllc.dsp.config;

import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the session controls ({@link SessionService}) to every API request of a signed-in user,
 * before authorization, so refreshed groups and administrator status take effect at once. An
 * expired session answers 401 with {@code "code": "session_expired"} and the reason; the sign-in
 * endpoints instead carry on as signed out ({@code /api/auth/me} then reports no user).
 */
public class SessionControlFilter extends OncePerRequestFilter {
  private final SessionService sessions;

  public SessionControlFilter(SessionService sessions) {
    this.sessions = sessions;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    HttpSession session = request.getSession(false);
    Authentication auth = session == null ? null : SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof SessionUser user) {
      SessionService.Expired expired = sessions.onRequest(request, response, session, user);
      if (expired != null) {
        SecurityContextHolder.clearContext();
        if (!request.getRequestURI().startsWith("/api/auth/")) {
          JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, expired.message(), "session_expired");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
