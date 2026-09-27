package com.winllc.dsp.web.dto;

import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.web.validation.LdapFilter;
import com.winllc.dsp.web.validation.ValidDn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies for the admin schema and directory tools. */
public final class SchemaRequests {
  private SchemaRequests() {}

  public record AttributeTypeInput(
      @NotBlank @Pattern(regexp = Patterns.OID_OR_NAME, message = "Invalid OID") String oid,
      @NotEmpty @Size(max = 10) List<@Pattern(regexp = Patterns.NAME, message = "Invalid name") String> names,
      @Size(max = 1000) String desc,
      @Pattern(regexp = Patterns.OID_OR_NAME) String sup,
      @Size(max = 128) String equality,
      @Size(max = 128) String ordering,
      @Size(max = 128) String substr,
      @Size(max = 128) String syntax,
      boolean singleValue,
      Boolean collective,
      boolean noUserModification,
      Boolean obsolete,
      @Pattern(regexp = "userApplications|directoryOperation|distributedOperation|dSAOperation") String usage,
      @Size(max = 128) String displayName) {

    public AttributeTypeDef toDef() {
      return new AttributeTypeDef(oid, names, blank(desc), blank(sup), blank(equality), blank(ordering), blank(substr), blank(syntax),
          singleValue, collective, noUserModification, obsolete, usage, "custom", blank(displayName));
    }
  }

  public record ObjectClassInput(
      @NotBlank @Pattern(regexp = Patterns.OID_OR_NAME, message = "Invalid OID") String oid,
      @NotEmpty @Size(max = 10) List<@Pattern(regexp = Patterns.NAME, message = "Invalid name") String> names,
      @Size(max = 1000) String desc,
      @NotNull @Size(max = 10) List<@Pattern(regexp = Patterns.OID_OR_NAME) String> sup,
      @NotNull @Pattern(regexp = "STRUCTURAL|AUXILIARY|ABSTRACT") String kind,
      @NotNull @Size(max = 200) List<@Pattern(regexp = Patterns.OID_OR_NAME) String> must,
      @NotNull @Size(max = 500) List<@Pattern(regexp = Patterns.OID_OR_NAME) String> may,
      Boolean obsolete,
      @Size(max = 128) String displayName) {

    public ObjectClassDef toDef() {
      return new ObjectClassDef(oid, names, blank(desc), sup, kind, must, may, obsolete, "custom", blank(displayName));
    }
  }

  public record SaveAttributeType(@NotNull @Valid AttributeTypeInput definition, String originalOid) {}

  public record SaveObjectClass(@NotNull @Valid ObjectClassInput definition, String originalOid) {}

  public record Import(@NotBlank @Size(max = 2_000_000) String text) {}

  public record Preview(
      @ValidDn String baseDn,
      @NotNull @Pattern(regexp = "base|one|sub") String scope,
      @LdapFilter String filter,
      @Size(max = 20) List<@Pattern(regexp = Patterns.ATTRIBUTE) String> attributes) {}

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
