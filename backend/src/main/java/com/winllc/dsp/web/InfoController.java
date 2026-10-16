package com.winllc.dsp.web;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.ServerInfo;
import com.winllc.dsp.x509.X509AuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {
  public static final String VERSION = "0.1.0";

  private final LdapDirectory directory;
  private final PortalProperties props;
  private final X509AuthService x509;

  public InfoController(LdapDirectory directory, PortalProperties props, X509AuthService x509) {
    this.directory = directory;
    this.props = props;
    this.x509 = x509;
  }

  @GetMapping("/api/health")
  public Map<String, Boolean> health() {
    return Map.of("ok", true);
  }

  @GetMapping("/api/info")
  public ServerInfo info() {
    String mode = props.isMemoryMode() ? "memory" : "ldap";
    String url = props.isMemoryMode() ? null : directory.url();
    PortalProperties.Banner b = props.getBanner();
    // Colors are validated at startup; re-check so an invalid value can never reach the page styles.
    ServerInfo.Banner banner = b.enabled() && b.problems().isEmpty()
        ? new ServerInfo.Banner(b.getText().trim(), b.getForeground().trim(), b.getBackground().trim())
        : null;
    String hint = props.getLoginHint() == null || props.getLoginHint().isBlank() ? null : props.getLoginHint();
    ServerInfo.AuthOptions auth = new ServerInfo.AuthOptions(
        // Certificate sign-in is offered only when the license includes it (smart-card-sign-in).
        props.isPasswordLoginEnabled(), x509.available(), x509.available() && props.getX509().isAutoLogin());
    try {
      directory.ping();
      return new ServerInfo(mode, url, props.getBaseDn(), true, null, VERSION, auth, hint, banner);
    } catch (RuntimeException e) {
      return new ServerInfo(mode, url, props.getBaseDn(), false, e.getMessage(), VERSION, auth, hint, banner);
    }
  }
}
