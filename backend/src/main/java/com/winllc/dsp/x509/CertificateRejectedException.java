package com.winllc.dsp.x509;

/** A client certificate was missing, unusable or could not be mapped to a directory user. */
public class CertificateRejectedException extends RuntimeException {
  /** A forwarded certificate header did not come from the portal's own proxy. */
  public static final String UNTRUSTED_HEADER = "untrusted_certificate_header";
  public static final String REVOKED = "certificate_revoked";
  public static final String REVOCATION_UNKNOWN = "revocation_unknown";
  public static final String ISSUER_NOT_ALLOWED = "issuer_not_allowed";

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

  /** A code for the audit log ({@link #UNTRUSTED_HEADER}, {@link #REVOKED}, ...), or null. */
  public String reasonCode() {
    return reasonCode;
  }

  /** The reason recorded on the auth.login failure: the ENS baseline's wording for revocation, else the message. */
  public String auditReason() {
    if (REVOKED.equals(reasonCode)) return "certificate revoked";
    if (REVOCATION_UNKNOWN.equals(reasonCode)) return "revocation unknown";
    return getMessage();
  }

  static CertificateRejectedException revoked() {
    return new CertificateRejectedException("The client certificate has been revoked", REVOKED);
  }

  static CertificateRejectedException revocationUnknown() {
    return new CertificateRejectedException("The revocation status of the client certificate could not be determined", REVOCATION_UNKNOWN);
  }
}
