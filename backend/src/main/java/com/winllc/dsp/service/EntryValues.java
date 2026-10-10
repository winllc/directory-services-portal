package com.winllc.dsp.service;

import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.schema.SchemaIndex;
import java.util.List;
import java.util.Map;

/**
 * Reads attribute values from scanned entries. Servers may return an attribute under a different
 * name than requested (an alias such as {@code surname} for {@code sn}, or with options), so a
 * miss falls back to comparing primary names from the schema. {@code dn} yields the entry DN.
 */
public final class EntryValues {
  private final SchemaIndex index;

  public EntryValues(SchemaIndex index) {
    this.index = index;
  }

  public List<String> get(DirectoryEntry entry, String attribute) {
    if (DataSources.DN.equalsIgnoreCase(attribute)) return List.of(entry.dn());
    List<String> v = entry.values(attribute);
    if (!v.isEmpty()) return v;
    String primary = index.primaryAttributeName(attribute);
    for (Map.Entry<String, List<String>> e : entry.attributes().entrySet()) {
      String name = e.getKey();
      int semi = name.indexOf(';');
      String base = semi > 0 ? name.substring(0, semi) : name;
      if (index.primaryAttributeName(base).equalsIgnoreCase(primary)) return e.getValue();
    }
    return List.of();
  }
}
