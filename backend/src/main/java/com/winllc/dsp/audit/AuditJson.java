package com.winllc.dsp.audit;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The {@code ens-audit/1} serialization and integrity chain, written by hand so the bytes are
 * exactly those of the shared spec (field order, compact JSON, JavaScript {@code JSON.stringify}
 * string escaping) whatever JSON library or settings the application uses:
 *
 * <ul>
 *   <li>{@code body} is the event without {@code hash}, ending in {@code "prevHash":"..."}};
 *   <li>{@code hash = sha256hex(prevHash + "\n" + body)};
 *   <li>the stored line is the body with its final brace replaced by {@code ,"hash":"<hash>"}}.
 * </ul>
 */
public final class AuditJson {
  public static final String GENESIS = "0".repeat(64);
  /** {@code ,"hash":"<64 hex>"}} at the end of every stored line. */
  static final int HASH_SUFFIX_LENGTH = ",\"hash\":\"".length() + 64 + 2;

  private AuditJson() {}

  public static String body(AuditEvent e) {
    StringBuilder sb = new StringBuilder(512);
    sb.append("{\"schema\":");
    str(sb, AuditEvent.SCHEMA);
    sb.append(",\"id\":");
    str(sb, e.id());
    sb.append(",\"seq\":").append(e.seq());
    sb.append(",\"time\":");
    str(sb, e.time());
    sb.append(",\"product\":");
    str(sb, e.product());
    sb.append(",\"version\":");
    str(sb, e.version());
    sb.append(",\"host\":");
    str(sb, e.host());
    sb.append(",\"installationId\":");
    str(sb, e.installationId());
    sb.append(",\"category\":");
    str(sb, e.category());
    sb.append(",\"action\":");
    str(sb, e.action());
    sb.append(",\"outcome\":");
    str(sb, e.outcome());
    sb.append(",\"severity\":");
    str(sb, e.severity());
    sb.append(",\"actor\":");
    AuditEvent.Actor a = e.actor() == null ? AuditEvent.Actor.SYSTEM : e.actor();
    sb.append("{\"type\":");
    str(sb, a.type());
    sb.append(",\"id\":");
    str(sb, a.id());
    sb.append(",\"name\":");
    str(sb, a.name());
    sb.append(",\"roles\":[");
    List<String> roles = a.roles() == null ? List.of() : a.roles();
    for (int i = 0; i < roles.size(); i++) {
      if (i > 0) sb.append(',');
      str(sb, roles.get(i));
    }
    sb.append("]}");
    sb.append(",\"source\":");
    if (e.source() == null) {
      sb.append("null");
    } else {
      sb.append("{\"ip\":");
      str(sb, e.source().ip());
      sb.append(",\"userAgent\":");
      str(sb, e.source().userAgent());
      sb.append('}');
    }
    sb.append(",\"target\":");
    if (e.target() == null) {
      sb.append("null");
    } else {
      sb.append("{\"type\":");
      str(sb, e.target().type());
      sb.append(",\"id\":");
      str(sb, e.target().id());
      sb.append(",\"name\":");
      str(sb, e.target().name());
      sb.append('}');
    }
    sb.append(",\"reason\":");
    str(sb, e.reason());
    sb.append(",\"details\":{");
    boolean first = true;
    if (e.details() != null) {
      for (Map.Entry<String, Object> d : e.details().entrySet()) {
        if (!first) sb.append(',');
        first = false;
        str(sb, d.getKey());
        sb.append(':');
        value(sb, d.getValue());
      }
    }
    sb.append('}');
    sb.append(",\"requestId\":");
    str(sb, e.requestId());
    sb.append(",\"session\":");
    str(sb, e.session());
    sb.append(",\"prevHash\":");
    str(sb, e.prevHash());
    sb.append('}');
    return sb.toString();
  }

  public static String hash(String prevHash, String body) {
    return sha256Hex(prevHash + "\n" + body);
  }

  public static String line(String body, String hash) {
    return body.substring(0, body.length() - 1) + ",\"hash\":\"" + hash + "\"}";
  }

  /** The body a stored line was made from, or null when the line doesn't end in a hash field. */
  public static String bodyOf(String line) {
    if (line.length() <= HASH_SUFFIX_LENGTH || !line.endsWith("\"}")) return null;
    int at = line.length() - HASH_SUFFIX_LENGTH;
    if (!line.startsWith(",\"hash\":\"", at)) return null;
    return line.substring(0, at) + "}";
  }

  /** The hash a stored line carries (null when malformed). */
  public static String hashOf(String line) {
    if (bodyOf(line) == null) return null;
    return line.substring(line.length() - 66, line.length() - 2);
  }

  public static String sha256Hex(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void value(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof Boolean b) {
      sb.append(b);
    } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
      sb.append(((Number) v).longValue());
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
      else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
      else sb.append(BigDecimal.valueOf(d).stripTrailingZeros().toPlainString());
    } else {
      str(sb, String.valueOf(v));
    }
  }

  /** A JSON string as JavaScript's JSON.stringify writes it (null for null). */
  static void str(StringBuilder sb, String s) {
    if (s == null) {
      sb.append("null");
      return;
    }
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else if (Character.isSurrogate(c)) {
            // Well-formed pairs as is; a lone surrogate escaped (as JSON.stringify does).
            boolean pair = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            if (pair) {
              sb.append(c).append(s.charAt(++i));
            } else {
              sb.append(String.format("\\u%04x", (int) c));
            }
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
