package com.winllc.dsp.model;

import java.util.List;
import java.util.Map;

/** An LDAP entry. Attribute names are lower-cased; values are always lists of strings. */
public record DirectoryEntry(String dn, Map<String, List<String>> attributes) {
  public List<String> values(String attribute) {
    List<String> v = attributes.get(attribute.toLowerCase());
    return v == null ? List.of() : v;
  }

  public String first(String attribute) {
    List<String> v = values(attribute);
    return v.isEmpty() ? null : v.get(0);
  }
}
