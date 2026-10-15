package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * The license status shown to signed-in users. {@code state} is LICENSED, MISSING, INVALID or
 * EXPIRED; {@code file} (the license path on the server) is only given to administrators.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseInfo(
    String state,
    String product,
    String licensee,
    String licenseId,
    LocalDate expires,
    String problem,
    String file,
    List<Feature> features) {

  /** A licensed feature and whether this deployment may use it. */
  public record Feature(String id, String label, boolean enabled) {}
}
