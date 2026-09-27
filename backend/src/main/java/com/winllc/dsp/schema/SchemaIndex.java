package com.winllc.dsp.schema;

import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.SchemaSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Case-insensitive lookups over a (merged) schema snapshot. */
public class SchemaIndex {
  private final SchemaSnapshot schema;
  private final Map<String, AttributeTypeDef> attrs = new HashMap<>();
  private final Map<String, ObjectClassDef> classes = new HashMap<>();

  public SchemaIndex(SchemaSnapshot schema) {
    this.schema = schema;
    for (AttributeTypeDef a : schema.attributeTypes()) {
      attrs.put(a.oid().toLowerCase(), a);
      for (String n : a.names()) attrs.put(n.toLowerCase(), a);
    }
    for (ObjectClassDef c : schema.objectClasses()) {
      classes.put(c.oid().toLowerCase(), c);
      for (String n : c.names()) classes.put(n.toLowerCase(), c);
    }
  }

  public SchemaSnapshot schema() {
    return schema;
  }

  public AttributeTypeDef attribute(String name) {
    return name == null ? null : attrs.get(name.toLowerCase());
  }

  public ObjectClassDef objectClass(String name) {
    return name == null ? null : classes.get(name.toLowerCase());
  }

  public String primaryAttributeName(String name) {
    AttributeTypeDef a = attribute(name);
    return a == null ? name : a.primaryName();
  }

  /** Follows SUP chains so inherited SINGLE-VALUE is honoured. */
  public boolean isSingleValue(String name) {
    Set<String> seen = new HashSet<>();
    AttributeTypeDef a = attribute(name);
    while (a != null && seen.add(a.oid())) {
      if (a.singleValue()) return true;
      a = a.sup() == null ? null : attribute(a.sup());
    }
    return false;
  }

  /** Object classes including all superclasses. */
  public List<ObjectClassDef> expandClasses(List<String> names) {
    List<ObjectClassDef> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String n : names) visit(n, out, seen);
    return out;
  }

  private void visit(String name, List<ObjectClassDef> out, Set<String> seen) {
    ObjectClassDef c = objectClass(name);
    if (c == null || !seen.add(c.oid())) return;
    out.add(c);
    if (c.sup() != null) for (String s : c.sup()) visit(s, out, seen);
  }

  public record ClassAttributes(List<String> must, List<String> may) {}

  /** MUST and MAY attributes (primary names) for a set of object classes, including inherited ones. */
  public ClassAttributes classAttributes(List<String> names) {
    Map<String, String> must = new LinkedHashMap<>();
    Map<String, String> may = new LinkedHashMap<>();
    List<ObjectClassDef> expanded = expandClasses(names);
    for (ObjectClassDef c : expanded) {
      for (String a : c.must()) {
        String p = primaryAttributeName(a);
        must.put(p.toLowerCase(), p);
      }
    }
    for (ObjectClassDef c : expanded) {
      for (String a : c.may()) {
        String p = primaryAttributeName(a);
        if (!must.containsKey(p.toLowerCase())) may.put(p.toLowerCase(), p);
      }
    }
    must.remove("objectclass");
    return new ClassAttributes(new ArrayList<>(must.values()), new ArrayList<>(may.values()));
  }
}
