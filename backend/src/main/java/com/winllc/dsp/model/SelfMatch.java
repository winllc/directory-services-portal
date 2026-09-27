package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** How to locate the signed-in user's entry inside a directory definition. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelfMatch.None.class, name = "none"),
  @JsonSubTypes.Type(value = SelfMatch.Dn.class, name = "dn"),
  @JsonSubTypes.Type(value = SelfMatch.Attribute.class, name = "attribute")
})
public sealed interface SelfMatch {

  /** Disabled. */
  record None() implements SelfMatch {}

  /** The entry DN equals the user's DN. */
  record Dn() implements SelfMatch {}

  /** entry[entryAttribute] equals user[userAttribute] ("dn" = the user's DN). */
  record Attribute(
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String entryAttribute,
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String userAttribute)
      implements SelfMatch {}
}
