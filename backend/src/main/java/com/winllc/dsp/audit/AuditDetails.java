package com.winllc.dsp.audit;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rules for {@code details}: a flat object of strings, numbers, booleans and nulls; strings cut to
 * 1000 characters; secrets replaced with {@code [redacted]}; changes as {@code <field>.old} and
 * {@code <field>.new}.
 */
public final class AuditDetails {
  public static final String REDACTED = "[redacted]";
  static final int MAX_STRING = 1000;

  /**
   * Keys whose values are never recorded: passwords (LDAP userPassword, unicodePwd and the like),
   * tokens, API keys, private keys, license text, session ids, one-time codes, PINs and seeds.
   */
  private static final Pattern SECRET = Pattern.compile(
      "(password|passwd|pwd|secret|token|apikey|api[_-]key|privatekey|private[_-]key|krbprincipalkey|passcode|"
          + "tokencode|otp|sessionid|session[_-]id|licensetext|license[_-]text|seed|credential)");

  private AuditDetails() {}

  public static boolean isSecret(String key) {
    if (key == null) return false;
    String k = key.toLowerCase(Locale.ROOT);
    if (k.endsWith(".old")) k = k.substring(0, k.length() - 4);
    else if (k.endsWith(".new")) k = k.substring(0, k.length() - 4);
    int semi = k.indexOf(';');
    if (semi > 0) k = k.substring(0, semi);
    return SECRET.matcher(k).find() || k.endsWith("pin");
  }

  /** Puts one value, redacted or truncated as needed (anything not scalar is recorded as text). */
  public static void put(Map<String, Object> details, String key, Object value) {
    if (key == null) return;
    details.put(key, clean(key, value));
  }

  static Object clean(String key, Object value) {
    if (value == null) return null;
    if (isSecret(key)) return REDACTED;
    if (value instanceof Boolean || value instanceof Number) return value;
    return truncate(String.valueOf(value));
  }

  public static String truncate(String s) {
    return s == null || s.length() <= MAX_STRING ? s : s.substring(0, MAX_STRING);
  }

  /** {@code <attribute>.old} and {@code <attribute>.new}: one value as text, several as a JSON array, none as null. */
  public static void putChange(Map<String, Object> details, AuditEvent.Change change) {
    put(details, change.attribute() + ".old", values(change.before()));
    put(details, change.attribute() + ".new", values(change.after()));
  }

  static String values(List<String> values) {
    if (values == null || values.isEmpty()) return null;
    if (values.size() == 1) return values.get(0);
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      AuditJson.str(sb, values.get(i));
    }
    return sb.append(']').toString();
  }
}
