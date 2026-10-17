package com.winllc.dsp.x509;

/** A client certificate was missing, unusable or could not be mapped to a directory user. */
public class CertificateRejectedException extends RuntimeException {
  /** A forwarded certificate header did not come from the portal's own proxy. */
  public static final String UNTRUSTED_HEADER = "untrusted_certificate_header";

  private final String reasonCode;

  public CertificateRejectedException(String message) {
    this(message, (String) null);
  }

  public CertificateRejectedException(String message, Throwable cause) {
    super(message, cause);
    this.reasonCode = null;
  }

  public CertificateRejectedException(String message, String reasonCode) {
    super(message);
    this.reasonCode = reasonCode;
  }

  /** A code for the audit log ({@link #UNTRUSTED_HEADER}), or null. */
  public String reasonCode() {
    return reasonCode;
  }
}
