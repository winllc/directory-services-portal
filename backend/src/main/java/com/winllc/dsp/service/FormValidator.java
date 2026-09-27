package com.winllc.dsp.service;

import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.OptionItem;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Server-side validation of submitted entry values against a form. Mirrors the client-side
 * rules in {@code shared/src/validation.ts} so users see the same messages in both places.
 */
public final class FormValidator {
  private FormValidator() {}

  public enum Mode { CREATE, UPDATE, SELF }

  public record Result(Map<String, List<String>> values, Map<String, String> errors) {}

  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
  private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()./-]{3,}(\\s*(x|ext\\.?)\\s*\\d+)?$", Pattern.CASE_INSENSITIVE);
  private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");

  /** Trim, drop blanks and duplicates. Accepts a string, a list or null. */
  public static List<String> normalize(Object raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    List<?> list = raw instanceof List<?> l ? l : List.of(raw);
    for (Object v : list) {
      if (v == null) continue;
      String s = String.valueOf(v).trim();
      if (!s.isEmpty() && !out.contains(s)) out.add(s);
    }
    return out;
  }

  public static boolean isWritable(FormDefinition form, FormField field, Mode mode) {
    boolean isRdn = field.attribute().equalsIgnoreCase(form.rdnAttribute());
    return switch (mode) {
      case CREATE -> !field.readOnly() || isRdn;
      case UPDATE -> !field.readOnly();
      case SELF -> !field.readOnly() && field.selfEditable();
    };
  }

  public static String validateField(FormField field, List<String> values, List<OptionItem> resolvedOptions) {
    String label = field.label();
    if (field.required() && values.isEmpty()) return label + " is required";
    if (!field.multiValued() && values.size() > 1) return label + " accepts a single value";
    for (String v : values) {
      if (field.maxLength() != null && v.length() > field.maxLength()) {
        return label + " must be at most " + field.maxLength() + " characters";
      }
      if (field.dropdownWidget()) {
        List<OptionItem> options = field.dropdown() instanceof DropdownSource.Static s ? s.options() : resolvedOptions;
        if (options != null && !field.allowsCustomValues() && options.stream().noneMatch(o -> o.value().equals(v))) {
          return "\"" + v + "\" is not an allowed value for " + label;
        }
        continue;
      }
      String format = field.format() == null ? "plain" : field.format();
      switch (format) {
        case "email" -> {
          if (!EMAIL.matcher(v).matches()) return "\"" + v + "\" is not a valid e-mail address";
        }
        case "phone" -> {
          if (!PHONE.matcher(v).matches()) return "\"" + v + "\" is not a valid phone number";
        }
        case "number" -> {
          if (!NUMBER.matcher(v).matches()) return "\"" + v + "\" is not a number";
        }
        case "url" -> {
          if (!isUrl(v)) return "\"" + v + "\" is not a valid URL";
        }
        case "dn" -> {
          if (!Dns.isValid(v)) return "\"" + v + "\" is not a valid distinguished name";
        }
        default -> {}
      }
      if (field.pattern() != null && !field.pattern().isEmpty()) {
        Pattern p;
        try {
          p = Pattern.compile("^(?:" + field.pattern() + ")$");
        } catch (PatternSyntaxException e) {
          return label + " has an invalid validation pattern";
        }
        if (!p.matcher(v).matches()) return "\"" + v + "\" does not match the required format for " + label;
      }
    }
    return null;
  }

  private static boolean isUrl(String v) {
    try {
      URI u = new URI(v);
      return u.getScheme() != null && (u.getHost() != null || u.getSchemeSpecificPart() != null);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Validate input against the form. Unknown attributes and fields that are not writable in
   * {@code mode} are dropped. Outside CREATE only the attributes present are validated, which
   * allows partial updates. Result keys are lower-cased attribute names.
   */
  public static Result validate(FormDefinition form, Map<String, ?> input, Mode mode, Map<String, List<OptionItem>> resolvedOptions) {
    Map<String, Object> byAttr = new LinkedHashMap<>();
    if (input != null) input.forEach((k, v) -> byAttr.put(k.toLowerCase(), v));

    Map<String, List<String>> values = new LinkedHashMap<>();
    Map<String, String> errors = new LinkedHashMap<>();
    for (FormField field : form.fields()) {
      String key = field.attribute().toLowerCase();
      if (!isWritable(form, field, mode)) continue;
      boolean present = byAttr.containsKey(key);
      if (!present && mode != Mode.CREATE) continue;
      List<String> vals = normalize(byAttr.get(key));
      boolean isRdn = key.equalsIgnoreCase(form.rdnAttribute());
      if (isRdn && vals.isEmpty() && (mode == Mode.CREATE || present)) {
        errors.put(key, mode == Mode.CREATE ? field.label() + " is required (it names the entry)" : field.label() + " cannot be empty (it names the entry)");
        continue;
      }
      String err = validateField(field, vals, resolvedOptions == null ? null : resolvedOptions.get(field.id()));
      if (err != null) errors.put(key, err);
      else values.put(key, vals);
    }
    return new Result(values, errors);
  }
}
