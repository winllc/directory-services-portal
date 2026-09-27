package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfo(String mode, String ldapUrl, String baseDn, boolean connected, String error, String version, AuthOptions auth) {

  /** Sign-in methods offered by the login page. */
  public record AuthOptions(boolean password, boolean x509, boolean x509AutoLogin) {}
}
