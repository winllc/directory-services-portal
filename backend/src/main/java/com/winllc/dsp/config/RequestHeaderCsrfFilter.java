package com.winllc.dsp.config;

import com.winllc.dsp.audit.AccessDenials;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF defence: state-changing API requests must carry {@code X-DSP-Request: 1}. Browsers
 * cannot add custom headers to cross-site requests without a CORS preflight (which this API
 * never approves), and the session cookie is SameSite=Strict as a second layer.
 */
public class RequestHeaderCsrfFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-DSP-Request";
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  private final AccessDenials denials;

  public RequestHeaderCsrfFilter(AccessDenials denials) {
    this.denials = denials;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/") || SAFE.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!"1".equals(request.getHeader(HEADER))) {
      if (denials != null) denials.denied(request, "csrf", "Missing request verification header");
      JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "Missing request verification header");
      return;
    }
    chain.doFilter(request, response);
  }
}
