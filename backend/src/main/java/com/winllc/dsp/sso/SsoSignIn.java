package com.winllc.dsp.sso;

import com.winllc.dsp.util.LogSafe;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditUnavailableException;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.AuthService;
import com.winllc.dsp.service.ConsentService;
import com.winllc.dsp.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Turns what an identity provider asserted into a portal sign-in, the same for OIDC and SAML:
 * the asserted identity must match exactly one directory entry (the provider's user filter), whose
 * groups and administrator status are resolved as for password sign-in. The provider's groups add
 * administrator rights only through the configured {@code admin-groups}. Every outcome is audited
 * as {@code auth.login} (method {@code oidc} or {@code saml}); a sign-in that can't be audited
 * fails. The browser then returns to the portal: {@code /} signed in, or
 * {@code /login?sso_error=<code>}.
 */
@Component
public class SsoSignIn {
  private static final Logger log = LoggerFactory.getLogger(SsoSignIn.class);

  /** The portal user an assertion maps to, and whether a provider group made them administrator. */
  /**
   * {@code alsoMatched}: other directories (ids) that also hold an account matching the asserted
   * identity; the user signed in to the first in configured order.
   */
  public record Identity(String method, String asserted, SessionUser user, boolean idpAdmin, List<String> alsoMatched) implements Serializable {

    public Identity(String method, String asserted, SessionUser user, boolean idpAdmin) {
      this(method, asserted, user, idpAdmin, List.of());
    }

    public List<String> alsoMatched() {
      return alsoMatched == null ? List.of() : alsoMatched;
    }
  }

  /** Carries an {@link Identity} through Spring Security's login filters. */
  public interface HasIdentity {
    Identity identity();
  }

  /** The assertion was valid but can't be mapped to a portal user. */
  public static class MappingException extends AuthenticationException {
    private final String code;
    private final String asserted;

    public MappingException(String code, String asserted, String message) {
      super(message);
      this.code = code;
      this.asserted = asserted;
    }

    public String code() {
      return code;
    }

    public String asserted() {
      return asserted;
    }
  }

  private final Directories directories;
  private final PortalProperties props;
  private final AuthService auth;
  private final SessionService sessions;
  private final ConsentService consent;
  private final AuditLog audit;
  private final SecurityContextRepository contexts;

  public SsoSignIn(Directories directories, PortalProperties props, AuthService auth, SessionService sessions, ConsentService consent,
      AuditLog audit, SecurityContextRepository contexts) {
    this.contexts = contexts;
    this.directories = directories;
    this.props = props;
    this.auth = auth;
    this.sessions = sessions;
    this.consent = consent;
    this.audit = audit;
  }

  /**
   * The portal user for an asserted identity.
   *
   * @throws MappingException when no single directory entry matches
   */
  public Identity map(String method, SsoProperties.Provider provider, String asserted, Collection<String> idpGroups) {
    if (asserted == null || asserted.isBlank()) {
      throw new MappingException("no_identity", null, "The identity provider did not send the identity the portal maps users by");
    }
    String value = asserted.trim();
    if (value.length() > 256) throw new MappingException("no_account", value.substring(0, 256), "The asserted identity is too long");
    // Every enabled directory that takes single sign-on (its sso setting, default true) is searched,
    // in configured order, with its own sso-user-filter or the provider's. The first directory with a
    // match wins and must hold exactly one; later directories that also match are only reported.
    List<DirectoryEntry> found = List.of();
    Directories.Directory home = null;
    List<String> alsoMatched = new java.util.ArrayList<>();
    for (Directories.Directory dir : directories.enabled()) {
      if (!dir.config().isSso()) continue;
      String template = dir.config().getSsoUserFilter() == null || dir.config().getSsoUserFilter().isBlank()
          ? provider.getUserFilter() : dir.config().getSsoUserFilter();
      Filter filter = AuthService.template(template, Map.of("value", value));
      List<DirectoryEntry> hits = dir.ldap().search(dir.config().effectiveUserSearchBase(), SearchScope.SUB, filter, 2, List.of("1.1")).entries();
      if (hits.isEmpty()) continue;
      if (home == null) {
        home = dir;
        found = hits;
      } else {
        alsoMatched.add(dir.id());
      }
    }
    if (found.isEmpty()) throw new MappingException("no_account", value, "No directory entry matches the asserted identity");
    if (found.size() > 1) throw new MappingException("no_account", value, "More than one directory entry matches the asserted identity");
    if (!alsoMatched.isEmpty()) {
      log.warn("Single sign-on ({}): the asserted identity matches accounts in several directories: signed in to {}, the first in"
          + " configured order; also matched: {}", method, home.id(), String.join(", ", alsoMatched));
    }
    SessionUser user = auth.buildUser(home, found.get(0).dn(), value, method, null);
    List<String> adminGroups = provider.adminGroupList();
    // idpAdmin: administrator only through the provider's groups (the directory doesn't say so).
    boolean idpAdmin = !user.isAdmin() && idpGroups != null
        && idpGroups.stream().anyMatch(g -> adminGroups.stream().anyMatch(a -> a.equalsIgnoreCase(g)));
    if (idpAdmin) user = user.asAdmin();
    return new Identity(method, value, user, idpAdmin, List.copyOf(alsoMatched));
  }

  public final AuthenticationSuccessHandler success = this::succeeded;
  public final AuthenticationFailureHandler failure = this::failed;

  private void succeeded(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
    Identity id = authentication.getPrincipal() instanceof HasIdentity h ? h.identity()
        : authentication instanceof HasIdentity h ? h.identity() : null;
    // Whatever the login filter may have stored is dropped: only the portal user below is signed in.
    SecurityContextHolder.clearContext();
    contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    if (id == null) {
      redirect(request, response, "/login?sso_error=failed");
      return;
    }
    SsoRequests.Pending pending = request.getAttribute(SsoRequests.PENDING) instanceof SsoRequests.Pending p ? p : null;
    if (consent.enabled() && (pending == null || !pending.consentAcknowledged())) {
      deny(id.method(), id.asserted(), "The notice and consent banner has not been acknowledged", ConsentService.CONSENT_REQUIRED);
      redirect(request, response, "/login?sso_error=consent");
      return;
    }
    try {
      // Recorded before the session exists: a sign-in that can't be audited doesn't happen.
      audit.event("auth.login").by(id.user()).target("user", id.user().dn(), id.user().displayName()).detail("method", id.method())
          .directory(id.user().directory())
          .detail("alsoMatched", id.alsoMatched().isEmpty() ? null : String.join(",", id.alsoMatched()))
          .detail("idpAdmin", id.idpAdmin() ? Boolean.TRUE : null).success();
    } catch (AuditUnavailableException e) {
      redirect(request, response, "/login?sso_error=unavailable");
      return;
    }
    sessions.establish(id.user(), request, response);
    if (id.idpAdmin()) request.getSession().setAttribute(SessionService.IDP_ADMIN, Boolean.TRUE);
    // SAML single logout names the IdP's session by these (identifiers only, never a credential).
    if (authentication instanceof SsoSecurity.SamlAuthentication saml && saml.logout() != null) {
      request.getSession().setAttribute(SamlLogout.ATTRIBUTE, saml.logout());
    }
    if (pending != null && pending.consentAcknowledged()) consent.carryOver(request);
    redirect(request, response, "/");
  }

  private void failed(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
    String method = request.getRequestURI().contains("/saml/") ? "saml" : "oidc";
    String code = "failed";
    String asserted = null;
    Throwable t = e;
    while (t != null && !(t instanceof MappingException)) t = t.getCause();
    if (t instanceof MappingException m) {
      code = m.code();
      asserted = m.asserted();
    }
    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    if (e instanceof OAuth2AuthenticationException o && o.getError() != null) {
      reason = o.getError().getErrorCode() + (o.getError().getDescription() == null ? "" : ": " + o.getError().getDescription());
    } else if (e instanceof Saml2AuthenticationException s && s.getSaml2Error() != null) {
      reason = s.getSaml2Error().getErrorCode() + ": " + s.getSaml2Error().getDescription();
    }
    log.warn("Single sign-on ({}) refused: {}", method, LogSafe.of(reason));
    try {
      audit.event("auth.login").anonymous(asserted).target("user", asserted, null).detail("method", method).detail("reasonCode", code)
          .failed(reason);
    } catch (AuditUnavailableException ignored) {
      // Refused either way.
    }
    redirect(request, response, "/login?sso_error=" + code);
  }

  /** Refused before reaching the provider (the banner was not acknowledged), audited. */
  public void deny(String method, String asserted, String reason, String code) {
    try {
      audit.event("auth.login").anonymous(asserted).target("user", asserted, null).detail("method", method).detail("reasonCode", code)
          .denied(reason);
    } catch (AuditUnavailableException ignored) {
      // Refused either way.
    }
  }

  static void redirect(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
    response.sendRedirect(request.getContextPath() + path);
  }
}
