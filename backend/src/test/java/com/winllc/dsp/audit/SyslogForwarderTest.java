package com.winllc.dsp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Syslog over tcp against a local listener that goes away or stops reading, as a collector does. */
class SyslogForwarderTest {
  @TempDir Path dir;

  @Test
  void theBatchAfterTheCollectorClosedTheConnectionGoesOutOnANewOne() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(5000);
      SyslogForwarder f = forwarder(server.getLocalPort());
      try {
        f.send(List.of(event(1, "one"), event(2, "two")));
        try (Socket first = server.accept()) {
          first.setSoTimeout(5000);
          assertThat(lines(first.getInputStream(), 2)).containsExactly(line(1, "one"), line(2, "two"));
        } // the collector restarts: it closes the connection
        Thread.sleep(100);

        f.send(List.of(event(3, "three")));
        try (Socket second = server.accept()) {
          second.setSoTimeout(5000);
          // the last batch on the closed connection is sent again: it may not have arrived
          assertThat(lines(second.getInputStream(), 3))
              .containsExactly(line(1, "one"), line(2, "two"), line(3, "three"));
        }
      } finally {
        f.close();
      }
    }
  }

  @Test
  void aCollectorThatStopsReadingFailsTheBatchInsteadOfBlocking() throws Exception {
    try (ServerSocket server = new ServerSocket()) {
      server.setReceiveBufferSize(4096);
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 5);
      SyslogForwarder f = forwarder(server.getLocalPort());
      f.timeoutMillis = 500;
      String big = "x".repeat(64 * 1024);
      List<AuditStore.Stored> batch = LongStream.rangeClosed(1, 200).mapToObj(i -> event(i, big)).toList();
      try {
        assertTimeoutPreemptively(Duration.ofSeconds(20),
            () -> assertThatThrownBy(() -> f.send(batch)).isInstanceOf(SocketTimeoutException.class));
      } finally {
        f.close();
      }
    }
  }

  SyslogForwarder forwarder(int port) throws IOException {
    AuditProperties.Syslog cfg = new AuditProperties.Syslog();
    cfg.setHost("127.0.0.1");
    cfg.setPort(port);
    cfg.setProtocol("tcp");
    cfg.setFacility("local0");
    return new SyslogForwarder(cfg, "portal-1", "directory-portal", new FileAuditStore(dir),
        new AuditState(dir, AuditOutputsTest.MAPPER), null);
  }

  static String line(long seq, String note) {
    return "{\"seq\":" + seq + ",\"action\":\"auth.login\",\"note\":\"" + note + "\"}";
  }

  static AuditStore.Stored event(long seq, String note) {
    return new AuditStore.Stored(seq, line(seq, note));
  }

  /** The JSON lines (the last field) of the next count octet-counted messages. */
  static List<String> lines(InputStream in, int count) throws IOException {
    List<String> lines = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      StringBuilder length = new StringBuilder();
      int c;
      while ((c = in.read()) != ' ') {
        assertThat(c).as("frame length digit").isBetween((int) '0', (int) '9');
        length.append((char) c);
      }
      String message = new String(in.readNBytes(Integer.parseInt(length.toString())), StandardCharsets.UTF_8);
      lines.add(message.substring(message.lastIndexOf(" - ") + 3));
    }
    return lines;
  }
}
