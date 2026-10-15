package com.winllc.dsp.license;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** Refuses requests for a licensed feature that the license does not enable. */
class LicenseInterceptor implements HandlerInterceptor {
  private final LicenseService licenses;
  private final LicensedFeature feature;

  LicenseInterceptor(LicenseService licenses, LicensedFeature feature) {
    this.licenses = licenses;
    this.feature = feature;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    // Rendered as {"error", "code": "license_required", "feature"} by ApiErrorHandler.
    if (!licenses.isEnabled(feature.id())) throw new LicenseRequiredException(feature);
    return true;
  }
}
