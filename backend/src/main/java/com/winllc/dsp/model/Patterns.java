package com.winllc.dsp.model;

/** Regular expressions shared by request validation. */
public final class Patterns {
  public static final String ATTRIBUTE = "^([A-Za-z][A-Za-z0-9-]*|\\d+(\\.\\d+)+)(;[A-Za-z0-9-]+)*$";
  public static final String ATTRIBUTE_OR_DN = "^(dn|([A-Za-z][A-Za-z0-9-]*|\\d+(\\.\\d+)+)(;[A-Za-z0-9-]+)*)$";
  public static final String OID_OR_NAME = "^([0-9]+(\\.[0-9]+)+|[A-Za-z][A-Za-z0-9-]*)$";
  public static final String NAME = "^[A-Za-z][A-Za-z0-9-]*$";
  public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

  private Patterns() {}
}
