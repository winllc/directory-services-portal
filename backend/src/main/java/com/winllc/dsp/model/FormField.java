package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One attribute in a form. {@code widget} is text, textarea or dropdown; {@code multiValued}
 * controls single vs multiple values.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormField(
    @NotBlank @Size(max = 64) String id,
    @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String attribute,
    @NotBlank @Size(max = 128) String label,
    @Size(max = 500) String helpText,
    @NotNull @Pattern(regexp = "text|textarea|dropdown") String widget,
    boolean multiValued,
    boolean required,
    boolean readOnly,
    boolean selfEditable,
    @Pattern(regexp = "plain|email|phone|url|number|dn") String format,
    @Size(max = 500) String pattern,
    @Positive @Max(65536) Integer maxLength,
    @Size(max = 200) String placeholder,
    @Valid DropdownSource dropdown,
    Boolean allowCustomValues,
    @Size(max = 100) String section,
    @Size(max = 100) List<@Size(max = 1024) String> defaultValues) {

  /** True when the widget is a drop down (not named isDropdown: Jackson would treat it as a getter). */
  public boolean dropdownWidget() {
    return "dropdown".equals(widget);
  }

  public boolean allowsCustomValues() {
    return Boolean.TRUE.equals(allowCustomValues);
  }
}
