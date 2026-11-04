package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.List;

/**
 * The authenticated user, stored in the HTTP session. {@code authMethod} is "password",
 * "x509", "oidc" or "saml"; {@code certificateSubject} is set for certificate sign-ins.
 * {@code directory} is the id of the LDAP directory the user signed in to: a user is (directory, DN).
 * Null in sessions of earlier versions, which means the primary directory.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionUser(
    String username,
    String dn,
    String displayName,
    String mail,
    List<String> groups,
    @JsonProperty("isAdmin") boolean isAdmin,
    String authMethod,
    String certificateSubject,
    String directory)
    implements Serializable {

  public SessionUser(String username, String dn, String displayName, String mail, List<String> groups, boolean isAdmin, String authMethod,
      String certificateSubject) {
    this(username, dn, displayName, mail, groups, isAdmin, authMethod, certificateSubject, null);
  }

  /** The same user with administrator rights (granted by a single sign-on group mapping). */
  public SessionUser asAdmin() {
    return new SessionUser(username, dn, displayName, mail, groups, true, authMethod, certificateSubject, directory);
  }

  /**
   * Identifies the user among every directory's users (jobs, session limits): the user name for the
   * primary directory, as before there were several (sessions of earlier versions have no
   * directory), else {@code <directory>:<username>}.
   */
  public String key() {
    return directory == null || directory.isBlank() || directory.equals(com.winllc.dsp.config.DirectoryProperties.DEFAULT_ID)
        ? username : directory + ":" + username;
  }
}
