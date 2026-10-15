package com.winllc.dsp.license;

/** A request for a licensed feature that the current license does not enable (HTTP 403). */
public class LicenseRequiredException extends RuntimeException {
  private final LicensedFeature feature;

  public LicenseRequiredException(LicensedFeature feature) {
    super(feature.label() + " requires an ENS Solutions license.");
    this.feature = feature;
  }

  public LicensedFeature feature() {
    return feature;
  }
}
