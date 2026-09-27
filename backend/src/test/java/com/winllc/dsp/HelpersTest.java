package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.sdk.schema.Schema;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.SchemaMapper;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.schema.FormLinter;
import com.winllc.dsp.schema.SchemaIndex;
import com.winllc.dsp.service.FormValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HelpersTest {

  private static FormField field(String id, String attr, String widget, boolean multi, boolean required, boolean readOnly, boolean self, String format, DropdownSource dd) {
    return new FormField(id, attr, attr, null, widget, multi, required, readOnly, self, format, null, null, null, dd, null, null, null);
  }

  private static final FormDefinition FORM = new FormDefinition("f", "T", null, List.of("inetOrgPerson"), "uid", List.of(
      field("uid", "uid", "text", false, true, false, false, null, null),
      field("mail", "mail", "text", true, false, false, true, "email", null),
      field("dept", "departmentNumber", "dropdown", false, false, false, false, null,
          new DropdownSource.Static(List.of(new OptionItem("A", null), new OptionItem("B", null)))),
      field("badge", "acmeBadgeNumber", "text", false, false, true, false, null, null)), null, null);

  @Test
  void dnHelpersEscapeAndCompareSafely() {
    String dn = Dns.build("cn", "Smith, Jr. <x>", "ou=people,dc=example,dc=com");
    assertThat(Dns.rdnValue(dn)).isEqualTo("Smith, Jr. <x>");
    assertThat(Dns.equal("UID=Alice, OU=People,DC=Example,DC=com", "uid=alice,ou=people,dc=example,dc=com")).isTrue();
    assertThat(Dns.isUnder("uid=a,ou=people,dc=example,dc=com", "ou=people,dc=example,dc=com", true)).isTrue();
    assertThat(Dns.isUnder("ou=people,dc=example,dc=com", "ou=people,dc=example,dc=com", true)).isFalse();
    // An escaped comma in a value must not be mistaken for a namespace boundary.
    assertThat(Dns.isUnder("cn=x\\,ou=people,dc=evil,dc=example,dc=com", "ou=people,dc=example,dc=com", false)).isFalse();
    assertThat(Dns.depthBelow("uid=a,ou=people,dc=example,dc=com", "dc=example,dc=com")).isEqualTo(2);
    assertThat(Dns.isValid("not a dn")).isFalse();
  }

  @Test
  void parsesSchemaTextInSeveralFormats() {
    String ldif = """
        dn: cn=acme,cn=schema,cn=config
        olcAttributeTypes: {0}( 1.2.3.4.1 NAME 'fooAttr' DESC 'foo'
          SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )
        olcObjectClasses: {0}( 1.2.3.4.2 NAME 'fooClass' SUP top AUXILIARY MAY fooAttr )
        """;
    SchemaMapper.ParsedText parsed = SchemaMapper.parseText(ldif, "custom");
    assertThat(parsed.errors()).isEmpty();
    assertThat(parsed.attributeTypes().get(0).names()).containsExactly("fooAttr");
    assertThat(parsed.objectClasses().get(0).kind()).isEqualTo("AUXILIARY");
    assertThat(parsed.objectClasses().get(0).may()).containsExactly("fooAttr");

    String roundTrip = SchemaMapper.format(parsed.attributeTypes().get(0));
    assertThat(SchemaMapper.parseText("attributetype " + roundTrip, "custom").attributeTypes().get(0).desc()).isEqualTo("foo");

    SchemaMapper.ParsedText broken = SchemaMapper.parseText("attributetype ( 1.2.3 NAME 'x' SYNTAX ", "custom");
    assertThat(broken.errors()).isNotEmpty();
  }

  @Test
  void validatesRequiredSingleValueFormatsAndOptions() {
    FormValidator.Result r = FormValidator.validate(FORM,
        Map.of("uid", List.of("a", "b"), "mail", List.of("nope"), "departmentNumber", "C"), FormValidator.Mode.CREATE, null);
    assertThat(r.errors()).containsOnlyKeys("uid", "mail", "departmentnumber");
  }

  @Test
  void dropsReadOnlyAndNonSelfEditableFields() {
    FormValidator.Result r = FormValidator.validate(FORM,
        Map.of("uid", "x", "mail", List.of("a@b.co"), "acmeBadgeNumber", "1", "evil", "x"), FormValidator.Mode.SELF, null);
    assertThat(r.errors()).isEmpty();
    assertThat(r.values()).isEqualTo(Map.of("mail", List.of("a@b.co")));
  }

  @Test
  void lintsFormsAgainstTheSchema() throws Exception {
    SchemaIndex index = new SchemaIndex(SchemaMapper.toSnapshot(Schema.getDefaultStandardSchema(), "cn=schema"));
    List<FormField> fields = new java.util.ArrayList<>(FORM.fields());
    fields.add(field("dn2", "displayName", "text", true, false, false, false, null, null));
    FormLinter.Lint lint = FormLinter.lint("T", List.of("inetOrgPerson"), "uid", fields, index);
    assertThat(lint.errors()).anyMatch(e -> e.contains("SINGLE-VALUE"));
    assertThat(lint.warnings()).anyMatch(w -> w.contains("sn"));
    assertThat(lint.warnings()).anyMatch(w -> w.contains("acmeBadgeNumber"));
  }
}
