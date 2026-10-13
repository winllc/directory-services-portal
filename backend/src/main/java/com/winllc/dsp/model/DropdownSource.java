package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.winllc.dsp.web.validation.LdapFilter;
import com.winllc.dsp.web.validation.ValidDn;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Where a drop down gets its options: a fixed list or an LDAP search. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = DropdownSource.Static.class, name = "static"),
  @JsonSubTypes.Type(value = DropdownSource.Ldap.class, name = "ldap")
})
public sealed interface DropdownSource {

  record Static(@NotNull @Size(max = 1000) List<@Valid OptionItem> options) implements DropdownSource {}

  /**
   * Options found by searching the directory. Besides where to search and which attributes to
   * store and show, the search can be tuned:
   *
   * <ul>
   *   <li>{@code mode}: {@code list} loads the options up front (small sets); {@code search}
   *       queries the directory as the user types (large sets).
   *   <li>{@code searchAttributes} / {@code match}: what typed text is matched against, and how
   *       ({@code contains}, {@code startsWith} or {@code exact}). Each typed word must match one
   *       of the attributes.
   *   <li>{@code minChars}: characters needed before searching (search mode).
   *   <li>{@code limit}: most options returned; more matches are reported as truncated.
   *   <li>{@code sortAttribute}, {@code descriptionAttribute} (a second line under each option)
   *       and {@code labelTemplate} (e.g. {@code "{cn} ({mail})"}, overriding labelAttribute).
   *   <li>{@code cacheSeconds}: how long results are reused (0: always ask the directory).
   * </ul>
   *
   * All tuning fields are optional; absent ones take the defaults below.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Ldap(
      @ValidDn String baseDn,
      @NotNull @Pattern(regexp = "one|sub") String scope,
      @LdapFilter String filter,
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String valueAttribute,
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String labelAttribute,
      @Pattern(regexp = "list|search") String mode,
      @Size(max = 10) List<@Pattern(regexp = Patterns.ATTRIBUTE) String> searchAttributes,
      @Pattern(regexp = "contains|startsWith|exact") String match,
      @Min(0) @Max(10) Integer minChars,
      @Min(1) @Max(MAX_LIMIT) Integer limit,
      @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String sortAttribute,
      @Pattern(regexp = Patterns.ATTRIBUTE) String descriptionAttribute,
      @Size(max = 200) String labelTemplate,
      @Min(0) @Max(3600) Integer cacheSeconds)
      implements DropdownSource {

    public static final int MAX_LIMIT = 2000;
    public static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([^{}]*)}");

    /** The settings the first version had (everything else defaulted). */
    public Ldap(String baseDn, String scope, String filter, String valueAttribute, String labelAttribute) {
      this(baseDn, scope, filter, valueAttribute, labelAttribute, null, null, null, null, null, null, null, null, null);
    }

    public boolean valueIsDn() {
      return "dn".equalsIgnoreCase(valueAttribute);
    }

    public boolean searchMode() {
      return "search".equals(mode);
    }

    public String matchOrDefault() {
      return match == null ? "contains" : match;
    }

    public int minCharsOrDefault() {
      return minChars != null ? minChars : searchMode() ? 2 : 0;
    }

    public int limitOrDefault() {
      return limit != null ? limit : searchMode() ? 25 : 500;
    }

    public int cacheSecondsOrDefault() {
      return cacheSeconds == null ? 30 : cacheSeconds;
    }

    /** Attributes typed text is matched against: as configured, else the label (and stored value). */
    public List<String> effectiveSearchAttributes() {
      if (searchAttributes != null && !searchAttributes.isEmpty()) return searchAttributes;
      java.util.List<String> out = new java.util.ArrayList<>(List.of(labelAttribute));
      if (!valueIsDn() && !valueAttribute.equalsIgnoreCase(labelAttribute)) out.add(valueAttribute);
      return out;
    }

    /** Attribute names used by {@code labelTemplate}. */
    public List<String> templateAttributes() {
      if (labelTemplate == null || labelTemplate.isBlank()) return List.of();
      java.util.List<String> out = new java.util.ArrayList<>();
      java.util.regex.Matcher m = PLACEHOLDER.matcher(labelTemplate);
      while (m.find()) out.add(m.group(1).trim());
      return out;
    }
  }
}
