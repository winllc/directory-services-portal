package com.winllc.dsp.metrics;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.util.StaticUtils;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.util.SafeRegex;
import java.text.ParseException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a raw attribute value into the bucket it is counted under, e.g. an e-mail address into
 * its domain, a DN into its RDN value or parent, or a timestamp into its month. A null result
 * means the value has no bucket (a regex that doesn't match, a date that can't be parsed).
 */
public final class ValueTransform {
  private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}.*");

  /** A value's bucket: {@code key} groups (case-insensitively), {@code label} is shown. */
  public record Bucket(String key, String label) {}

  private final String type;
  private final int prefixLength;
  private final SafeRegex regex;

  private ValueTransform(String type, int prefixLength, SafeRegex regex) {
    this.type = type;
    this.prefixLength = prefixLength;
    this.regex = regex;
  }

  /** Validates the transform's argument; throws IllegalArgumentException with a readable message. */
  public static ValueTransform of(String type, String arg) {
    String t = type == null || type.isBlank() ? "none" : type;
    return switch (t) {
      case "prefix" -> {
        int n;
        try {
          n = Integer.parseInt(arg == null ? "" : arg.trim());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Enter how many leading characters to keep");
        }
        if (n < 1 || n > 64) throw new IllegalArgumentException("Keep between 1 and 64 leading characters");
        yield new ValueTransform(t, n, null);
      }
      case "regex" -> new ValueTransform(t, 0, SafeRegex.compile(arg, false, false));
      default -> new ValueTransform(t, 0, null);
    };
  }

  public boolean isDate() {
    return DateBucket.isDateUnit(type);
  }

  public Bucket apply(String raw) {
    if (raw == null) return null;
    String v = raw.trim();
    if (v.isEmpty()) return null;
    switch (type) {
      case "lowercase" -> v = v.toLowerCase(Locale.ROOT);
      case "uppercase" -> v = v.toUpperCase(Locale.ROOT);
      case "emailDomain" -> {
        int at = v.lastIndexOf('@');
        if (at < 0 || at == v.length() - 1) return null;
        v = v.substring(at + 1).toLowerCase(Locale.ROOT);
      }
      case "dnRdn" -> {
        if (Dns.isValid(v)) v = Dns.rdnValue(v);
      }
      case "dnParent" -> {
        if (!Dns.isValid(v)) return null;
        DN parent = Dns.parse(v).getParent();
        if (parent == null || parent.isNullDN()) return new Bucket("", "(root)");
        return new Bucket(parent.toNormalizedString(), parent.toString());
      }
      case "prefix" -> v = v.substring(0, Math.min(prefixLength, v.length()));
      case "regex" -> {
        v = regex.extract(v);
        if (v == null || v.isEmpty()) return null;
      }
      case "year", "quarter", "month", "week", "day" -> {
        LocalDate d = parseDate(v);
        if (d == null) return null;
        DateBucket unit = DateBucket.of(type);
        LocalDate start = unit.start(d);
        return new Bucket(unit.key(start), unit.label(start));
      }
      default -> {}
    }
    return new Bucket(v.toLowerCase(Locale.ROOT), v);
  }

  /** LDAP GeneralizedTime (20260314093000Z), an ISO date or an ISO timestamp, as a UTC date. */
  public static LocalDate parseDate(String v) {
    String s = v.trim();
    if (s.isEmpty()) return null;
    if (ISO_DATE.matcher(s).matches()) {
      try {
        if (s.length() == 10) return LocalDate.parse(s);
        return OffsetDateTime.parse(s).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
      } catch (DateTimeParseException e) {
        try {
          return Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException e2) {
          try {
            return LocalDate.parse(s.substring(0, 10));
          } catch (DateTimeParseException e3) {
            return null;
          }
        }
      }
    }
    if (s.length() >= 10 && Character.isDigit(s.charAt(0))) {
      try {
        return StaticUtils.decodeGeneralizedTime(s).toInstant().atZone(ZoneOffset.UTC).toLocalDate();
      } catch (ParseException | RuntimeException e) {
        return null;
      }
    }
    return null;
  }

  /** Calendar periods used by date transforms and timelines. */
  public enum DateBucket {
    DAY, WEEK, MONTH, QUARTER, YEAR;

    static boolean isDateUnit(String t) {
      return switch (t) {
        case "day", "week", "month", "quarter", "year" -> true;
        default -> false;
      };
    }

    public static DateBucket of(String unit) {
      return valueOf((unit == null ? "month" : unit).toUpperCase(Locale.ROOT));
    }

    public LocalDate start(LocalDate d) {
      return switch (this) {
        case DAY -> d;
        case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        case MONTH -> d.withDayOfMonth(1);
        case QUARTER -> LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
        case YEAR -> LocalDate.of(d.getYear(), 1, 1);
      };
    }

    public LocalDate next(LocalDate start) {
      return switch (this) {
        case DAY -> start.plusDays(1);
        case WEEK -> start.plusWeeks(1);
        case MONTH -> start.plusMonths(1);
        case QUARTER -> start.plusMonths(3);
        case YEAR -> start.plusYears(1);
      };
    }

    public String key(LocalDate start) {
      return switch (this) {
        case DAY -> start.toString();
        case WEEK -> String.format("%d-W%02d", start.get(IsoFields.WEEK_BASED_YEAR), start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        case MONTH -> start.toString().substring(0, 7);
        case QUARTER -> start.getYear() + "-Q" + start.get(IsoFields.QUARTER_OF_YEAR);
        case YEAR -> String.valueOf(start.getYear());
      };
    }

    public String label(LocalDate start) {
      return switch (this) {
        case DAY -> start.getDayOfMonth() + " " + start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + start.getYear();
        case WEEK -> "Week of " + start;
        case MONTH -> start.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + start.getYear();
        case QUARTER -> "Q" + start.get(IsoFields.QUARTER_OF_YEAR) + " " + start.getYear();
        case YEAR -> String.valueOf(start.getYear());
      };
    }
  }
}
