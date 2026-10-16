package com.winllc.dsp.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import org.springframework.stereotype.Component;

/**
 * The portal's questions to the installed license. Wraps the library's {@link LicenseManager}
 * (re-reads the file when it changes, so a call per request is cheap).
 */
@Component
public class Licensing {
  /** The product id in the ENS license catalog. */
  public static final String PRODUCT = "directory-portal";

  private final LicenseManager licenses;

  public Licensing(LicenseManager licenses) {
    this.licenses = licenses;
  }

  public LicenseManager manager() {
    return licenses;
  }

  public LicenseCheck current() {
    return licenses.current();
  }

  /**
   * Whether a feature may be used now. In full effect (valid or in grace): the license lists it.
   * Read-only (expired past grace, or a perpetual license not covering this release): the license
   * lists it, and the read-only rule (GET only) is applied separately, so data stays visible.
   * Unlicensed: nothing.
   */
  public boolean allows(LicensedFeature feature) {
    return allows(current(), feature);
  }

  public static boolean allows(LicenseCheck check, LicensedFeature feature) {
    return switch (check.mode()) {
      case FULL -> check.isFeatureEnabled(feature.id());
      case READ_ONLY -> check.license().map(l -> l.features().contains(PRODUCT + "." + feature.id())).orElse(false);
      case UNLICENSED -> false;
    };
  }

  /** True when no license is in effect at all (only sign-in and the license screen work). */
  public boolean unlicensed() {
    return current().mode() == LicenseState.Mode.UNLICENSED;
  }
}
