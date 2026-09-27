package com.winllc.dsp.web.dto;

import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.Patterns;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record FormInput(
    @NotBlank @Size(max = 128) String name,
    @Size(max = 1000) String description,
    @NotEmpty @Size(max = 50) List<@NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String> objectClasses,
    @NotBlank @Pattern(regexp = Patterns.ATTRIBUTE) String rdnAttribute,
    @NotEmpty @Size(max = 200) List<@Valid FormField> fields) {}
