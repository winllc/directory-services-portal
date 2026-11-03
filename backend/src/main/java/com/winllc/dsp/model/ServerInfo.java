package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfo(String mode, String ldapUrl, String baseDn, boolean connected, String error, String version, AuthOptions auth, String loginHint, Banner banner,
    Consent consent) {

  /** Fixed banner shown at the top and bottom of every page; null when disabled. */
  public record Banner(String text, String foreground, String background) {}

  /** The notice-and-consent banner shown before sign-in (plain text, paragraphs separated by blank lines); null when disabled. */
  public record Consent(String title, String text) {}

  /**
   * Sign-in methods offered by the login page. {@code directories}: the LDAP directories a user
   * signs in to with a password, when there is more than one to choose from (else absent).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AuthOptions(boolean password, boolean x509, boolean x509AutoLogin, java.util.List<Sso> sso,
      java.util.List<DirectoryOption> directories) {}

  /** A directory to sign in to: its id (sent with the user name) and name (shown). */
  public record DirectoryOption(String id, String name) {}

  /** A single sign-on button: where the browser goes to sign in with that provider. */
  public record Sso(String id, String label, String url) {}
}
