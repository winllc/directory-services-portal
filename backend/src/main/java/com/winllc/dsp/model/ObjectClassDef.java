package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** An LDAP object class (RFC 4512 section 4.1.1). {@code kind} is STRUCTURAL, AUXILIARY or ABSTRACT. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObjectClassDef(
    String oid,
    List<String> names,
    String desc,
    List<String> sup,
    String kind,
    List<String> must,
    List<String> may,
    Boolean obsolete,
    String source,
    String displayName) {

  public String primaryName() {
    return names == null || names.isEmpty() ? oid : names.get(0);
  }

  public ObjectClassDef withSource(String newSource) {
    return new ObjectClassDef(oid, names, desc, sup, kind, must, may, obsolete, newSource, displayName);
  }
}
