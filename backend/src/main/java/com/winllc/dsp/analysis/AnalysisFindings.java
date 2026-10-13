package com.winllc.dsp.analysis;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Filtering, paging and CSV export of an analysis result's findings (quality issues or comparison
 * rows), so large results are browsed a page at a time instead of sent whole.
 */
public final class AnalysisFindings {
  private AnalysisFindings() {}

  public static final int DEFAULT_PAGE_SIZE = 50;
  public static final int MAX_PAGE_SIZE = 500;

  /**
   * Filters: {@code q} (DN, entry or message for issues; key for rows), {@code rule},
   * {@code severity} and {@code source} for issues, {@code status} for rows.
   */
  public record Query(String q, String rule, String severity, Integer source, String status, int page, int pageSize) {
    public Query {
      page = Math.max(1, page);
      pageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
    }
  }

  /**
   * One page of findings. {@code total} counts the findings matching the filters; {@code listed}
   * counts every finding kept with the result (runs keep at most {@code max-issues}).
   */
  public record Page<T>(List<T> items, int total, int page, int pageSize, int listed) {}

  private static String needle(Query query) {
    return query.q() == null || query.q().isBlank() ? null : query.q().trim().toLowerCase(Locale.ROOT);
  }

  private static boolean contains(String haystack, String needle) {
    return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
  }

  static Predicate<AnalysisResult.Issue> issueFilter(Query query) {
    String needle = needle(query);
    return i -> (blank(query.rule()) || query.rule().equals(i.ruleId()))
        && (blank(query.severity()) || query.severity().equals(i.severity()))
        && (query.source() == null || query.source() == i.source())
        && (needle == null || contains(i.dn(), needle) || contains(i.title(), needle) || contains(i.message(), needle));
  }

  static Predicate<AnalysisResult.CompareRow> rowFilter(Query query) {
    String needle = needle(query);
    String status = query.status();
    return r -> (blank(status) || status.equals(r.status()) || ("duplicate".equals(status) && Boolean.TRUE.equals(r.duplicate())))
        && (needle == null || contains(r.key(), needle));
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static <T> Page<T> page(List<T> all, Predicate<T> filter, Query query) {
    int total = 0;
    int from = (query.page() - 1) * query.pageSize();
    List<T> items = new ArrayList<>();
    for (T t : all == null ? List.<T>of() : all) {
      if (!filter.test(t)) continue;
      if (total >= from && items.size() < query.pageSize()) items.add(t);
      total++;
    }
    return new Page<>(items, total, query.page(), query.pageSize(), all == null ? 0 : all.size());
  }

  /** A page of the result's findings: issues for a quality check, rows for a comparison. */
  public static Page<?> page(AnalysisResult result, Query query) {
    if (result.quality() != null) return page(result.quality().issues(), issueFilter(query), query);
    return page(result.compare().rows(), rowFilter(query), query);
  }

  // ---------------------------------------------------------------------------
  // CSV
  // ---------------------------------------------------------------------------

  private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");

  /** RFC 4180 cell; text starting with = + - @ is prefixed so spreadsheets don't run it as a formula. */
  static String cell(Object v) {
    String s = v == null ? "" : v.toString();
    if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !NUMBER.matcher(s).matches()) s = "'" + s;
    if (s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
      s = '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  private static void line(Writer w, List<?> cells) throws IOException {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) w.write(',');
      w.write(cell(cells.get(i)));
    }
    w.write("\r\n");
  }

  private static String joined(List<String> values) {
    return values == null ? "" : String.join(" | ", values);
  }

  private static final Map<String, String> STATUS_LABEL = Map.of(
      "missing", "Missing from a source", "different", "Values differ", "duplicate", "Duplicate key");

  /** Every finding matching the filters (paging is ignored) as CSV with a UTF-8 BOM. */
  public static void writeCsv(AnalysisResult result, Query query, OutputStream out) throws IOException {
    Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
    w.write('﻿');
    if (result.quality() != null) {
      AnalysisResult.Quality q = result.quality();
      Map<String, String> ruleLabels = new HashMap<>();
      q.rules().forEach(r -> ruleLabels.put(r.id(), r.label()));
      line(w, List.of("Source", "Entry", "DN", "Rule", "Severity", "Attribute", "Values", "Finding"));
      Predicate<AnalysisResult.Issue> filter = issueFilter(query);
      for (AnalysisResult.Issue i : q.issues()) {
        if (!filter.test(i)) continue;
        String source = i.source() < q.sources().size() ? q.sources().get(i.source()).label() : "";
        line(w, List.of(source, nz(i.title()), nz(i.dn()), ruleLabels.getOrDefault(i.ruleId(), i.ruleId()), nz(i.severity()),
            nz(i.attribute()), joined(i.values()), nz(i.message())));
      }
    } else {
      AnalysisResult.Compare c = result.compare();
      List<String> labels = c.sources().stream().map(AnalysisResult.SourceStats::label).toList();
      List<String> head = new ArrayList<>(List.of("Key", "Status"));
      labels.forEach(l -> head.add("In " + l));
      labels.forEach(l -> head.add("DN in " + l));
      head.add("Attribute");
      labels.forEach(l -> head.add("Value in " + l));
      line(w, head);
      Predicate<AnalysisResult.CompareRow> filter = rowFilter(query);
      for (AnalysisResult.CompareRow r : c.rows()) {
        if (!filter.test(r)) continue;
        List<Object> base = new ArrayList<>();
        base.add(r.key());
        base.add(STATUS_LABEL.getOrDefault(r.status(), r.status())
            + (Boolean.TRUE.equals(r.duplicate()) && !"duplicate".equals(r.status()) ? " (duplicate key)" : ""));
        for (int s = 0; s < labels.size(); s++) base.add(r.present() != null && s < r.present().size() && r.present().get(s) ? "yes" : "no");
        for (int s = 0; s < labels.size(); s++) base.add(r.dns() != null && s < r.dns().size() ? joined(r.dns().get(s)) : "");
        if (r.differences() == null || r.differences().isEmpty()) {
          List<Object> row = new ArrayList<>(base);
          row.add("");
          labels.forEach(l -> row.add(""));
          line(w, row);
        } else {
          for (AnalysisResult.Difference d : r.differences()) {
            List<Object> row = new ArrayList<>(base);
            row.add(d.label());
            for (int s = 0; s < labels.size(); s++) row.add(s < d.values().size() ? joined(d.values().get(s)) : "");
            line(w, row);
          }
        }
      }
    }
    w.flush();
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }
}
