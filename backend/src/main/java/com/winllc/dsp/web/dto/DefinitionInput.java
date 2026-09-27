package com.winllc.dsp.web.dto;

import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.model.SelfMatch;
import com.winllc.dsp.web.validation.LdapFilter;
import com.winllc.dsp.web.validation.ValidDn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DefinitionInput(
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 64) @Pattern(regexp = Patterns.SLUG, message = "Use lower-case letters, digits and dashes") String slug,
    @Size(max = 1000) String description,
    @NotBlank String formId,
    @ValidDn String baseDn,
    @NotNull @Pattern(regexp = "one|sub") String scope,
    @LdapFilter String filter,
    @NotNull @Pattern(regexp = "readwrite|readonly") String mode,
    boolean everyoneCanRead,
    @NotEmpty @Size(max = 20) List<@Pattern(regexp = Patterns.ATTRIBUTE) String> listAttributes,
    @NotNull @Size(max = 20) List<@Pattern(regexp = Patterns.ATTRIBUTE) String> searchAttributes,
    @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String titleAttribute,
    @NotNull @Size(max = 50) List<@ValidDn String> createContainers,
    @NotNull @Valid SelfMatch selfMatch,
    @Size(max = 32) String icon) {}
