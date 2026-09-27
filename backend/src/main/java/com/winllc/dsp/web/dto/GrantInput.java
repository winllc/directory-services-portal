package com.winllc.dsp.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GrantInput(
    @NotBlank String definitionId,
    @NotNull @Pattern(regexp = "user|group") String subjectType,
    @NotBlank @Size(max = 1024) String subject,
    @Size(max = 256) String subjectLabel,
    @NotNull @Pattern(regexp = "read|write") String access) {}
