package com.winllc.dsp.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes {"error": "..."} responses from servlet filters (outside of Spring MVC). */
final class JsonErrors {
  private JsonErrors() {}

  static void write(HttpServletResponse response, int status, String message) throws IOException {
    write(response, status, message, null);
  }

  /** With a machine-readable {@code code} (e.g. "session_expired"), when not null. */
  static void write(HttpServletResponse response, int status, String message, String code) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":" + quote(message) + (code == null ? "" : ",\"code\":" + quote(code)) + "}");
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
