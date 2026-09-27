package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum AccessLevel {
  NONE,
  READ,
  WRITE;

  @JsonValue
  public String json() {
    return name().toLowerCase();
  }

  public boolean atLeast(AccessLevel other) {
    return ordinal() >= other.ordinal();
  }

  public static AccessLevel max(AccessLevel a, AccessLevel b) {
    return a.atLeast(b) ? a : b;
  }

  public static AccessLevel parse(String value) {
    return value == null ? NONE : valueOf(value.toUpperCase());
  }
}
