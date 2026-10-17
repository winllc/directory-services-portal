package com.winllc.dsp.audit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Syslog (RFC 5424) over udp, tcp or tls: {@code <PRI>1 TIME HOST directory-portal - <action> - <json line>},
 * with no structured data, PRI from the facility and the event's severity (info 6, notice 5,
 * warning 4, alert 1), and octet-counting framing on tcp and tls (RFC 6587 / RFC 5425).
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
  int timeoutMillis = 10_000;
  private Socket socket;
  private OutputStream out;
  private DatagramSocket udp;

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
    if (socket == null || socket.isClosed()) connect();
    for (AuditStore.Stored s : batch) {
      byte[] b = message(s).getBytes(StandardCharsets.UTF_8);
      out.write((b.length + " ").getBytes(StandardCharsets.US_ASCII));
      out.write(b);
    }
    out.flush();
  }

  private void connect() throws IOException {
    Socket s;
    if (protocol.equals("tls")) {
      Socket plain = new Socket();
      plain.connect(new InetSocketAddress(host, port), timeoutMillis);
      SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, host, port, true);
      var params = tls.getSSLParameters();
      params.setEndpointIdentificationAlgorithm("HTTPS");
      tls.setSSLParameters(params);
      tls.startHandshake();
      s = tls;
    } else {
      s = new Socket();
      s.connect(new InetSocketAddress(host, port), timeoutMillis);
    }
    s.setSoTimeout(timeoutMillis);
    socket = s;
    out = s.getOutputStream();
  }

  @Override
  protected synchronized void close() {
    try {
      if (socket != null) socket.close();
    } catch (IOException e) {
      // closing anyway
    }
    socket = null;
    out = null;
    if (udp != null) udp.close();
    udp = null;
  }
}
