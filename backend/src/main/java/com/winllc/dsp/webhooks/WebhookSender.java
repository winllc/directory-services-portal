package com.winllc.dsp.webhooks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Sends one webhook: an HTTPS POST to exactly the address {@link UrlGuard} checked (the TLS
 * certificate is still verified against the host name), without proxies or redirects. A 2xx answer
 * is a delivery; anything else, including a redirect, is a failed attempt. Each send has an
 * overall deadline (connect, TLS handshake, request and answer together): a watchdog closes the
 * socket when it passes, so a receiver that answers a byte at a time can't hold the sender.
 */
public class WebhookSender {
  private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "dsp-webhook-watchdog");
    t.setDaemon(true);
    return t;
  });
  public static final String SIGNATURE_HEADER = "X-DSP-Signature";
  public static final String TIMESTAMP_HEADER = "X-DSP-Timestamp";

  /** The answer: the HTTP status, or 0 with an error when there was none. */
  public record Result(int status, String error) {
    public boolean delivered() {
      return status >= 200 && status < 300;
    }
  }

  private final SSLContext tls;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final Duration deadline;

  public WebhookSender(SSLContext tls, Duration connectTimeout, Duration readTimeout) {
    this(tls, connectTimeout, readTimeout, connectTimeout.plus(readTimeout));
  }

  /** {@code deadline}: the most one send may take in all. */
  public WebhookSender(SSLContext tls, Duration connectTimeout, Duration readTimeout, Duration deadline) {
    this.tls = tls;
    this.connectTimeout = connectTimeout;
    this.readTimeout = readTimeout;
    this.deadline = deadline;
  }

  /** The longest one send can take. */
  public Duration deadline() {
    return deadline;
  }

  /**
   * {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">}: receivers recompute it with the
   * webhook's secret, compare in constant time and reject timestamps more than a few minutes old.
   */
  public static String sign(String secret, long timestamp, byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
      return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  public Result send(UrlGuard.Target target, Map<String, String> headers, byte[] body) {
    Socket watched = new Socket();
    java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
    ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
      expired.set(true);
      try {
        watched.close();
      } catch (IOException ignored) {
        // closing is all it can do
      }
    }, deadline.toMillis(), TimeUnit.MILLISECONDS);
    Result result;
    try {
      result = sendOn(watched, target, headers, body);
    } finally {
      watchdog.cancel(false);
    }
    if (expired.get() && !result.delivered()) return new Result(0, "No complete answer within " + deadline.toSeconds() + " s");
    return result;
  }

  private Result sendOn(Socket socketToUse, UrlGuard.Target target, Map<String, String> headers, byte[] body) {
    try (Socket raw = socketToUse) {
      raw.connect(new InetSocketAddress(target.address(), target.port()), (int) Math.min(connectTimeout.toMillis(), deadline.toMillis()));
      raw.setSoTimeout((int) readTimeout.toMillis());
      try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, target.host(), target.port(), true)) {
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(target.host())) params.setServerNames(List.of(new SNIHostName(target.host())));
        socket.setSSLParameters(params);
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        out.write(request(target, headers, body.length).getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        return new Result(status(socket.getInputStream()), null);
      }
    } catch (IOException | RuntimeException e) {
      return new Result(0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  private static String request(UrlGuard.Target target, Map<String, String> headers, int length) {
    String path = target.uri().getRawPath() == null || target.uri().getRawPath().isEmpty() ? "/" : target.uri().getRawPath();
    if (target.uri().getRawQuery() != null) path += "?" + target.uri().getRawQuery();
    String host = target.host().contains(":") ? "[" + target.host() + "]" : target.host();
    if (target.port() != 443) host += ":" + target.port();
    StringBuilder sb = new StringBuilder();
    sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
    sb.append("Host: ").append(host).append("\r\n");
    sb.append("User-Agent: DirectoryServicesPortal-Webhooks/1\r\n");
    sb.append("Content-Type: application/json; charset=utf-8\r\n");
    sb.append("Content-Length: ").append(length).append("\r\n");
    sb.append("Connection: close\r\n");
    headers.forEach((k, v) -> sb.append(k).append(": ").append(v.replaceAll("[\\r\\n]", "")).append("\r\n"));
    return sb.append("\r\n").toString();
  }

  /** The status code of the response's first line (reads at most 8 KB of it). */
  private static int status(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1 && c != '\n') {
      if (line.size() >= 8192) throw new IOException("The response's status line is too long");
      line.write(c);
    }
    String s = line.toString(StandardCharsets.US_ASCII).trim();
    String[] parts = s.split(" ", 3);
    if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Not an HTTP response");
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      throw new IOException("Not an HTTP response");
    }
  }

  private static boolean isIpLiteral(String host) {
    return host.contains(":") || host.matches("[0-9.]+");
  }
}
