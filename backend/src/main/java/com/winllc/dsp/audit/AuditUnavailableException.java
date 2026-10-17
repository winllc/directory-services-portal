package com.winllc.dsp.audit;

/**
 * A security action could not be audited, so it is refused (HTTP 503): the portal never makes a
 * sign-in, permission, configuration or license change it cannot record.
 */
public class AuditUnavailableException extends RuntimeException {
  public AuditUnavailableException(String message) {
    super(message);
  }
}
