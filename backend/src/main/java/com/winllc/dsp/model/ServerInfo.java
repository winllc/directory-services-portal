package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfo(String mode, String ldapUrl, String baseDn, boolean connected, String error, String version, AuthOptions auth, String loginHint, Banner banner) {

  /** Fixed banner shown at the top and bottom of every page; null when disabled. */
  public record Banner(String text, String foreground, String background) {}

  /** Sign-in methods offered by the login page. */
  public record AuthOptions(boolean password, boolean x509, boolean x509AutoLogin) {}
}
