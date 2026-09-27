package com.winllc.dsp.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Writes {"error": "..."} responses from servlet filters (outside of Spring MVC). */
final class JsonErrors {
  private JsonErrors() {}

  static void write(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
  }
}
