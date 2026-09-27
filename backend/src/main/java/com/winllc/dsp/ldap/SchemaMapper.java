package com.winllc.dsp.ldap;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.AttributeUsage;
import com.unboundid.ldap.sdk.schema.ObjectClassDefinition;
import com.unboundid.ldap.sdk.schema.ObjectClassType;
import com.unboundid.ldap.sdk.schema.Schema;
import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.SchemaSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts between UnboundID schema objects, RFC 4512 text and the portal's schema records. */
public final class SchemaMapper {
  private SchemaMapper() {}

  public static SchemaSnapshot toSnapshot(Schema schema, String subschemaDn) {
    List<AttributeTypeDef> attrs = schema.getAttributeTypes().stream().map(a -> toDef(a, "server")).toList();
    List<ObjectClassDef> classes = schema.getObjectClasses().stream().map(o -> toDef(o, "server")).toList();
    return new SchemaSnapshot(attrs, classes, Instant.now().toString(), subschemaDn);
  }

  private static List<String> names(String[] names, String oid) {
    return names == null || names.length == 0 ? List.of(oid) : List.of(names);
  }

  private static List<String> list(String[] values) {
    return values == null ? List.of() : List.of(values);
  }

  public static AttributeTypeDef toDef(AttributeTypeDefinition a, String source) {
    AttributeUsage usage = a.getUsage();
    return new AttributeTypeDef(
        a.getOID(),
        names(a.getNames(), a.getOID()),
        a.getDescription(),
        a.getSuperiorType(),
        a.getEqualityMatchingRule(),
        a.getOrderingMatchingRule(),
        a.getSubstringMatchingRule(),
        a.getSyntaxOID(),
        a.isSingleValued(),
        a.isCollective() ? Boolean.TRUE : null,
        a.isNoUserModification(),
        a.isObsolete() ? Boolean.TRUE : null,
        usage == null || usage == AttributeUsage.USER_APPLICATIONS ? null : usage.getName(),
        source,
        null);
  }

  public static ObjectClassDef toDef(ObjectClassDefinition o, String source) {
    ObjectClassType type = o.getObjectClassType();
    return new ObjectClassDef(
        o.getOID(),
        names(o.getNames(), o.getOID()),
        o.getDescription(),
        list(o.getSuperiorClasses()),
        type == null ? "STRUCTURAL" : type.getName(),
        list(o.getRequiredAttributes()),
        list(o.getOptionalAttributes()),
        o.isObsolete() ? Boolean.TRUE : null,
        source,
        null);
  }

  private static String[] arr(List<String> l) {
    return l == null ? new String[0] : l.toArray(String[]::new);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  /** RFC 4512 attribute type description. */
  public static String format(AttributeTypeDef a) {
    AttributeUsage usage = a.usage() == null ? null : AttributeUsage.forName(a.usage());
    return new AttributeTypeDefinition(
            a.oid(), arr(a.names()), blankToNull(a.desc()), Boolean.TRUE.equals(a.obsolete()), blankToNull(a.sup()),
            blankToNull(a.equality()), blankToNull(a.ordering()), blankToNull(a.substr()), blankToNull(a.syntax()),
            a.singleValue(), Boolean.TRUE.equals(a.collective()), a.noUserModification(), usage, null)
        .toString();
  }

  /** RFC 4512 object class description. */
  public static String format(ObjectClassDef o) {
    return new ObjectClassDefinition(
            o.oid(), arr(o.names()), blankToNull(o.desc()), Boolean.TRUE.equals(o.obsolete()), arr(o.sup()),
            ObjectClassType.forName(o.kind() == null ? "STRUCTURAL" : o.kind()), arr(o.must()), arr(o.may()), null)
        .toString();
  }

  public record ParsedText(List<AttributeTypeDef> attributeTypes, List<ObjectClassDef> objectClasses, List<String> errors) {}

  private static final Pattern DEFINITION_START = Pattern.compile(
      "(olcAttributeTypes|attributeTypes|attributetype|olcObjectClasses|objectClasses|objectclass)\\s*:?\\s*(\\{\\d+\\})?\\s*\\(",
      Pattern.CASE_INSENSITIVE);

  /**
   * Parse free-form schema text: an OpenLDAP .schema file, a cn=config LDIF
   * (olcAttributeTypes / olcObjectClasses), a subschema LDIF (attributeTypes: / objectClasses:)
   * or bare descriptions prefixed with attributetype / objectclass.
   */
  public static ParsedText parseText(String text, String source) {
    List<AttributeTypeDef> attrs = new ArrayList<>();
    List<ObjectClassDef> classes = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    // Unfold LDIF continuation lines and drop comment lines.
    String unfolded = String.join("\n",
        Arrays.stream(text.replace("\r\n", "\n").replaceAll("\n[ \t]", " ").split("\n"))
            .filter(l -> !l.strip().startsWith("#"))
            .toList());

    Matcher m = DEFINITION_START.matcher(unfolded);
    int from = 0;
    while (from < unfolded.length() && m.find(from)) {
      int open = m.end() - 1;
      int end = matchingParen(unfolded, open);
      if (end < 0) {
        errors.add("Unbalanced parentheses in definition starting at offset " + m.start());
        break;
      }
      String body = unfolded.substring(open, end + 1);
      boolean isAttr = m.group(1).toLowerCase().contains("attribute");
      try {
        if (isAttr) attrs.add(toDef(new AttributeTypeDefinition(body), source));
        else classes.add(toDef(new ObjectClassDefinition(body), source));
      } catch (LDAPException e) {
        errors.add((isAttr ? "Attribute type: " : "Object class: ") + e.getMessage());
      }
      from = end + 1;
    }
    return new ParsedText(attrs, classes, errors);
  }

  private static int matchingParen(String s, int open) {
    int depth = 0;
    boolean quoted = false;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\'') quoted = !quoted;
      if (quoted) continue;
      if (c == '(') depth++;
      if (c == ')' && --depth == 0) return i;
    }
    return -1;
  }
}
