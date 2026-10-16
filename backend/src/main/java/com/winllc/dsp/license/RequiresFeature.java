package com.winllc.dsp.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or handler method as part of a licensed feature. Requests to it get 403
 * ({@code feature_not_licensed}) unless the license unlocks at least one of the listed features. A
 * method annotation replaces the controller's.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
  LicensedFeature[] value();
}
