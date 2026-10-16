package com.winllc.dsp.audit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Splunk HTTP Event Collector: POST {@code /services/collector/event} with
 * {@code Authorization: Splunk <token>}, batches of up to 100 events, each wrapped as
 * {@code {"time":<epoch seconds.ms>,"host":…,"source":"directory-portal","sourcetype":…,"index"?,"event":<event>}}.
 * TLS trust is the JVM default; {@code insecure} (test setups only) trusts any certificate.
 */
public class SplunkHecForwarder extends AuditForwarder {
  private final URI endpoint;
  private final String token;
  private final String host;
  private final String source;
  private final String sourcetype;
  private final String index;
  private final boolean insecure;
  int timeoutMillis = 10_000;

  public SplunkHecForwarder(AuditProperties.Splunk cfg, String host, String source, AuditStore store, AuditState state,
      Listener listener) {
    super("splunk", store, state, listener);
    String url = cfg.getUrl().trim().replaceAll("/+$", "");
    this.endpoint = URI.create(url.contains("/services/collector") ? url : url + "/services/collector/event");
    this.token = readToken(cfg);
    this.host = host;
    this.source = source;
    this.sourcetype = cfg.getSourcetype() == null || cfg.getSourcetype().isBlank() ? "ens:audit" : cfg.getSourcetype();
    this.index = cfg.getIndex() == null || cfg.getIndex().isBlank() ? null : cfg.getIndex();
    this.insecure = cfg.isInsecure();
  }

  private static String readToken(AuditProperties.Splunk cfg) {
    if (cfg.getTokenFile() != null && !cfg.getTokenFile().isBlank()) {
      try {
        return Files.readString(Path.of(cfg.getTokenFile())).trim();
      } catch (IOException e) {
        return null;
      }
    }
    return cfg.getToken() == null || cfg.getToken().isBlank() ? null : cfg.getToken().trim();
  }

  public boolean hasToken() {
    return token != null;
  }

  @Override
  public String destination() {
    return endpoint.toString();
  }

  /** The request body: one HEC event object per stored event, newline separated. */
  String payload(List<AuditStore.Stored> batch) {
    StringBuilder sb = new StringBuilder();
    for (AuditStore.Stored s : batch) {
      Instant t;
      try {
        t = Instant.parse(s.time());
      } catch (RuntimeException e) {
        t = Instant.now();
      }
      long ms = t.toEpochMilli();
      sb.append("{\"time\":").append(Math.floorDiv(ms, 1000)).append('.').append(String.format("%03d", Math.floorMod(ms, 1000)));
      sb.append(",\"host\":");
      AuditJson.str(sb, host);
      sb.append(",\"source\":");
      AuditJson.str(sb, source);
      sb.append(",\"sourcetype\":");
      AuditJson.str(sb, sourcetype);
      if (index != null) {
        sb.append(",\"index\":");
        AuditJson.str(sb, index);
      }
      sb.append(",\"event\":").append(s.line()).append("}\n");
    }
    return sb.toString();
  }

  @Override
  protected void send(List<AuditStore.Stored> batch) throws IOException {
    if (token == null) throw new IOException("No HEC token (ENS_AUDIT_SPLUNK_TOKEN or ENS_AUDIT_SPLUNK_TOKEN_FILE)");
    byte[] body = payload(batch).getBytes(StandardCharsets.UTF_8);
    HttpURLConnection c = (HttpURLConnection) endpoint.toURL().openConnection();
    try {
      if (insecure && c instanceof HttpsURLConnection https) {
        https.setSSLSocketFactory(trustAll());
        https.setHostnameVerifier((h, s) -> true);
      }
      c.setConnectTimeout(timeoutMillis);
      c.setReadTimeout(timeoutMillis);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setRequestProperty("Authorization", "Splunk " + token);
      c.setRequestProperty("Content-Type", "application/json");
      c.setFixedLengthStreamingMode(body.length);
      try (OutputStream out = c.getOutputStream()) {
        out.write(body);
      }
      int status = c.getResponseCode();
      if (status < 200 || status >= 300) {
        String detail = "";
        try (InputStream err = c.getErrorStream()) {
          if (err != null) detail = new String(err.readNBytes(300), StandardCharsets.UTF_8).trim();
        }
        throw new IOException("HEC returned HTTP " + status + (detail.isEmpty() ? "" : ": " + detail));
      }
      try (InputStream in = c.getInputStream()) {
        in.readAllBytes();
      }
    } finally {
      c.disconnect();
    }
  }

  private static SSLSocketFactory trustAll() throws IOException {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return new X509Certificate[0];
        }
      }}, new SecureRandom());
      return ctx.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new IOException(e.getMessage(), e);
    }
  }
}
