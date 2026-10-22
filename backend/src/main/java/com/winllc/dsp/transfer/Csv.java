package com.winllc.dsp.transfer;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV for entry export and import.
 *
 * <ul>
 *   <li>Formula injection: a cell that starts with {@code = + - @}, a tab or a carriage return (and
 *       is not a plain number) is written with a leading apostrophe, so spreadsheets show it as text
 *       instead of running it. On import, an apostrophe before one of those characters is removed
 *       again, so an exported file imports unchanged.
 *   <li>Several values of a multi-valued attribute share a cell, separated by {@code |}; a
 *       {@code |} or {@code \} inside a value is written {@code \|} or {@code \\}.
 * </ul>
 */
public final class Csv {
  public static final String BOM = "﻿";

  private Csv() {}

  /**
   * True when a spreadsheet might run the cell as a formula: it starts with a tab or CR, or with
   * {@code = + - @} after any leading spaces, tabs or line breaks (spreadsheets trim those), and is
   * not a plain number.
   */
  static boolean risky(String s) {
    if (s.isEmpty()) return false;
    char first = s.charAt(0);
    if (first == '\t' || first == '\r') return true;
    int i = 0;
    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r' || s.charAt(i) == '\n')) i++;
    if (i == s.length()) return false;
    char c = s.charAt(i);
    return (c == '=' || c == '+' || c == '-' || c == '@') && !s.trim().matches("-?\\d+(\\.\\d+)?");
  }

  /** One cell, guarded and quoted as needed. */
  public static String cell(String value) {
    String s = value == null ? "" : value;
    if (risky(s)) s = "'" + s;
    if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) s = "\"" + s.replace("\"", "\"\"") + "\"";
    return s;
  }

  public static void row(StringBuilder out, List<String> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) out.append(',');
      out.append(cell(cells.get(i)));
    }
    out.append("\r\n");
  }

  /** Several values in one cell (escaped, joined with |). */
  public static String join(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append('|');
      sb.append(values.get(i).replace("\\", "\\\\").replace("|", "\\|"));
    }
    return sb.toString();
  }

  /** The values of a multi-valued cell (the reverse of {@link #join}). */
  public static List<String> split(String cell) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (int i = 0; i < cell.length(); i++) {
      char c = cell.charAt(i);
      if (c == '\\' && i + 1 < cell.length() && (cell.charAt(i + 1) == '|' || cell.charAt(i + 1) == '\\')) {
        cur.append(cell.charAt(++i));
      } else if (c == '|') {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(c);
      }
    }
    out.add(cur.toString());
    return out;
  }

  /**
   * Removes the formula guard an export added: a {@code '} followed by text the guard protects
   * ({@link #risky}). Only then: an apostrophe before anything else is part of the value. Ambiguous
   * by nature: a value that itself starts with {@code '} followed by such text (e.g. {@code '=x})
   * is read back without its apostrophe.
   */
  public static String unguard(String cell) {
    return cell.length() > 1 && cell.charAt(0) == '\'' && risky(cell.substring(1)) ? cell.substring(1) : cell;
  }

  /** A parsed record and the line it starts on. */
  public record Record(int line, List<String> cells) {}

  /**
   * Parses CSV text (a leading byte-order mark is ignored); blank lines are skipped. At most
   * {@code maxRecords} records are returned; {@code over} tells whether there were more.
   *
   * @throws IllegalArgumentException on an unterminated quoted cell
   */
  public static List<Record> parse(String text, int maxRecords, boolean[] over) {
    String s = text.startsWith(BOM) ? text.substring(1) : text;
    List<Record> out = new ArrayList<>();
    List<String> cells = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    int line = 1;
    int start = 1;
    boolean quoted = false;
    boolean any = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          if (c == '\n') line++;
          cur.append(c);
        }
        continue;
      }
      switch (c) {
        case '"' -> {
          quoted = true;
          any = true;
        }
        case ',' -> {
          cells.add(cur.toString());
          cur.setLength(0);
          any = true;
        }
        case '\r' -> {}
        case '\n' -> {
          if (any || cur.length() > 0) {
            cells.add(cur.toString());
            if (out.size() >= maxRecords) {
              over[0] = true;
              return out;
            }
            out.add(new Record(start, List.copyOf(cells)));
          }
          cells.clear();
          cur.setLength(0);
          any = false;
          line++;
          start = line;
        }
        default -> {
          cur.append(c);
          any = true;
        }
      }
    }
    if (quoted) throw new IllegalArgumentException("Line " + start + ": a quoted cell is not closed");
    if (any || cur.length() > 0) {
      cells.add(cur.toString());
      if (out.size() >= maxRecords) over[0] = true;
      else out.add(new Record(start, List.copyOf(cells)));
    }
    return out;
  }
}
