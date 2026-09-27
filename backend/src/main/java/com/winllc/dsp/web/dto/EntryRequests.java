package com.winllc.dsp.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Bodies for entry create/update. Values are strings, lists of strings or null, keyed by attribute. */
public final class EntryRequests {
  private EntryRequests() {}

  public record Create(@Size(max = 1024) String parentDn, @NotNull Map<String, Object> values) {}

  public record Update(@NotNull Map<String, Object> values) {}
}
