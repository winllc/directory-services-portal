package com.winllc.dsp.web;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.ServerInfo;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.ConsentService;
import com.winllc.dsp.x509.X509AuthService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {
  public static final String VERSION = "0.1.0";

  private final LdapDirectory directory;
  private final PortalProperties props;
  private final X509AuthService x509;
  private final ConsentService consent;

  public InfoController(LdapDirectory directory, PortalProperties props, X509AuthService x509, ConsentService consent) {
    this.directory = directory;
    this.props = props;
    this.x509 = x509;
    this.consent = consent;
  }

  /**
   * Kept for monitoring set up before the actuator probes: {"ok":true} while the API runs, whatever
   * the directory or the audit log (liveness only). Probes use /actuator/health/liveness and
   * /actuator/health/readiness (or /livez and /readyz).
   */
  @GetMapping("/api/health")
  public Map<String, Boolean> health() {
    return Map.of("ok", true);
  }

  /**
   * What the client needs before and after sign-in. Anonymous callers get the sign-in options,
   * banners and whether the directory answers; the base DN only signed-in users, and the LDAP URL
   * and the directory's error text only administrators.
   */
  @GetMapping("/api/info")
  public ServerInfo info(@AuthenticationPrincipal SessionUser user) {
    boolean admin = user != null && user.isAdmin();
    String mode = props.isMemoryMode() ? "memory" : "ldap";
    String url = props.isMemoryMode() || !admin ? null : directory.url();
    String baseDn = user == null ? null : props.getBaseDn();
    PortalProperties.Banner b = props.getBanner();
    // Colors are validated at startup; re-check so an invalid value can never reach the page styles.
    ServerInfo.Banner banner = b.enabled() && b.problems().isEmpty()
        ? new ServerInfo.Banner(b.getText().trim(), b.getForeground().trim(), b.getBackground().trim())
        : null;
    String hint = props.getLoginHint() == null || props.getLoginHint().isBlank() ? null : props.getLoginHint();
    ServerInfo.AuthOptions auth = new ServerInfo.AuthOptions(
        // Certificate sign-in is offered only when the license includes it (smart-card-sign-in).
        props.isPasswordLoginEnabled(), x509.available(), x509.available() && props.getX509().isAutoLogin());
    ServerInfo.Consent notice = consent.enabled() ? new ServerInfo.Consent(consent.title(), consent.text()) : null;
    try {
      directory.ping();
      return new ServerInfo(mode, url, baseDn, true, null, VERSION, auth, hint, banner, notice);
    } catch (RuntimeException e) {
      return new ServerInfo(mode, url, baseDn, false, admin ? e.getMessage() : null, VERSION, auth, hint, banner, notice);
    }
  }
}
