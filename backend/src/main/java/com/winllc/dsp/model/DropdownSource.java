package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.winllc.dsp.web.validation.LdapFilter;
import com.winllc.dsp.web.validation.ValidDn;
import jakarta.validation.Valid;
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

  record Ldap(
      @ValidDn String baseDn,
      @NotNull @Pattern(regexp = "one|sub") String scope,
      @LdapFilter String filter,
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE_OR_DN) String valueAttribute,
      @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String labelAttribute)
      implements DropdownSource {

    public boolean valueIsDn() {
      return "dn".equalsIgnoreCase(valueAttribute);
    }
  }
}
