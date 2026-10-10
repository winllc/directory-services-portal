package com.winllc.dsp.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Regular expressions supplied by users (metric transforms, analysis rules), evaluated with a
 * time budget per match so a catastrophically backtracking pattern can't tie up a request thread.
 */
public final class SafeRegex {
  public static final int MAX_PATTERN_LENGTH = 500;
  private static final long BUDGET_NANOS = 50_000_000L;

  /** Thrown when a single match exceeds its time budget. */
  public static final class TimeoutException extends RuntimeException {
    TimeoutException() {
      super("The regular expression took too long to evaluate", null, false, false);
    }
  }

  private final Pattern pattern;

  private SafeRegex(Pattern pattern) {
    this.pattern = pattern;
  }

  /** Compile, or throw IllegalArgumentException with a readable message. */
  public static SafeRegex compile(String regex, boolean anchored, boolean ignoreCase) {
    if (regex == null || regex.isEmpty()) throw new IllegalArgumentException("The pattern is empty");
    if (regex.length() > MAX_PATTERN_LENGTH) throw new IllegalArgumentException("The pattern is too long");
    try {
      String source = anchored ? "^(?:" + regex + ")$" : regex;
      return new SafeRegex(Pattern.compile(source, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0));
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid pattern: " + e.getDescription());
    }
  }

  public String pattern() {
    return pattern.pattern();
  }

  public boolean matches(String input) {
    return matcher(input).matches();
  }

  /** The first capture group of the first match (or the whole match without groups); null when nothing matches. */
  public String extract(String input) {
    Matcher m = matcher(input);
    if (!m.find()) return null;
    return m.groupCount() >= 1 ? m.group(1) : m.group();
  }

  private Matcher matcher(String input) {
    return pattern.matcher(new Deadline(input, System.nanoTime() + BUDGET_NANOS));
  }

  /** A CharSequence that fails once its deadline passes; the regex engine reads every character through it. */
  private static final class Deadline implements CharSequence {
    private final CharSequence inner;
    private final long deadline;
    private int reads;

    Deadline(CharSequence inner, long deadline) {
      this.inner = inner;
      this.deadline = deadline;
    }

    @Override
    public char charAt(int index) {
      // Backtracking re-reads the same few characters, so count reads rather than positions.
      if ((++reads & 0x3ff) == 0 && System.nanoTime() > deadline) throw new TimeoutException();
      return inner.charAt(index);
    }

    @Override
    public int length() {
      return inner.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return new Deadline(inner.subSequence(start, end), deadline);
    }

    @Override
    public String toString() {
      return inner.toString();
    }
  }
}
