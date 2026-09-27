package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptionItem(@NotBlank @Size(max = 1024) String value, @Size(max = 256) String label) {
  public String display() {
    return label == null || label.isBlank() ? value : label;
  }
}
