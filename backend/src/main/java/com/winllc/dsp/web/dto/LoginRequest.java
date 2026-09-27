package com.winllc.dsp.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 256) String username, @NotBlank @Size(max = 1024) String password) {}
