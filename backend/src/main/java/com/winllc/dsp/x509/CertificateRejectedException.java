package com.winllc.dsp.x509;

/** A client certificate was missing, unusable or could not be mapped to a directory user. */
public class CertificateRejectedException extends RuntimeException {
  public CertificateRejectedException(String message) {
    super(message);
  }

  public CertificateRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
