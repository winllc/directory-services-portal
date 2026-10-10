package com.winllc.dsp.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.web.validation.LdapFilter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One chart or statistic on a dashboard: what to measure (the source directory, an optional extra
 * filter and a metric kind with its options) and how to show it.
 *
 * <ul>
 *   <li>{@code count}: number of entries.
 *   <li>{@code distribution}: entries per value of {@code attribute} (after {@code transform}),
 *       top {@code limit} with the rest folded into "Other"; optionally split by a second
 *       attribute ({@code splitBy}) for stacked bars.
 *   <li>{@code completeness}: share of entries that have a value for each of {@code attributes}.
 *   <li>{@code timeline}: entries per {@code bucket} of a date attribute, optionally cumulative.
 *   <li>{@code numeric}: statistics and a histogram of numeric values ({@code measure=values}) or
 *       of the number of values per entry ({@code measure=valueCount}).
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricWidget(
    @Size(max = 64) String id,
    @NotBlank @Size(max = 128) String title,
    @Size(max = 500) String description,
    @NotBlank @Size(max = 128) String definitionId,
    @LdapFilter @Size(max = 2048) String filter,
    @NotNull @Pattern(regexp = KINDS) String kind,
    @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String attribute,
    @Pattern(regexp = TRANSFORMS) String transform,
    @Size(max = 500) String transformArg,
    @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String splitBy,
    @Pattern(regexp = TRANSFORMS) String splitTransform,
    @Size(max = 50) List<@Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String> attributes,
    @Min(1) @Max(100) Integer limit,
    Boolean includeMissing,
    @Pattern(regexp = "count|label") String sort,
    @Pattern(regexp = "day|week|month|quarter|year") String bucket,
    Boolean cumulative,
    @Pattern(regexp = "values|valueCount") String measure,
    @Min(2) @Max(50) Integer bins,
    @NotNull @Pattern(regexp = CHARTS) String chart,
    @Pattern(regexp = "third|half|two-thirds|full") String width) {

  public static final String KINDS = "count|distribution|completeness|timeline|numeric";
  public static final String TRANSFORMS = "none|lowercase|uppercase|emailDomain|dnRdn|dnParent|prefix|regex|year|quarter|month|week|day";
  public static final String CHARTS = "stat|bar|column|stacked|donut|line|area|table";

  public int limitOr(int fallback) {
    return limit == null ? fallback : limit;
  }

  public String transformOrNone() {
    return transform == null ? "none" : transform;
  }

  public MetricWidget withId(String newId) {
    return new MetricWidget(newId, title, description, definitionId, filter, kind, attribute, transform, transformArg, splitBy,
        splitTransform, attributes, limit, includeMissing, sort, bucket, cumulative, measure, bins, chart, width);
  }

  /** The parts that determine the result (not how it is titled or drawn), used as a cache key. */
  public MetricWidget computeKey() {
    return new MetricWidget(null, "-", null, definitionId, filter == null || filter.isBlank() ? null : filter.trim(), kind,
        attribute, transform, transformArg, splitBy, splitTransform, attributes, limit, includeMissing, sort, bucket, cumulative,
        measure, bins, "table", null);
  }
}
