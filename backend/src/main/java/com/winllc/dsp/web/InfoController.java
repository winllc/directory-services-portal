package com.winllc.dsp.web;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Directories;
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

  private final Directories directories;
  private final PortalProperties props;
  private final X509AuthService x509;
  private final ConsentService consent;
  private final com.winllc.dsp.sso.SsoSecurity sso;
  /** Whether the primary directory answered, reused for {@link #REACH_TTL}: anyone may call /api/info. */
  private volatile Reach reach;

  static final java.time.Duration REACH_TTL = java.time.Duration.ofSeconds(10);

  /** {@code error} is null when the directory answered. */
  private record Reach(long at, String directory, String error) {}

  public InfoController(Directories directories, PortalProperties props, X509AuthService x509, ConsentService consent,
      com.winllc.dsp.sso.SsoSecurity sso) {
    this.sso = sso;
    this.directories = directories;
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
    // The primary directory's (several: see /api/admin/directories); a user's own directory's base DN.
    Directories.Directory primary = directories.primary();
    String url = props.isMemoryMode() || !admin ? null : primary.ldap().url();
    String baseDn = user == null ? null : directories.find(user.directory()).orElse(primary).config().getBaseDn();
    PortalProperties.Banner b = props.getBanner();
    // Colors are validated at startup; re-check so an invalid value can never reach the page styles.
    ServerInfo.Banner banner = b.enabled() && b.problems().isEmpty()
        ? new ServerInfo.Banner(b.getText().trim(), b.getForeground().trim(), b.getBackground().trim())
        : null;
    String hint = props.getLoginHint() == null || props.getLoginHint().isBlank() ? null : props.getLoginHint();
    ServerInfo.AuthOptions auth = new ServerInfo.AuthOptions(
        // Certificate sign-in is offered only when the license includes it (smart-card-sign-in).
        props.isPasswordLoginEnabled(), x509.available(), x509.available() && props.getX509().isAutoLogin(),
        sso.options().stream().map(o -> new ServerInfo.Sso(o.id(), o.label(), o.url())).toList(),
        directories.several() ? directories.enabled().stream().map(d -> new ServerInfo.DirectoryOption(d.id(), d.name())).toList() : null);
    ServerInfo.Consent notice = consent.enabled() ? new ServerInfo.Consent(consent.title(), consent.text()) : null;
    Reach r = reach(primary);
    return new ServerInfo(mode, url, baseDn, r.error() == null, admin ? r.error() : null, VERSION, auth, hint, banner, notice);
  }

  private Reach reach(Directories.Directory primary) {
    Reach r = reach;
    long now = System.nanoTime();
    if (r != null && r.directory().equals(primary.id()) && now - r.at() < REACH_TTL.toNanos()) return r;
    String error = null;
    try {
      primary.ldap().ping();
    } catch (RuntimeException e) {
      error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    r = new Reach(now, primary.id(), error);
    reach = r;
    return r;
  }
}
