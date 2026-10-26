package com.winllc.dsp.sso;

import java.io.Serializable;
import java.util.List;

/**
 * What a SAML single logout needs to name the IdP's session: the assertion's NameID (and its
 * format) and session indexes. Kept in the portal session (attribute {@link #ATTRIBUTE}) after a
 * SAML sign-in while single logout is on; identifiers, not credentials.
 */
public record SamlLogout(String nameId, String nameIdFormat, List<String> sessionIndexes) implements Serializable {
  public static final String ATTRIBUTE = "dsp.sso.samlLogout";

  public SamlLogout {
    sessionIndexes = sessionIndexes == null ? List.of() : List.copyOf(sessionIndexes);
  }
}
