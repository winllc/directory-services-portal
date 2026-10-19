package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.List;

/**
 * The authenticated user, stored in the HTTP session. {@code authMethod} is "password",
 * "x509", "oidc" or "saml"; {@code certificateSubject} is set for certificate sign-ins.
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
    String certificateSubject)
    implements Serializable {

  /** The same user with administrator rights (granted by a single sign-on group mapping). */
  public SessionUser asAdmin() {
    return new SessionUser(username, dn, displayName, mail, groups, true, authMethod, certificateSubject);
  }
}
