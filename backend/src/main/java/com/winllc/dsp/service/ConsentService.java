package com.winllc.dsp.service;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The DoD notice-and-consent banner (DISA STIG): while it is enabled, no sign-in of any kind
 * (password, certificate, certificate auto-login) succeeds until this browser session has
 * acknowledged it. The acknowledgement is kept in the session, which survives the session id
 * change at sign-in and ends with sign-out, so the banner is shown before every sign-in.
 *
 * <p>Machine endpoints that are not browser sessions are exempt: the audit pull API
 * ({@code /api/audit/**}, bearer tokens), the health probes and the Prometheus endpoint.
 */
@Service
public class ConsentService {
  public static final String CONSENT_REQUIRED = "consent_required";
  static final String ACKNOWLEDGED = ConsentService.class.getName() + ".acknowledged";

  private final EnsProperties ens;
  private final AuditLog audit;

  public ConsentService(EnsProperties ens, AuditLog audit) {
    this.ens = ens;
    this.audit = audit;
  }

  public boolean enabled() {
    return ens.getConsentBanner().isEnabled();
  }

  public String title() {
    return ens.getConsentBanner().effectiveTitle();
  }

  public String text() {
    return ens.getConsentBanner().effectiveText();
  }

  /** Whether this browser session must still acknowledge the banner before signing in. */
  public boolean required(HttpServletRequest request) {
    if (!enabled()) return false;
    HttpSession session = request.getSession(false);
    return session == null || !Boolean.TRUE.equals(session.getAttribute(ACKNOWLEDGED));
  }

  /**
   * Records the acknowledgement in the session, audited as {@code auth.consent-acknowledged} with
   * the client address first: an acknowledgement that can't be audited isn't kept.
   */
  public void acknowledge(HttpServletRequest request) {
    if (!enabled()) return;
    HttpSession session = request.getSession(true);
    audit.event("auth.consent-acknowledged").target("banner", "notice-and-consent", title()).success();
    session.setAttribute(ACKNOWLEDGED, Boolean.TRUE);
  }

  /**
   * Carries an acknowledgement given before a single sign-on over to the session the sign-on
   * creates (the provider's answer arrives without the session cookie). Already audited when given.
   */
  public void carryOver(HttpServletRequest request) {
    if (!enabled()) return;
    request.getSession(true).setAttribute(ACKNOWLEDGED, Boolean.TRUE);
  }

  /** Refuses a sign-in (403, code {@value #CONSENT_REQUIRED}) until the banner is acknowledged. */
  public void requireAcknowledged(HttpServletRequest request, AuditLog.Draft signIn) {
    if (!required(request)) return;
    signIn.detail("reasonCode", CONSENT_REQUIRED).denied("The notice and consent banner has not been acknowledged");
    throw new ApiException(HttpStatus.FORBIDDEN, "Read and acknowledge the notice and consent banner before signing in", CONSENT_REQUIRED);
  }
}
