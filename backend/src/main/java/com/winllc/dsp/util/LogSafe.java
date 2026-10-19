package com.winllc.dsp.util;

/**
 * Values from users, identity providers or stored data, made safe for one log line: control
 * characters (CR and LF included) are escaped, so a value can't forge or split log entries, and
 * long values are cut.
 */
public final class LogSafe {
  static final int MAX = 300;

  private LogSafe() {}

  public static String of(Object value) {
    if (value == null) return "null";
    String s = value.toString();
    StringBuilder out = new StringBuilder(Math.min(s.length(), MAX) + 8);
    for (int i = 0; i < s.length() && out.length() < MAX; i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\r' -> out.append("\\r");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> {
          if (Character.isISOControl(c) || c == ' ' || c == ' ') out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    if (out.length() >= MAX && s.length() > MAX) out.append("...");
    return out.toString();
  }
}
