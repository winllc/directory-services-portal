package com.winllc.dsp.license;

import com.enssolutions.license.LicenseCheck;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the license to every API request (Spring Security has already authenticated it):
 * <ul>
 *   <li>UNLICENSED: 403 {@code license_required}; the web client then shows the license screen to
 *       administrators and a "not licensed" page to everyone else.</li>
 *   <li>READ_ONLY: GET, HEAD and OPTIONS work; anything else gets 403 {@code license_read_only}.</li>
 *   <li>{@link RequiresFeature}: 403 {@code feature_not_licensed} unless the license unlocks one of
 *       its features.</li>
 * </ul>
 * Errors are thrown as {@link LicenseRequiredException} and rendered as JSON by ApiErrorHandler.
 */
class LicenseInterceptor implements HandlerInterceptor {
  private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

  private final ObjectProvider<Licensing> licensing;

  LicenseInterceptor(ObjectProvider<Licensing> licensing) {
    this.licensing = licensing;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    LicenseCheck check = licensing.getObject().current();
    switch (check.mode()) {
      case UNLICENSED -> throw LicenseRequiredException.unlicensed(check);
      case READ_ONLY -> {
        if (!SAFE.contains(request.getMethod())) throw LicenseRequiredException.readOnly(check);
      }
      case FULL -> {}
    }
    if (handler instanceof HandlerMethod method) {
      RequiresFeature required = method.getMethodAnnotation(RequiresFeature.class);
      if (required == null) required = method.getBeanType().getAnnotation(RequiresFeature.class);
      if (required != null) {
        boolean any = false;
        for (LicensedFeature f : required.value()) any |= Licensing.allows(check, f);
        if (!any) throw LicenseRequiredException.feature(check, required.value()[0]);
      }
    }
    return true;
  }
}
