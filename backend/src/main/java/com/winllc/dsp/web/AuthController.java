package com.winllc.dsp.web;

import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.AuthService;
import com.winllc.dsp.web.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private final AuthService auth;
  private final SecurityContextRepository contexts;

  public AuthController(AuthService auth, SecurityContextRepository contexts) {
    this.auth = auth;
    this.contexts = contexts;
  }

  @PostMapping("/login")
  public Map<String, SessionUser> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    SessionUser user = auth.login(body.username().trim(), body.password(), request.getRemoteAddr());

    List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_USER")));
    if (user.isAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
    SecurityContextHolder.setContext(context);

    // Prevent session fixation: always issue a fresh session id on login.
    request.getSession(true);
    request.changeSessionId();
    contexts.saveContext(context, request, response);
    return Map.of("user", user);
  }

  @PostMapping("/logout")
  public Map<String, Boolean> logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
    return Map.of("ok", true);
  }

  /** Current session; {@code user} is null when signed out (so page loads don't log a 401). */
  @GetMapping("/me")
  public Map<String, SessionUser> me(@AuthenticationPrincipal SessionUser user) {
    Map<String, SessionUser> body = new HashMap<>();
    body.put("user", user);
    return body;
  }
}
