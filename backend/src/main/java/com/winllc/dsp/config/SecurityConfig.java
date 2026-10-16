package com.winllc.dsp.config;

import com.winllc.dsp.audit.AccessDenials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Session-based security. Authentication happens in {@code AuthController} (LDAP bind); the
 * resulting {@code SessionUser} is stored in the HTTP session. Administrators get ROLE_ADMIN.
 *
 * <p>The SIEM pull API ({@code /api/audit/**}) has its own chain: no session, no CSRF header; its
 * controller checks the {@code Authorization: Bearer} audit token. Every 403 is audited
 * ({@link AccessDenials}).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  @Order(1)
  SecurityFilterChain auditApiFilterChain(HttpSecurity http) throws Exception {
    http
        .securityMatcher("/api/audit/**")
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .securityContext(sc -> sc.securityContextRepository(new NullSecurityContextRepository()))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .headers(h -> h.frameOptions(f -> f.deny()));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts, AccessDenials denials) throws Exception {
    http
        // Replaced by RequestHeaderCsrfFilter (custom header + SameSite=Strict cookie).
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .securityContext(sc -> sc.securityContextRepository(contexts))
        .addFilterAfter(new RequestHeaderCsrfFilter(denials), SecurityContextHolderFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/x509", "/api/auth/logout").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/info", "/api/health").permitAll()
            .requestMatchers("/api/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .exceptionHandling(e -> e
            .authenticationEntryPoint((req, res, ex) -> JsonErrors.write(res, 401, "Authentication required"))
            .accessDeniedHandler((req, res, ex) -> {
              denials.denied(req, "role_required", "Administrator access required");
              JsonErrors.write(res, 403, "Administrator access required");
            }))
        .headers(h -> h
            .frameOptions(f -> f.deny())
            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));
    return http.build();
  }
}
