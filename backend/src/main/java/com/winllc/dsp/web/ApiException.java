package com.winllc.dsp.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error with an HTTP status and optional per-field details, rendered as {"error", "details"}. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final Map<String, String> details;

  public ApiException(HttpStatus status, String message) {
    this(status, message, null);
  }

  public ApiException(HttpStatus status, String message, Map<String, String> details) {
    super(message);
    this.status = status;
    this.details = details;
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
