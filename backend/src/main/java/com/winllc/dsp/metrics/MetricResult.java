package com.winllc.dsp.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A computed metric. {@code total} is the number of entries scanned. {@code rows} holds the
 * buckets: values (distribution), attributes (completeness), periods (timeline) or histogram
 * bins (numeric); {@code share} is relative to {@code total}. With a split, {@code series} names
 * the stacked segments and {@code matrix[row][series]} holds their counts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricResult(
    String kind,
    long total,
    boolean truncated,
    String computedAt,
    List<Row> rows,
    List<Series> series,
    List<List<Long>> matrix,
    Integer distinctValues,
    Stats stats,
    List<String> warnings) {

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Row(String key, String label, double value, Double share, Boolean other, Boolean missing) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Series(String key, String label, Boolean other, Boolean missing) {}

  /** Numeric summary; null fields when there were no values. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Stats(long count, Double min, Double max, Double mean, Double median, Double p90, Double sum, long invalid, long missing) {}
}
