package com.winllc.dsp.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Syslog (RFC 5424) over udp, tcp or tls: {@code <PRI>1 TIME HOST directory-portal - <action> - <json line>},
 * with no structured data, PRI from the facility and the event's severity (info 6, notice 5,
 * warning 4, alert 1), and octet-counting framing on tcp and tls (RFC 6587 / RFC 5425).
 *
 * <p>TCP has no delivery acknowledgement, so a write into a connection the collector has closed succeeds
 * locally and is lost. Before each batch the connection is therefore checked (the collector never sends,
 * so a read that returns end of stream or fails means it is gone) and replaced after
 * {@link #IDLE_RECONNECT_MILLIS} unused. When a connection turns out broken, the batch last written on it
 * is sent again on the next one, so a collector restart can repeat up to one batch (same event {@code id}).
 * A batch that cannot be written within {@link #timeoutMillis} (the collector stopped reading) fails. Even
 * so, tcp and tls are best effort; where no event may be missing use Splunk HEC or the pull API.
 */
public class SyslogForwarder extends AuditForwarder {
  private static final Map<String, Integer> FACILITIES = Map.ofEntries(
      Map.entry("kern", 0), Map.entry("user", 1), Map.entry("mail", 2), Map.entry("daemon", 3), Map.entry("auth", 4),
      Map.entry("syslog", 5), Map.entry("lpr", 6), Map.entry("news", 7), Map.entry("uucp", 8), Map.entry("cron", 9),
      Map.entry("authpriv", 10), Map.entry("ftp", 11), Map.entry("local0", 16), Map.entry("local1", 17),
      Map.entry("local2", 18), Map.entry("local3", 19), Map.entry("local4", 20), Map.entry("local5", 21),
      Map.entry("local6", 22), Map.entry("local7", 23));

  private final String host;
  private final int port;
  private final String protocol;
  private final int facility;
  private final String hostname;
  private final String appName;
  /** A connection unused this long is replaced before the next batch: a firewall may have dropped it. */
  static final long IDLE_RECONNECT_MILLIS = 30_000;

  int timeoutMillis = 10_000;
  private Socket socket;
  /** The TCP socket under {@link #socket} (the same one without tls). */
  private Socket raw;
  private OutputStream out;
  private DatagramSocket udp;
  private long lastUsed;
  /** The batch last written on the current connection, and one written on a connection that then broke. */
  private List<AuditStore.Stored> lastBatch = List.of();
  private List<AuditStore.Stored> resend = List.of();

  public SyslogForwarder(AuditProperties.Syslog cfg, String hostname, String appName, AuditStore store, AuditState state,
      Listener listener) {
    super("syslog", store, state, listener);
    this.host = cfg.getHost().trim();
    this.port = cfg.effectivePort();
    String p = cfg.getProtocol() == null ? "tcp" : cfg.getProtocol().trim().toLowerCase(Locale.ROOT);
    this.protocol = p.equals("udp") || p.equals("tls") ? p : "tcp";
    Integer f = FACILITIES.get(cfg.getFacility() == null ? "local0" : cfg.getFacility().trim().toLowerCase(Locale.ROOT));
    this.facility = f == null ? 16 : f;
    this.hostname = printable(hostname, 255);
    this.appName = printable(appName, 48);
  }

  @Override
  public String destination() {
    return protocol + "://" + host + ":" + port;
  }

  static int severityCode(String severity) {
    if (severity == null) return 6;
    return switch (severity) {
      case "alert" -> 1;
      case "warning" -> 4;
      case "notice" -> 5;
      default -> 6;
    };
  }

  /** One RFC 5424 message (without framing). */
  String message(AuditStore.Stored s) {
    int pri = facility * 8 + severityCode(field(s.line(), "severity"));
    String time = s.time() == null ? "-" : s.time();
    String msgid = printable(field(s.line(), "action"), 32);
    return "<" + pri + ">1 " + time + " " + hostname + " " + appName + " - " + msgid + " - " + s.line();
  }

  private static String printable(String s, int max) {
    if (s == null || s.isBlank()) return "-";
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (c > 32 && c < 127) sb.append(c);
      if (sb.length() >= max) break;
    }
    return sb.isEmpty() ? "-" : sb.toString();
  }

  @Override
  protected synchronized void send(List<AuditStore.Stored> batch) throws IOException {
    if (protocol.equals("udp")) {
      if (udp == null) udp = new DatagramSocket();
      InetAddress address = InetAddress.getByName(host);
      for (AuditStore.Stored s : batch) {
        byte[] b = message(s).getBytes(StandardCharsets.UTF_8);
        udp.send(new DatagramPacket(b, b.length, address, port));
      }
      return;
    }
    if (batch.isEmpty()) return;
    try {
      if (socket != null) {
        if (socket.isClosed() || peerClosed(socket)) {
          broken();
        } else if (System.nanoTime() - lastUsed > TimeUnit.MILLISECONDS.toNanos(IDLE_RECONNECT_MILLIS)) {
          closeConnection();
          lastBatch = List.of();
        }
      }
      if (socket == null) connect();
      long first = batch.get(0).seq();
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      for (AuditStore.Stored s : resend) {
        if (s.seq() < first) frame(bytes, s);
      }
      for (AuditStore.Stored s : batch) frame(bytes, s);
      write(bytes.toByteArray());
    } catch (IOException e) {
      broken();
      throw e;
    }
    resend = List.of();
    lastBatch = List.copyOf(batch);
    lastUsed = System.nanoTime();
  }

  private void frame(ByteArrayOutputStream bytes, AuditStore.Stored s) throws IOException {
    byte[] b = message(s).getBytes(StandardCharsets.UTF_8);
    bytes.write((b.length + " ").getBytes(StandardCharsets.US_ASCII));
    bytes.write(b);
  }

  /** Writes and flushes, closing the TCP socket when that takes longer than {@link #timeoutMillis}. */
  private void write(byte[] bytes) throws IOException {
    Socket s = raw;
    AtomicBoolean expired = new AtomicBoolean();
    CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> {
      expired.set(true);
      closeQuietly(s);
    }, CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS));
    try {
      out.write(bytes);
      out.flush();
    } catch (IOException e) {
      if (expired.get()) throw new SocketTimeoutException("the syslog collector took no data for " + timeoutMillis + " ms");
      throw e;
    } finally {
      deadline.cancel(false);
    }
  }

  /**
   * Whether the collector has closed the connection. It never sends anything, so a read that times out at
   * once means the connection is open; end of stream or an error means it is gone.
   */
  static boolean peerClosed(Socket s) {
    try {
      int timeout = s.getSoTimeout();
      s.setSoTimeout(1);
      try {
        byte[] discard = new byte[512];
        for (int i = 0; i < 16; i++) {
          if (s.getInputStream().read(discard) < 0) return true;
        }
        return false; // it is sending something, so it is there
      } finally {
        s.setSoTimeout(timeout);
      }
    } catch (SocketTimeoutException e) {
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  /** The connection failed or the collector closed it: the last batch written on it may not have arrived. */
  private void broken() {
    if (!lastBatch.isEmpty()) resend = lastBatch;
    lastBatch = List.of();
    closeConnection();
  }

  private void connect() throws IOException {
    Socket s;
    if (protocol.equals("tls")) {
      Socket plain = new Socket();
      raw = plain;
      plain.connect(new InetSocketAddress(host, port), timeoutMillis);
      SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
      var params = tls.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(params);
      tls.startHandshake();
      s = tls;
    } else {
      s = new Socket();
      raw = s;
      s.connect(new InetSocketAddress(host, port), timeoutMillis);
    }
    s.setSoTimeout(timeoutMillis);
    socket = s;
    out = s.getOutputStream();
  }

  @Override
  protected synchronized void close() {
    closeConnection();
    if (udp != null) udp.close();
    udp = null;
  }

  private void closeConnection() {
    closeQuietly(socket);
    closeQuietly(raw);
    socket = null;
    raw = null;
    out = null;
  }

  private static void closeQuietly(Socket s) {
    try {
      if (s != null) s.close();
    } catch (IOException e) {
      // closing anyway
    }
  }
}
