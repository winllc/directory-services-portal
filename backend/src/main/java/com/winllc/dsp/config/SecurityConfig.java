package com.winllc.dsp.config;

import com.winllc.dsp.audit.AccessDenials;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Session-based security. Authentication happens in {@code AuthController} (LDAP bind); the
 * resulting {@code SessionUser} is stored in the HTTP session. Administrators get ROLE_ADMIN.
 *
 * <p>The SIEM pull API ({@code /api/audit/**}) has its own chain: no session, no CSRF header; its
 * controller checks the {@code Authorization: Bearer} audit token. Every 403 is audited
 * ({@link AccessDenials}).
 *
 * <p>Both chains send the ENS baseline security headers. The web tier (nginx) sends the same set
 * for the client's pages and replaces these on {@code /api} responses.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
  /** The ENS baseline policy. The API serves no scripts or pages of its own, so it needs no nonce. */
  public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
      + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; "
      + "form-action 'self'; object-src 'none'";
  public static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";
  static final long HSTS_MAX_AGE_SECONDS = 31_536_000;

  /**
   * CSP (report-only with ENS_CSP_REPORT_ONLY), HSTS on secure requests (direct TLS, or
   * X-Forwarded-Proto with the framework forward-headers strategy), nosniff, DENY framing, the
   * referrer and permissions policies.
   */
  static Customizer<HeadersConfigurer<HttpSecurity>> baselineHeaders(EnsProperties ens) {
    return h -> {
      h.frameOptions(f -> f.deny())
          .contentTypeOptions(Customizer.withDefaults())
          .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
          .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_MAX_AGE_SECONDS))
          .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
          .addHeaderWriter((request, response) -> response.setHeader(
              ens.getCsp().isReportOnly() ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy", CONTENT_SECURITY_POLICY));
    };
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  @Order(1)
  SecurityFilterChain auditApiFilterChain(HttpSecurity http, EnsProperties ens) throws Exception {
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
        .headers(baselineHeaders(ens));
    return http.build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts, AccessDenials denials, EnsProperties ens)
      throws Exception {
    http
        // Replaced by RequestHeaderCsrfFilter (custom header + SameSite=Strict cookie).
        .csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .securityContext(sc -> sc.securityContextRepository(contexts))
        .addFilterAfter(new RequestHeaderCsrfFilter(denials), SecurityContextHolderFilter.class)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/x509", "/api/auth/logout", "/api/auth/consent").permitAll()
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
        .headers(baselineHeaders(ens));
    return http.build();
  }
}
