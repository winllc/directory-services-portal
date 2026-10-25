package com.winllc.dsp.sso;

import com.winllc.dsp.integration.ApiRateLimiter;
import com.winllc.dsp.service.ConsentService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.SubjectConfirmation;
import org.opensaml.saml.saml2.core.SubjectConfirmationData;
import org.opensaml.saml.saml2.assertion.SAML2AssertionValidationParameters;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ErrorCodes;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the configured single sign-on to the API's security chain (nothing when none is):
 *
 * <ul>
 *   <li>OIDC: {@code GET /api/auth/sso/authorize/oidc} redirects to the provider (authorization
 *       code with PKCE, state and nonce); the provider returns to
 *       {@code /api/auth/sso/callback/oidc}. The ID token's signature (the provider's JWKS),
 *       issuer, audience, expiry (60 s skew) and nonce are checked by Spring Security.
 *   <li>SAML: {@code GET /api/auth/sso/saml/authenticate/saml} sends an AuthnRequest; the IdP posts
 *       its response to {@code /api/auth/sso/saml/acs/saml}; this SP's metadata is at
 *       {@code /api/auth/sso/saml/metadata}. The response or every assertion must be signed by the
 *       IdP's metadata key; issuer, audience, recipient, validity (SSO_SAML_CLOCK_SKEW) and
 *       InResponseTo are checked, IdP-initiated responses are refused and each assertion is
 *       accepted once.
 * </ul>
 *
 * Neither starts while the notice-and-consent banner is unacknowledged. Tokens from the provider
 * are not kept: the session holds only the portal user, as for password sign-in.
 */
@Component
public class SsoSecurity {
  public static final String OIDC_START = "/api/auth/sso/authorize/";
  public static final String SAML_START = "/api/auth/sso/saml/authenticate/";
  public static final String SAML_ACS = "/api/auth/sso/saml/acs/";

  private final SsoProperties props;
  private final SsoRequests requests;
  private final SsoSignIn signIn;
  private final SsoStateStore state;
  private final ConsentService consent;
  private final ApiRateLimiter limiter;
  private final ClientRegistrationRepository oidcClients;
  private final SsoRegistrations.Saml samlParties;

  public SsoSecurity(SsoProperties props, SsoRequests requests, SsoSignIn signIn, SsoStateStore state, ConsentService consent,
      ApiRateLimiter limiter) {
    this.props = props;
    this.requests = requests;
    this.signIn = signIn;
    this.state = state;
    this.consent = consent;
    this.limiter = limiter;
    this.oidcClients = new SsoRegistrations.Oidc(props);
    this.samlParties = new SsoRegistrations.Saml(props);
  }

  /** The sign-in buttons for the login page: id, label and where the browser goes. */
  public record Option(String id, String label, String url) {}

  public List<Option> options() {
    List<Option> out = new ArrayList<>();
    if (props.getOidc().enabled()) {
      out.add(new Option("oidc", label(props.getOidc(), "Sign in with single sign-on"), OIDC_START + SsoRegistrations.OIDC));
    }
    if (props.getSaml().enabled()) {
      out.add(new Option("saml", label(props.getSaml(), "Sign in with SAML single sign-on"), SAML_START + SsoRegistrations.SAML));
    }
    return out;
  }

  private static String label(SsoProperties.Provider p, String fallback) {
    return p.getLabel() == null || p.getLabel().isBlank() ? fallback : p.getLabel().trim();
  }

  /** Adds the configured providers to the chain. */
  public void apply(HttpSecurity http) throws Exception {
    if (!props.enabled()) return;
    http.addFilterAfter(new ConsentGate(), SecurityContextHolderFilter.class);
    if (props.getOidc().enabled()) {
      DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(oidcClients, "/api/auth/sso/authorize");
      // PKCE for confidential clients too (public clients get it anyway).
      resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
      http.oauth2Login(o -> o
          .clientRegistrationRepository(oidcClients)
          .authorizedClientRepository(NO_AUTHORIZED_CLIENTS)
          .loginPage("/login")
          .authorizationEndpoint(a -> a.authorizationRequestResolver(resolver).authorizationRequestRepository(requests.oidc))
          .redirectionEndpoint(r -> r.baseUri("/api/auth/sso/callback/*"))
          .userInfoEndpoint(u -> u.oidcUserService(this::loadOidcUser))
          // The provider's token is not kept; the success handler signs in the portal user.
          .securityContextRepository(new RequestAttributeSecurityContextRepository())
          .successHandler(signIn.success)
          .failureHandler(signIn.failure));
    }
    if (props.getSaml().enabled()) {
      OpenSaml5AuthenticationProvider provider = samlProvider();
      http.saml2Login(s -> s
          .relyingPartyRegistrationRepository(samlParties)
          .authenticationManager(new ProviderManager(provider))
          .loginPage("/login")
          .authenticationRequestUri(SAML_START + "{registrationId}")
          .loginProcessingUrl(SAML_ACS + "{registrationId}")
          // The SAML token is not kept; the success handler signs in the portal user.
          .withObjectPostProcessor(new ObjectPostProcessor<Saml2WebSsoAuthenticationFilter>() {
            @Override
            public <O extends Saml2WebSsoAuthenticationFilter> O postProcess(O filter) {
              filter.setSecurityContextRepository(new RequestAttributeSecurityContextRepository());
              return filter;
            }
          })
          .successHandler(signIn.success)
          .failureHandler(signIn.failure));
      http.saml2Metadata(m -> m.metadataUrl("/api/auth/sso/saml/metadata"));
    }
  }

  // ---------------------------------------------------------------------------
  // OIDC
  // ---------------------------------------------------------------------------

  private final OidcUserService oidcUsers = new OidcUserService();

  private OidcUser loadOidcUser(OidcUserRequest request) {
    OidcUser user = oidcUsers.loadUser(request);
    SsoProperties.Oidc cfg = props.getOidc();
    Object value = user.getAttributes().get(cfg.getUsernameClaim());
    if ("email".equals(cfg.getUsernameClaim()) && !Boolean.TRUE.equals(user.getEmailVerified())) {
      // An unverified address is whatever the user typed at the provider: never an identity.
      throw new SsoSignIn.MappingException("unverified_email", value == null ? null : value.toString(),
          "The provider did not mark the email address as verified (email_verified)");
    }
    Collection<String> groups = strings(user.getAttributes().get(cfg.getGroupsClaim()));
    SsoSignIn.Identity identity = signIn.map("oidc", cfg, value == null ? null : value.toString(), groups);
    return new MappedOidcUser(user, identity);
  }

  /** The provider's user with the portal user it maps to. */
  record MappedOidcUser(OidcUser user, SsoSignIn.Identity identity) implements OidcUser, SsoSignIn.HasIdentity {
    @Override
    public Map<String, Object> getClaims() {
      return user.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
      return user.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
      return user.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
      return user.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
      return AuthorityUtils.NO_AUTHORITIES;
    }

    @Override
    public String getName() {
      return identity.user().username();
    }
  }

  /** Access and refresh tokens are not needed after sign-in, so none are stored. */
  private static final OAuth2AuthorizedClientRepository NO_AUTHORIZED_CLIENTS = new OAuth2AuthorizedClientRepository() {
    @Override
    public <T extends org.springframework.security.oauth2.client.OAuth2AuthorizedClient> T loadAuthorizedClient(String id,
        org.springframework.security.core.Authentication principal, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(org.springframework.security.oauth2.client.OAuth2AuthorizedClient client,
        org.springframework.security.core.Authentication principal, HttpServletRequest request, HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(String id, org.springframework.security.core.Authentication principal, HttpServletRequest request,
        HttpServletResponse response) {}
  };

  // ---------------------------------------------------------------------------
  // SAML
  // ---------------------------------------------------------------------------

  OpenSaml5AuthenticationProvider samlProvider() {
    SsoProperties.Saml cfg = props.getSaml();
    OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
    provider.setAssertionValidator(OpenSaml5AuthenticationProvider.createDefaultAssertionValidatorWithParameters(
        p -> p.put(SAML2AssertionValidationParameters.CLOCK_SKEW, cfg.getClockSkew())));
    var defaults = OpenSaml5AuthenticationProvider.createDefaultResponseValidator();
    provider.setResponseValidator(token -> {
      Saml2ResponseValidatorResult result = defaults.convert(token);
      if (token.getResponse().getInResponseTo() == null) {
        // IdP-initiated (unsolicited) responses are refused: nothing ties them to this browser.
        result = result.concat(new Saml2Error(Saml2ErrorCodes.INVALID_IN_RESPONSE_TO,
            "The response answers no sign-in request of this portal (IdP-initiated sign-in is not accepted)"));
      }
      return result;
    });
    var convert = OpenSaml5AuthenticationProvider.createDefaultResponseAuthenticationConverter();
    provider.setResponseAuthenticationConverter(token -> {
      Saml2Authentication converted = convert.convert(token);
      for (Assertion a : token.getResponse().getAssertions()) {
        if (a.getID() == null || !state.firstUse(a.getID(), expiry(a))) {
          throw new Saml2AuthenticationException(new Saml2Error(Saml2ErrorCodes.INVALID_ASSERTION, "The assertion was already used (replay)"));
        }
      }
      Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) converted.getPrincipal();
      String value = cfg.getUsernameAttribute().isBlank() ? principal.getName() : principal.getFirstAttribute(cfg.getUsernameAttribute()) instanceof Object o ? o.toString() : null;
      Collection<String> groups = cfg.getGroupsAttribute().isBlank() ? List.of() : strings(principal.getAttribute(cfg.getGroupsAttribute()));
      return new SamlAuthentication(signIn.map("saml", cfg, value, groups));
    });
    return provider;
  }

  /** How long an assertion id must be remembered: until the assertion could no longer be accepted. */
  private Instant expiry(Assertion a) {
    Instant latest = null;
    if (a.getConditions() != null && a.getConditions().getNotOnOrAfter() != null) latest = a.getConditions().getNotOnOrAfter();
    if (a.getSubject() != null) {
      for (SubjectConfirmation c : a.getSubject().getSubjectConfirmations()) {
        SubjectConfirmationData d = c.getSubjectConfirmationData();
        if (d != null && d.getNotOnOrAfter() != null && (latest == null || d.getNotOnOrAfter().isAfter(latest))) latest = d.getNotOnOrAfter();
      }
    }
    Instant base = latest != null ? latest : Instant.now().plus(java.time.Duration.ofHours(1));
    return base.plus(props.getSaml().getClockSkew());
  }

  static Collection<String> strings(Object claim) {
    if (claim == null) return List.of();
    if (claim instanceof Collection<?> c) return c.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    return List.of(claim.toString());
  }

  /** A SAML sign-in mapped to a portal user. */
  static final class SamlAuthentication extends AbstractAuthenticationToken implements SsoSignIn.HasIdentity {
    private final SsoSignIn.Identity identity;

    SamlAuthentication(SsoSignIn.Identity identity) {
      super(AuthorityUtils.NO_AUTHORITIES);
      this.identity = identity;
      setAuthenticated(true);
    }

    @Override
    public SsoSignIn.Identity identity() {
      return identity;
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return identity.user();
    }
  }

  // ---------------------------------------------------------------------------
  // Consent
  // ---------------------------------------------------------------------------

  /**
   * No sign-on starts until the notice-and-consent banner is acknowledged (refusal audited). Starts
   * are anonymous and each keeps a sign-in request for 10 minutes, so a client address may start
   * at most SSO_START_LIMIT_PER_MINUTE (per replica; counted with the API token limiter): more are
   * sent back to the login page with {@code sso_error=busy} and Retry-After, keeping nothing.
   */
  final class ConsentGate extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      String uri = request.getRequestURI();
      return !uri.startsWith(OIDC_START) && !uri.startsWith(SAML_START);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      if (consent.required(request)) {
        signIn.deny(request.getRequestURI().startsWith(SAML_START) ? "saml" : "oidc", null,
            "The notice and consent banner has not been acknowledged", ConsentService.CONSENT_REQUIRED);
        SsoSignIn.redirect(request, response, "/login?sso_error=consent");
        return;
      }
      long wait = limiter.acquire("sso-start:" + request.getRemoteAddr(), props.getStartLimitPerMinute());
      if (wait > 0) {
        response.setHeader("Retry-After", Long.toString(wait));
        SsoSignIn.redirect(request, response, "/login?sso_error=busy");
        return;
      }
      if (!state.acceptsMore()) {
        response.setHeader("Retry-After", "60");
        SsoSignIn.redirect(request, response, "/login?sso_error=busy");
        return;
      }
      chain.doFilter(request, response);
    }
  }
}
