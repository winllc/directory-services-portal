package com.winllc.dsp.metrics;

import com.winllc.dsp.metrics.MetricResult.Row;
import com.winllc.dsp.metrics.MetricResult.Series;
import com.winllc.dsp.metrics.MetricResult.Stats;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.service.EntryValues;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Folds a stream of entries into one metric. Not thread-safe; one instance per evaluation. */
abstract class Aggregator {
  static final String MISSING_KEY = "\u0000missing";
  static final String OTHER_KEY = "\u0000other";
  static final String MISSING_LABEL = "(none)";
  static final String OTHER_LABEL = "Other";
  /** Most series a stacked chart shows before folding the rest into "Other" (one categorical palette). */
  static final int MAX_SERIES = 8;
  static final int MAX_TIMELINE_BUCKETS = 1000;

  protected final MetricWidget widget;
  protected final EntryValues values;
  protected final List<String> warnings = new ArrayList<>();
  protected long total;

  Aggregator(MetricWidget widget, EntryValues values) {
    this.widget = widget;
    this.values = values;
  }

  /** Attributes the scan must return for this metric. */
  abstract Set<String> attributes();

  void accept(DirectoryEntry entry) {
    total++;
    add(entry);
  }

  abstract void add(DirectoryEntry entry);

  abstract MetricResult finish(boolean truncated);

  protected MetricResult result(boolean truncated, List<Row> rows, List<Series> series, List<List<Long>> matrix, Integer distinct, Stats stats) {
    return new MetricResult(widget.kind(), total, truncated, Instant.now().toString(), rows, series, matrix, distinct, stats,
        warnings.isEmpty() ? null : List.copyOf(warnings));
  }

  protected Double share(double value) {
    return total == 0 ? 0.0 : value / total;
  }

  // ---------------------------------------------------------------------------

  static final class Count extends Aggregator {
    Count(MetricWidget widget, EntryValues values) {
      super(widget, values);
    }

    @Override
    Set<String> attributes() {
      return Set.of();
    }

    @Override
    void add(DirectoryEntry entry) {}

    @Override
    MetricResult finish(boolean truncated) {
      return result(truncated, null, null, null, null, null);
    }
  }

  // ---------------------------------------------------------------------------

  /** Entries per value of an attribute, optionally split by a second attribute. */
  static final class Distribution extends Aggregator {
    private final ValueTransform transform;
    private final ValueTransform splitTransform;
    private final Map<String, String> optionLabels;
    private final Map<String, String> splitOptionLabels;
    private final boolean includeMissing;
    private final Map<String, long[]> counts = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, Map<String, long[]>> cells = new HashMap<>();
    private final Map<String, long[]> seriesTotals = new HashMap<>();
    private final Map<String, String> seriesLabels = new HashMap<>();
    private long missing;
    private long splitMissing;

    Distribution(MetricWidget widget, EntryValues values, ValueTransform transform, ValueTransform splitTransform,
        Map<String, String> optionLabels, Map<String, String> splitOptionLabels) {
      super(widget, values);
      this.transform = transform;
      this.splitTransform = splitTransform;
      this.optionLabels = optionLabels;
      this.splitOptionLabels = splitOptionLabels;
      this.includeMissing = !Boolean.FALSE.equals(widget.includeMissing());
    }

    @Override
    Set<String> attributes() {
      Set<String> a = new LinkedHashSet<>();
      a.add(widget.attribute());
      if (widget.splitBy() != null) a.add(widget.splitBy());
      return a;
    }

    private Set<String> buckets(DirectoryEntry entry, String attribute, ValueTransform t, Map<String, String> options, Map<String, String> labelsOut) {
      Set<String> keys = new LinkedHashSet<>();
      for (String raw : values.get(entry, attribute)) {
        ValueTransform.Bucket b = t.apply(raw);
        if (b == null) continue;
        keys.add(b.key());
        labelsOut.putIfAbsent(b.key(), options.getOrDefault(b.key(), b.label()));
      }
      return keys;
    }

    @Override
    void add(DirectoryEntry entry) {
      Set<String> keys = buckets(entry, widget.attribute(), transform, optionLabels, labels);
      if (keys.isEmpty()) {
        missing++;
        if (!includeMissing) return;
        keys = Set.of(MISSING_KEY);
      }
      for (String k : keys) counts.computeIfAbsent(k, x -> new long[1])[0]++;
      if (splitTransform == null) return;
      Set<String> split = buckets(entry, widget.splitBy(), splitTransform, splitOptionLabels, seriesLabels);
      if (split.isEmpty()) {
        splitMissing++;
        split = Set.of(MISSING_KEY);
      }
      for (String s : split) seriesTotals.computeIfAbsent(s, x -> new long[1])[0]++;
      for (String k : keys) {
        Map<String, long[]> row = cells.computeIfAbsent(k, x -> new HashMap<>());
        for (String s : split) row.computeIfAbsent(s, x -> new long[1])[0]++;
      }
    }

    private String label(String key, Map<String, String> source) {
      return MISSING_KEY.equals(key) ? MISSING_LABEL : source.getOrDefault(key, key);
    }

    @Override
    MetricResult finish(boolean truncated) {
      Comparator<String> byLabel = Comparator.comparing((String k) -> label(k, labels), String.CASE_INSENSITIVE_ORDER);
      Comparator<String> byCount = Comparator.comparingLong((String k) -> -counts.get(k)[0]).thenComparing(byLabel);
      List<String> ordered = counts.keySet().stream()
          .filter(k -> !MISSING_KEY.equals(k))
          .sorted("label".equals(widget.sort()) ? byLabel : byCount)
          .toList();
      int limit = widget.limitOr(10);
      // Folding a single leftover value into "Other" hides its name for nothing.
      if (ordered.size() == limit + 1) limit++;
      List<String> shown = new ArrayList<>(ordered.subList(0, Math.min(limit, ordered.size())));
      List<String> folded = ordered.subList(shown.size(), ordered.size());
      if (!folded.isEmpty()) shown.add(OTHER_KEY);
      if (counts.containsKey(MISSING_KEY)) shown.add(MISSING_KEY);

      List<Row> rows = new ArrayList<>();
      for (String k : shown) {
        long v = OTHER_KEY.equals(k) ? folded.stream().mapToLong(f -> counts.get(f)[0]).sum() : counts.get(k)[0];
        rows.add(new Row(k.startsWith("\u0000") ? k.substring(1) : k,
            OTHER_KEY.equals(k) ? OTHER_LABEL + " (" + folded.size() + ")" : label(k, labels), v, share(v),
            OTHER_KEY.equals(k) ? true : null, MISSING_KEY.equals(k) ? true : null));
      }
      if (missing > 0 && !includeMissing) warnings.add(missing + " entries have no value and are not shown");

      List<Series> series = null;
      List<List<Long>> matrix = null;
      if (splitTransform != null) {
        List<String> sOrdered = seriesTotals.keySet().stream()
            .filter(k -> !MISSING_KEY.equals(k))
            .sorted(Comparator.comparingLong((String k) -> -seriesTotals.get(k)[0])
                .thenComparing(k -> label(k, seriesLabels), String.CASE_INSENSITIVE_ORDER))
            .toList();
        int room = MAX_SERIES - (seriesTotals.containsKey(MISSING_KEY) ? 1 : 0);
        List<String> sShown = new ArrayList<>(sOrdered.size() <= room ? sOrdered : sOrdered.subList(0, room - 1));
        Set<String> sFolded = new LinkedHashSet<>(sOrdered.subList(sShown.size(), sOrdered.size()));
        if (!sFolded.isEmpty()) sShown.add(OTHER_KEY);
        if (seriesTotals.containsKey(MISSING_KEY)) sShown.add(MISSING_KEY);
        series = new ArrayList<>();
        for (String s : sShown) {
          series.add(new Series(s.startsWith("\u0000") ? s.substring(1) : s,
              OTHER_KEY.equals(s) ? OTHER_LABEL + " (" + sFolded.size() + ")" : label(s, seriesLabels),
              OTHER_KEY.equals(s) ? true : null, MISSING_KEY.equals(s) ? true : null));
        }
        matrix = new ArrayList<>();
        for (String k : shown) {
          List<String> rowKeys = OTHER_KEY.equals(k) ? folded : List.of(k);
          List<Long> line = new ArrayList<>();
          for (String s : sShown) {
            long sum = 0;
            for (String rk : rowKeys) {
              Map<String, long[]> row = cells.getOrDefault(rk, Map.of());
              if (OTHER_KEY.equals(s)) {
                for (String f : sFolded) sum += row.getOrDefault(f, new long[1])[0];
              } else {
                sum += row.getOrDefault(s, new long[1])[0];
              }
            }
            line.add(sum);
          }
          matrix.add(line);
        }
      }
      return result(truncated, rows, series, matrix, ordered.size(), null);
    }
  }

  // ---------------------------------------------------------------------------

  /** Share of entries with at least one value, per attribute. */
  static final class Completeness extends Aggregator {
    private final List<String> attrs;
    private final Map<String, String> fieldLabels;
    private final long[] present;

    Completeness(MetricWidget widget, EntryValues values, List<String> attrs, Map<String, String> fieldLabels) {
      super(widget, values);
      this.attrs = attrs;
      this.fieldLabels = fieldLabels;
      this.present = new long[attrs.size()];
    }

    @Override
    Set<String> attributes() {
      return new LinkedHashSet<>(attrs);
    }

    @Override
    void add(DirectoryEntry entry) {
      for (int i = 0; i < attrs.size(); i++) {
        if (!values.get(entry, attrs.get(i)).isEmpty()) present[i]++;
      }
    }

    @Override
    MetricResult finish(boolean truncated) {
      List<Row> rows = new ArrayList<>();
      for (int i = 0; i < attrs.size(); i++) {
        String a = attrs.get(i);
        rows.add(new Row(a, fieldLabels.getOrDefault(a.toLowerCase(), a), present[i], share(present[i]), null, null));
      }
      if ("count".equals(widget.sort())) rows.sort(Comparator.comparingDouble(Row::value));
      return result(truncated, rows, null, null, null, null);
    }
  }

  // ---------------------------------------------------------------------------

  /** Entries per period of a date attribute. */
  static final class Timeline extends Aggregator {
    private final ValueTransform.DateBucket unit;
    private final TreeMap<LocalDate, long[]> counts = new TreeMap<>();
    private long invalid;
    private long missing;

    Timeline(MetricWidget widget, EntryValues values) {
      super(widget, values);
      this.unit = ValueTransform.DateBucket.of(widget.bucket());
    }

    @Override
    Set<String> attributes() {
      return Set.of(widget.attribute());
    }

    @Override
    void add(DirectoryEntry entry) {
      List<String> vs = values.get(entry, widget.attribute());
      if (vs.isEmpty()) {
        missing++;
        return;
      }
      boolean any = false;
      for (String v : vs) {
        LocalDate d = ValueTransform.parseDate(v);
        if (d == null) continue;
        counts.computeIfAbsent(unit.start(d), x -> new long[1])[0]++;
        any = true;
      }
      if (!any) invalid++;
    }

    @Override
    MetricResult finish(boolean truncated) {
      List<Row> rows = new ArrayList<>();
      if (!counts.isEmpty()) {
        List<LocalDate> periods = new ArrayList<>();
        for (LocalDate d = counts.firstKey(); !d.isAfter(counts.lastKey()); d = unit.next(d)) periods.add(d);
        int keep = Math.min(widget.limitOr(MAX_TIMELINE_BUCKETS), MAX_TIMELINE_BUCKETS);
        long before = 0;
        if (periods.size() > keep) {
          if (widget.limit() == null) warnings.add("Only the last " + keep + " periods are shown");
          for (LocalDate d : periods.subList(0, periods.size() - keep)) before += counts.getOrDefault(d, new long[1])[0];
          periods = periods.subList(periods.size() - keep, periods.size());
        }
        boolean cumulative = Boolean.TRUE.equals(widget.cumulative());
        long running = before;
        for (LocalDate d : periods) {
          long v = counts.getOrDefault(d, new long[1])[0];
          running += v;
          double shown = cumulative ? running : v;
          rows.add(new Row(unit.key(d), unit.label(d), shown, share(shown), null, null));
        }
      }
      if (missing > 0) warnings.add(missing + " entries have no " + widget.attribute());
      if (invalid > 0) warnings.add(invalid + " entries have a " + widget.attribute() + " that isn't a date");
      return result(truncated, rows, null, null, null, new Stats(total - missing - invalid, null, null, null, null, null, null, invalid, missing));
    }
  }

  // ---------------------------------------------------------------------------

  /** Summary statistics and a histogram of numeric values, or of the number of values per entry. */
  static final class Numeric extends Aggregator {
    private final boolean valueCount;
    private double[] data = new double[256];
    private int n;
    private long invalid;
    private long missing;

    Numeric(MetricWidget widget, EntryValues values) {
      super(widget, values);
      this.valueCount = "valueCount".equals(widget.measure());
    }

    @Override
    Set<String> attributes() {
      return Set.of(widget.attribute());
    }

    private void push(double v) {
      if (n == data.length) data = Arrays.copyOf(data, n * 2);
      data[n++] = v;
    }

    @Override
    void add(DirectoryEntry entry) {
      List<String> vs = values.get(entry, widget.attribute());
      if (valueCount) {
        push(vs.size());
        return;
      }
      if (vs.isEmpty()) missing++;
      for (String v : vs) {
        try {
          double d = Double.parseDouble(v.trim());
          if (Double.isFinite(d)) push(d);
          else invalid++;
        } catch (NumberFormatException e) {
          invalid++;
        }
      }
    }

    @Override
    MetricResult finish(boolean truncated) {
      double[] sorted = Arrays.copyOf(data, n);
      Arrays.sort(sorted);
      if (invalid > 0) warnings.add(invalid + " values of " + widget.attribute() + " are not numbers");
      if (n == 0) return result(truncated, List.of(), null, null, null, new Stats(0, null, null, null, null, null, null, invalid, missing));
      double sum = 0;
      for (double d : sorted) sum += d;
      double min = sorted[0];
      double max = sorted[n - 1];
      Stats stats = new Stats(n, min, max, sum / n, quantile(sorted, 0.5), quantile(sorted, 0.9), sum, invalid, missing);

      List<Row> rows = new ArrayList<>();
      boolean integers = Arrays.stream(sorted).allMatch(d -> d == Math.rint(d));
      int bins = widget.bins() != null ? widget.bins() : 10;
      long span = (long) (max - min) + 1;
      if (integers && span <= Math.max(bins, widget.bins() == null ? 25 : bins)) {
        long[] c = new long[(int) span];
        for (double d : sorted) c[(int) (d - min)]++;
        for (int i = 0; i < span; i++) {
          String k = fmt(min + i);
          rows.add(new Row(k, k, c[i], (double) c[i] / n, null, null));
        }
      } else {
        double width = (max - min) / bins;
        if (width == 0) width = 1;
        long[] c = new long[bins];
        for (double d : sorted) c[Math.min(bins - 1, (int) ((d - min) / width))]++;
        for (int i = 0; i < bins; i++) {
          double lo = min + i * width;
          double hi = i == bins - 1 ? max : lo + width;
          String k = fmt(lo) + "–" + fmt(hi);
          rows.add(new Row(k, k, c[i], (double) c[i] / n, null, null));
        }
      }
      return result(truncated, rows, null, null, null, stats);
    }

    private static double quantile(double[] sorted, double q) {
      double pos = (sorted.length - 1) * q;
      int lo = (int) Math.floor(pos);
      int hi = (int) Math.ceil(pos);
      return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    private static String fmt(double d) {
      if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long) d);
      return String.format(java.util.Locale.ROOT, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
  }

  static Map<String, String> lowerKeys(Map<String, String> in) {
    Map<String, String> out = new LinkedHashMap<>();
    in.forEach((k, v) -> out.put(k.toLowerCase(), v));
    return out;
  }
}
