package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** An LDAP attribute type (RFC 4512 section 4.1.2), from the server or defined in the portal. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttributeTypeDef(
    String oid,
    List<String> names,
    String desc,
    String sup,
    String equality,
    String ordering,
    String substr,
    String syntax,
    boolean singleValue,
    Boolean collective,
    boolean noUserModification,
    Boolean obsolete,
    String usage,
    String source,
    String displayName) {

  public String primaryName() {
    return names == null || names.isEmpty() ? oid : names.get(0);
  }

  public AttributeTypeDef withSource(String newSource) {
    return new AttributeTypeDef(oid, names, desc, sup, equality, ordering, substr, syntax, singleValue,
        collective, noUserModification, obsolete, usage, newSource, displayName);
  }
}
