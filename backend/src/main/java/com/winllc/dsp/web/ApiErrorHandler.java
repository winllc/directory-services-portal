package com.winllc.dsp.web;

import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.x509.CertificateRejectedException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every API error as {"error": message, "details": {field: message}}. */
@RestControllerAdvice
public class ApiErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Map<String, ?> details) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("error", message);
    if (details != null && !details.isEmpty()) b.put("details", details);
    return ResponseEntity.status(status).body(b);
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Map<String, Object>> api(ApiException e) {
    return body(e.status(), e.getMessage(), e.details());
  }

  @ExceptionHandler(CertificateRejectedException.class)
  ResponseEntity<Map<String, Object>> certificate(CertificateRejectedException e) {
    return body(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
  }

  @ExceptionHandler(DirectoryException.class)
  ResponseEntity<Map<String, Object>> directory(DirectoryException e) {
    ResponseEntity<Map<String, Object>> r = body(e.status(), e.getMessage(), null);
    r.getBody().put("code", e.code().name());
    return r;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
    Map<String, String> details = new LinkedHashMap<>();
    e.getBindingResult().getFieldErrors().forEach(f -> details.putIfAbsent(f.getField(), f.getDefaultMessage()));
    e.getBindingResult().getGlobalErrors().forEach(g -> details.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
    return body(HttpStatus.BAD_REQUEST, "Invalid request", details);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<Map<String, Object>> invalidParams(HandlerMethodValidationException e) {
    Map<String, String> details = new LinkedHashMap<>();
    e.getParameterValidationResults().forEach(r -> r.getResolvableErrors()
        .forEach(err -> details.putIfAbsent(r.getMethodParameter().getParameterName(), err.getDefaultMessage())));
    return body(HttpStatus.BAD_REQUEST, "Invalid request", details);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Map<String, Object>> violations(ConstraintViolationException e) {
    Map<String, String> details = new LinkedHashMap<>();
    e.getConstraintViolations().forEach(v -> details.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
    return body(HttpStatus.BAD_REQUEST, "Invalid request", details);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
    return body(HttpStatus.BAD_REQUEST, "Malformed request body", null);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException e) {
    return body(HttpStatus.BAD_REQUEST, "Query parameter \"" + e.getParameterName() + "\" is required", null);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
    return body(HttpStatus.NOT_FOUND, "Unknown API endpoint", null);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
    return body(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", null);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, Object>> unexpected(Exception e) {
    log.error("Unhandled error", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", null);
  }
}
