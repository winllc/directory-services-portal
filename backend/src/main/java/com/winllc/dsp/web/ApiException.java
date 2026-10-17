package com.winllc.dsp.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error with an HTTP status, optional per-field details and an optional machine-readable code,
 * rendered as {"error", "details", "code"}.
 */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final Map<String, String> details;
  private final String code;

  public ApiException(HttpStatus status, String message) {
    this(status, message, (Map<String, String>) null);
  }

  public ApiException(HttpStatus status, String message, Map<String, String> details) {
    super(message);
    this.status = status;
    this.details = details;
    this.code = null;
  }

  public ApiException(HttpStatus status, String message, String code) {
    super(message);
    this.status = status;
    this.details = null;
    this.code = code;
  }

  /** E.g. "consent_required", "session_expired"; null when there is none. */
  public String code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  public Map<String, String> details() {
    return details;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, message);
  }

  public static ApiException badRequest(String message, Map<String, String> details) {
    return new ApiException(HttpStatus.BAD_REQUEST, message, details);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(HttpStatus.CONFLICT, message);
  }
}
