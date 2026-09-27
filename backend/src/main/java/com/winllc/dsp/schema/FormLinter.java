package com.winllc.dsp.schema;

import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.ObjectClassDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;

/** Structural and schema checks for forms. Errors block saving; warnings are advisory. */
public final class FormLinter {
  private FormLinter() {}

  public record Lint(List<String> errors, List<String> warnings) {}

  public static Lint lint(String name, List<String> objectClasses, String rdnAttribute, List<FormField> fields, SchemaIndex index) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (name == null || name.isBlank()) errors.add("Form name is required");
    if (objectClasses == null || objectClasses.isEmpty()) errors.add("At least one object class is required");
    if (fields == null || fields.isEmpty()) errors.add("At least one field is required");
    List<String> classes = objectClasses == null ? List.of() : objectClasses;
    List<FormField> fs = fields == null ? List.of() : fields;

    Set<String> seen = new HashSet<>();
    for (FormField f : fs) {
      String key = f.attribute().toLowerCase();
      if (!seen.add(key)) errors.add("Attribute \"" + f.attribute() + "\" is used by more than one field");
      if (f.dropdownWidget()) {
        DropdownSource dd = f.dropdown();
        if (dd == null) errors.add("Drop down \"" + f.label() + "\" has no option source");
        else if (dd instanceof DropdownSource.Static s && s.options().isEmpty() && !f.allowsCustomValues()) {
          errors.add("Drop down \"" + f.label() + "\" has no options");
        }
      }
      if (f.pattern() != null && !f.pattern().isEmpty()) {
        try {
          java.util.regex.Pattern.compile(f.pattern());
        } catch (PatternSyntaxException e) {
          errors.add("Field \"" + f.label() + "\" has an invalid pattern");
        }
      }
      if (index != null) {
        AttributeTypeDef at = index.attribute(f.attribute());
        if (at == null) {
          warnings.add("Attribute \"" + f.attribute() + "\" is not defined in the schema");
        } else {
          if (f.multiValued() && index.isSingleValue(f.attribute())) {
            errors.add("\"" + f.attribute() + "\" is SINGLE-VALUE in the schema and cannot be multi-valued");
          }
          if (at.noUserModification() && !f.readOnly()) {
            warnings.add("\"" + f.attribute() + "\" is NO-USER-MODIFICATION; consider making the field read only");
          }
        }
      }
    }
    if (rdnAttribute == null || rdnAttribute.isBlank()) {
      errors.add("A naming (RDN) attribute is required");
    } else if (fs.stream().noneMatch(f -> f.attribute().equalsIgnoreCase(rdnAttribute))) {
      errors.add("The naming attribute \"" + rdnAttribute + "\" must be one of the form fields");
    }

    if (index != null && !classes.isEmpty()) {
      List<String> unknown = classes.stream().filter(c -> index.objectClass(c) == null).toList();
      if (!unknown.isEmpty()) warnings.add("Unknown object class(es): " + String.join(", ", unknown));
      List<ObjectClassDef> expanded = index.expandClasses(classes);
      if (!expanded.isEmpty() && expanded.stream().noneMatch(c -> "STRUCTURAL".equals(c.kind()))) {
        warnings.add("No STRUCTURAL object class selected; the directory will reject new entries");
      }
      SchemaIndex.ClassAttributes ca = index.classAttributes(classes);
      Set<String> fieldPrimary = new HashSet<>();
      for (FormField f : fs) fieldPrimary.add(index.primaryAttributeName(f.attribute()).toLowerCase());
      List<String> missing = ca.must().stream().filter(m -> !fieldPrimary.contains(m.toLowerCase())).toList();
      if (!missing.isEmpty()) warnings.add("Required by the object classes but not in the form: " + String.join(", ", missing));
      boolean extensible = expanded.stream().anyMatch(c -> c.names().stream().anyMatch(n -> n.equalsIgnoreCase("extensibleObject")));
      if (!extensible) {
        Set<String> allowed = new HashSet<>();
        ca.must().forEach(a -> allowed.add(a.toLowerCase()));
        ca.may().forEach(a -> allowed.add(a.toLowerCase()));
        for (FormField f : fs) {
          if (index.attribute(f.attribute()) != null && !allowed.contains(index.primaryAttributeName(f.attribute()).toLowerCase())) {
            warnings.add("\"" + f.attribute() + "\" is not allowed by the selected object classes");
          }
        }
      }
    }
    return new Lint(errors, warnings);
  }
}
