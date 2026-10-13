package com.winllc.dsp.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The outcome of running an analysis. Exactly one of {@code compare} / {@code quality} is set. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResult(String type, String ranAt, long durationMs, Compare compare, Quality quality, List<String> warnings) {

  /**
   * The result without its findings (issues or rows): what the API sends, since findings can be
   * many and are fetched a page at a time instead (see {@link AnalysisFindings}).
   */
  public AnalysisResult withoutFindings() {
    Compare c = compare == null ? null
        : new Compare(compare.sources(), compare.summary(), compare.attributes(), null, compare.rowCount(), compare.rowsTruncated());
    Quality q = quality == null ? null
        : new Quality(quality.sources(), quality.summary(), quality.rules(), null, quality.issuesTruncated());
    return new AnalysisResult(type, ranAt, durationMs, c, q, warnings);
  }

  /** What was read from one source. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record SourceStats(String label, String definitionId, String definitionName, long entries, boolean truncated,
      Long keyed, Long missingKey, Long duplicateKeys) {}

  // ---------------------------------------------------------------------------
  // Comparison
  // ---------------------------------------------------------------------------

  /**
   * {@code keys}: distinct keys over all sources; {@code inAll}: keys present in every source,
   * split into {@code identical} and {@code different}; {@code partial}: keys missing from at
   * least one source; {@code onlyIn[i]}: keys found only in source i.
   */
  public record CompareSummary(long keys, long inAll, long identical, long different, long partial, List<Long> onlyIn, long duplicates) {}

  public record AttributeStats(String label, List<String> attributes, long differences) {}

  /** Values of one compared attribute, per source (null where the key is absent). */
  public record Difference(String label, List<List<String>> values) {}

  /**
   * A key with a finding. {@code status}: {@code missing} (absent from some sources),
   * {@code different} (values differ) or {@code duplicate} (values agree, but a source has the key
   * on more than one entry; {@code duplicate} is also set alongside the other statuses).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CompareRow(String key, String status, Boolean duplicate, List<Boolean> present, List<List<String>> dns, List<Difference> differences) {}

  /** {@code rows}: up to the findings limit; {@code rowCount} counts all. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Compare(List<SourceStats> sources, CompareSummary summary, List<AttributeStats> attributes, List<CompareRow> rows,
      long rowCount, boolean rowsTruncated) {}

  // ---------------------------------------------------------------------------
  // Quality
  // ---------------------------------------------------------------------------

  /** A rule and how many entries broke it. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RuleStats(String id, String label, String attribute, String type, String severity, long violations, Integer source) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Issue(String ruleId, int source, String dn, String title, String attribute, List<String> values, String message, String severity) {}

  /** {@code cleanShare}: share of entries without any finding (0..1). */
  public record QualitySummary(long entries, long entriesWithIssues, long issues, long errors, long warnings, double cleanShare) {}

  /** {@code issues}: up to the findings limit; the summary counts all. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Quality(List<SourceStats> sources, QualitySummary summary, List<RuleStats> rules, List<Issue> issues, boolean issuesTruncated) {}
}
