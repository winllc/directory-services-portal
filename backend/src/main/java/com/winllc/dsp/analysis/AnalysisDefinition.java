package com.winllc.dsp.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.web.validation.LdapFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A saved data analysis over one or more directory definitions.
 *
 * <ul>
 *   <li>{@code compare}: matches entries across two or more sources by a key attribute and reports
 *       keys missing from some sources, duplicate keys and attribute values that differ.
 *   <li>{@code quality}: checks one or more sources for correct and unique values, using explicit
 *       rules and/or the rules of each directory's form.
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisDefinition(
    String id,
    String name,
    String description,
    String type,
    List<Source> sources,
    CompareOptions compare,
    QualityOptions quality,
    String createdAt,
    String updatedAt,
    LastRun lastRun) {

  public static final int MAX_SOURCES = 6;

  /** A directory definition, optionally narrowed by a filter. {@code keyAttribute} matches entries in comparisons. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      @NotBlank @Size(max = 128) String definitionId,
      @LdapFilter @Size(max = 2048) String filter,
      @Size(max = 64) String label,
      @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String keyAttribute) {}

  /**
   * Attributes compared between matched entries ({@code attributes} has one name per source, or a
   * single name used for all) and how values are normalized before comparing.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CompareOptions(
      @Size(max = 50) List<@Valid AttributeMapping> attributes,
      Boolean ignoreCase,
      Boolean normalizeWhitespace,
      Boolean ignoreOrder,
      Boolean keyIgnoreCase) {
    /** Every normalization is on unless switched off. */
    public CompareOptions {
      if (attributes == null) attributes = List.of();
      if (ignoreCase == null) ignoreCase = true;
      if (normalizeWhitespace == null) normalizeWhitespace = true;
      if (ignoreOrder == null) ignoreOrder = true;
      if (keyIgnoreCase == null) keyIgnoreCase = true;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AttributeMapping(
      @Size(max = 128) String label,
      @NotNull @Size(min = 1, max = MAX_SOURCES) List<@NotBlank @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String> attributes) {}

  /** Checks to run: explicit {@code rules}, plus every form field's rules when {@code formRules} is set. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QualityOptions(Boolean formRules, @Size(max = 100) List<@Valid Rule> rules) {
    public QualityOptions {
      if (formRules == null) formRules = false;
      if (rules == null) rules = List.of();
    }
  }

  /**
   * One check of an attribute's values.
   *
   * <ul>
   *   <li>{@code required}: at least one value; {@code singleValue}: at most one.
   *   <li>{@code unique}: no other entry has the same value, within each source ({@code scope=source})
   *       or across all sources ({@code scope=all}).
   *   <li>{@code pattern}: every value matches the regular expression (whole value).
   *   <li>{@code format}: email, phone, url, number, integer, date or dn.
   *   <li>{@code allowedValues}: every value is one of {@code values}.
   *   <li>{@code length}: every value has between {@code min} and {@code max} characters.
   *   <li>{@code dnExists}: every value is the DN of an existing entry (referential integrity).
   * </ul>
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Rule(
      @Size(max = 64) String id,
      @NotNull @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String attribute,
      @NotNull @Pattern(regexp = RULE_TYPES) String type,
      @Size(max = 500) String pattern,
      @Pattern(regexp = "email|phone|url|number|integer|date|dn") String format,
      @Size(max = 1000) List<@Size(max = 1024) String> values,
      @Min(0) @Max(65536) Integer min,
      @Min(0) @Max(65536) Integer max,
      @Pattern(regexp = "source|all") String scope,
      Boolean ignoreCase,
      @Pattern(regexp = "error|warning") String severity,
      @Size(max = 200) String description) {

    public static final String RULE_TYPES = "required|singleValue|unique|pattern|format|allowedValues|length|dnExists";

    public Rule withId(String newId) {
      return new Rule(newId, attribute, type, pattern, format, values, min, max, scope, ignoreCase, severity, description);
    }

    public String severityOrDefault() {
      return severity == null ? "error" : severity;
    }

    public boolean caseInsensitive() {
      return !Boolean.FALSE.equals(ignoreCase);
    }
  }

  /** When a saved analysis last ran and what it found. */
  public record LastRun(String at, String by, long entries, long issues, String summary) {}

  public AnalysisDefinition withLastRun(LastRun run) {
    return new AnalysisDefinition(id, name, description, type, sources, compare, quality, createdAt, updatedAt, run);
  }
}
