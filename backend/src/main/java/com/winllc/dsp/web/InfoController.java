package com.winllc.dsp.web;

import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.ServerInfo;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {
  public static final String VERSION = "0.1.0";

  private final LdapDirectory directory;
  private final PortalProperties props;

  public InfoController(LdapDirectory directory, PortalProperties props) {
    this.directory = directory;
    this.props = props;
  }

  @GetMapping("/api/health")
  public Map<String, Boolean> health() {
    return Map.of("ok", true);
  }

  @GetMapping("/api/info")
  public ServerInfo info() {
    String mode = props.isMemoryMode() ? "memory" : "ldap";
    String url = props.isMemoryMode() ? null : directory.url();
    ServerInfo.AuthOptions auth = new ServerInfo.AuthOptions(
        props.isPasswordLoginEnabled(), props.getX509().isEnabled(), props.getX509().isEnabled() && props.getX509().isAutoLogin());
    try {
      directory.ping();
      return new ServerInfo(mode, url, props.getBaseDn(), true, null, VERSION, auth);
    } catch (RuntimeException e) {
      return new ServerInfo(mode, url, props.getBaseDn(), false, e.getMessage(), VERSION, auth);
    }
  }
}
