package com.winllc.dsp.schema;

import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.SelfMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Attribute matching for self service ({@link SelfMatch.Attribute}) is only as strong as its
 * attributes: one users can change in self service lets them claim an entry that is not theirs
 * (a user who can edit {@code mail} sets it to a colleague's and matches the colleague's entry).
 * So matching is refused while either side is self-editable:
 *
 * <ul>
 *   <li>the entry attribute, in the definition's own form (the user edits the matched entry);
 *   <li>the user attribute (unless it is the user's DN), in the form of any definition with self
 *       service on (that is where a user can edit their own entry).
 * </ul>
 *
 * The form linter warns about the same, before a form is saved.
 */
public final class SelfMatchCheck {
  private SelfMatchCheck() {}

  private static String key(String attribute, SchemaIndex index) {
    String name = index == null ? attribute : index.primaryAttributeName(attribute);
    return name.toLowerCase(Locale.ROOT);
  }

  private static boolean selfEditable(List<FormField> fields, String attribute, SchemaIndex index) {
    if (fields == null) return false;
    String wanted = key(attribute, index);
    return fields.stream().anyMatch(f -> f.selfEditable() && !f.readOnly() && key(f.attribute(), index).equals(wanted));
  }

  private static boolean selfService(DirectoryDefinition d) {
    return !d.readOnly() && d.selfMatch() != null && !(d.selfMatch() instanceof SelfMatch.None);
  }

  private static FormDefinition form(List<FormDefinition> forms, String id) {
    return forms.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
  }

  /** Why {@code def}'s attribute matching can't be trusted, or null when it can (or it doesn't match by attribute). */
  public static String problem(DirectoryDefinition def, List<DirectoryDefinition> definitions, List<FormDefinition> forms, SchemaIndex index) {
    if (!(def.selfMatch() instanceof SelfMatch.Attribute am)) return null;
    FormDefinition own = form(forms, def.formId());
    if (!def.readOnly() && own != null && selfEditable(own.fields(), am.entryAttribute(), index)) {
      return "users can edit \"" + am.entryAttribute() + "\", the attribute it matches entries by, in self service";
    }
    if (!"dn".equalsIgnoreCase(am.userAttribute())) {
      for (DirectoryDefinition d : definitions) {
        FormDefinition f = selfService(d) ? form(forms, d.formId()) : null;
        if (f != null && selfEditable(f.fields(), am.userAttribute(), index)) {
          return "users can edit \"" + am.userAttribute() + "\", the attribute of their own entry it matches by, in self service (\""
              + d.name() + "\")";
        }
      }
    }
    return null;
  }

  /**
   * Form linter warnings: self-editable fields of the form {@code formId} (null for a new one) that
   * would turn off attribute matching in a definition.
   */
  public static List<String> lintWarnings(String formId, List<FormField> fields, List<DirectoryDefinition> definitions, SchemaIndex index) {
    List<String> out = new ArrayList<>();
    for (DirectoryDefinition d : definitions) {
      if (!(d.selfMatch() instanceof SelfMatch.Attribute am)) continue;
      if (formId != null && formId.equals(d.formId()) && !d.readOnly() && selfEditable(fields, am.entryAttribute(), index)) {
        out.add("\"" + am.entryAttribute() + "\" is editable in self service, but \"" + d.name() + "\" finds a user's own entry by it:"
            + " a user could claim another entry, so self service there is refused until the field is not self-editable");
      }
      if (!"dn".equalsIgnoreCase(am.userAttribute()) && selfEditable(fields, am.userAttribute(), index)) {
        out.add("\"" + am.userAttribute() + "\" is editable in self service, but \"" + d.name() + "\" matches users by it: a user could"
            + " claim another entry, so self service there is refused while a self-service form lets users edit it");
      }
    }
    return out;
  }
}
