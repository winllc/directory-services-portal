package com.winllc.dsp.license;

import com.enssolutions.license.LicenseCheck;

/**
 * A request the license does not allow (HTTP 403), rendered by ApiErrorHandler as
 * {@code {"error": message, "code": ..., "state": ..., "feature": ...}}:
 * <ul>
 *   <li>{@code license_required}: no license in effect (missing, invalid, wrong product or
 *       installation, superseded, not yet valid); the web client routes to the license screen.</li>
 *   <li>{@code license_read_only}: the license expired past its grace period (or a perpetual
 *       license doesn't cover this release); only reading works.</li>
 *   <li>{@code feature_not_licensed}: the license doesn't include this feature.</li>
 * </ul>
 */
public class LicenseRequiredException extends RuntimeException {
  public static final String LICENSE_REQUIRED = "license_required";
  public static final String READ_ONLY = "license_read_only";
  public static final String FEATURE_NOT_LICENSED = "feature_not_licensed";

  private final String code;
  private final String state;
  private final LicensedFeature feature;

  private LicenseRequiredException(String code, String message, String state, LicensedFeature feature) {
    super(message);
    this.code = code;
    this.state = state;
    this.feature = feature;
  }

  public static LicenseRequiredException unlicensed(LicenseCheck check) {
    return new LicenseRequiredException(LICENSE_REQUIRED, check.message(), check.state().name(), null);
  }

  public static LicenseRequiredException readOnly(LicenseCheck check) {
    return new LicenseRequiredException(READ_ONLY, check.message(), check.state().name(), null);
  }

  public static LicenseRequiredException feature(LicenseCheck check, LicensedFeature feature) {
    return new LicenseRequiredException(FEATURE_NOT_LICENSED,
        feature.label() + " is not included in this portal's license.", check.state().name(), feature);
  }

  public String code() {
    return code;
  }

  public String state() {
    return state;
  }

  /** The feature refused ({@code feature_not_licensed} only), else null. */
  public LicensedFeature feature() {
    return feature;
  }
}
