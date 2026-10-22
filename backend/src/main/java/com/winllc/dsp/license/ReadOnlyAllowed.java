package com.winllc.dsp.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a non-GET handler method that only reads data (a download sent as POST so that it carries
 * the CSRF header): it keeps working while the license is read-only, like a GET.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadOnlyAllowed {}
