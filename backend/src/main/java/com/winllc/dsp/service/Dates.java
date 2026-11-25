package com.winllc.dsp.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Dates as date fields hold them: {@code yyyy-mm-dd}, or an LDAP generalized time
 * ({@code yyyymmdd} followed by a time, e.g. {@code 20261231000000Z}), of which the date counts.
 */
public final class Dates {
  private static final DateTimeFormatter COMPACT = DateTimeFormatter.BASIC_ISO_DATE;

  private Dates() {}

  /** The date of {@code v}, or null when it isn't one. */
  public static LocalDate parse(String v) {
    if (v == null) return null;
    String s = v.trim();
    try {
      if (s.length() == 10 && s.charAt(4) == '-') return LocalDate.parse(s);
      if (s.length() >= 8 && s.substring(0, 8).chars().allMatch(Character::isDigit)) return LocalDate.parse(s.substring(0, 8), COMPACT);
    } catch (DateTimeParseException e) {
      return null;
    }
    return null;
  }

  /** {@code date} written the way {@code like} is (a generalized time keeps its form). */
  public static String format(LocalDate date, String like) {
    if (like != null && like.trim().length() >= 8 && Character.isDigit(like.trim().charAt(4))) {
      return date.format(COMPACT) + like.trim().substring(8);
    }
    return date.toString();
  }
}
