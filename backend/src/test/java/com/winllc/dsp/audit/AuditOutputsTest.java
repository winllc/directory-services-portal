package com.winllc.dsp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Splunk HEC and syslog forwarders against local stub servers. */
class AuditOutputsTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path dir;
  HttpServer http;

  record Request(String auth, String contentType, String body) {}

  @AfterEach
  void stop() {
    if (http != null) http.stop(0);
  }

  /** A HEC stub: answers 503 to the first {@code failures} requests, then 200. */
  List<Request> hec(int failures) throws IOException {
    List<Request> got = new CopyOnWriteArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/services/collector/event", ex -> {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      got.add(new Request(ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Type"), body));
      boolean fail = calls.incrementAndGet() <= failures;
      byte[] out = (fail ? "{\"text\":\"Server is busy\",\"code\":9}" : "{\"text\":\"Success\",\"code\":0}").getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(fail ? 503 : 200, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    });
    http.start();
    return got;
  }

  AuditStore storeWith(int n) throws IOException {
    AuditStore store = new AuditStore(dir);
    for (int i = 0; i < n; i++) {
      store.append(AuditChainTest.event(i == 0 ? "auth.login" : "entry.update", "2026-10-16T02:10:11.12" + i + "Z"), null);
    }
    return store;
  }

  static final class Recorder implements AuditForwarder.Listener {
    final List<String> calls = new ArrayList<>();

    @Override
    public void failed(String output, String error, Instant since) {
      calls.add("failed " + output + ": " + error);
    }

    @Override
    public void recovered(String output, Instant failingSince) {
      calls.add("recovered " + output);
    }
  }

  SplunkHecForwarder splunk(AuditStore store, AuditState state, AuditForwarder.Listener listener) {
    AuditProperties.Splunk cfg = new AuditProperties.Splunk();
    cfg.setUrl("http://127.0.0.1:" + http.getAddress().getPort());
    cfg.setToken("hec-token-123");
    cfg.setIndex("security");
    return new SplunkHecForwarder(cfg, "portal-1", "directory-portal", store, state, listener);
  }

  @Test
  void hecPostsBatchesWithTheTokenRetriesAndPersistsTheCursor() throws Exception {
    List<Request> got = hec(1);
    AuditStore store = storeWith(3);
    AuditState state = new AuditState(dir, MAPPER);
    Recorder rec = new Recorder();
    SplunkHecForwarder f = splunk(store, state, rec);
    f.alertAfter = Duration.ZERO;

    assertThatThrownBy(f::deliverOnce).hasMessageContaining("HTTP 503");
    assertThat(f.status().lastError()).contains("503");
    assertThat(f.status().backlog()).isEqualTo(3);
    assertThat(rec.calls).containsExactly("failed splunk: HEC returned HTTP 503: {\"text\":\"Server is busy\",\"code\":9}");
    assertThat(state.cursor("splunk").seq()).isZero();

    assertThat(f.deliverOnce()).isTrue();
    assertThat(rec.calls).last().isEqualTo("recovered splunk");
    assertThat(f.deliverOnce()).isFalse();
    assertThat(got).hasSize(2);
    Request r = got.get(1);
    assertThat(r.auth()).isEqualTo("Splunk hec-token-123");
    assertThat(r.contentType()).isEqualTo("application/json");
    String[] events = r.body().split("\n");
    assertThat(events).hasSize(3);
    for (int i = 0; i < 3; i++) {
      JsonNode e = MAPPER.readTree(events[i]);
      assertThat(e.propertyNames()).containsExactly("time", "host", "source", "sourcetype", "index", "event");
      assertThat(events[i]).startsWith("{\"time\":1792116611.12" + i + ",");
      assertThat(e.get("host").asString()).isEqualTo("portal-1");
      assertThat(e.get("source").asString()).isEqualTo("directory-portal");
      assertThat(e.get("sourcetype").asString()).isEqualTo("ens:audit");
      assertThat(e.get("index").asString()).isEqualTo("security");
      assertThat(e.get("event").get("schema").asString()).isEqualTo("ens-audit/1");
      assertThat(e.get("event").get("seq").asLong()).isEqualTo(i + 1);
    }
    assertThat(r.body()).doesNotContain("hec-token-123");

    // The cursor survives a restart: nothing is sent again.
    assertThat(new AuditState(dir, MAPPER).cursor("splunk").seq()).isEqualTo(3);
    SplunkHecForwarder restarted = splunk(store, new AuditState(dir, MAPPER), null);
    assertThat(restarted.deliverOnce()).isFalse();
    store.append(AuditChainTest.event("auth.logout", "2026-10-16T03:00:00.000Z"), null);
    assertThat(restarted.deliverOnce()).isTrue();
    assertThat(got).hasSize(3);
    assertThat(got.get(2).body().split("\n")).hasSize(1);
  }

  @Test
  void hecBatchesAtMostAHundredEvents() throws Exception {
    List<Request> got = hec(0);
    AuditStore store = storeWith(0);
    for (int i = 0; i < 150; i++) store.append(AuditChainTest.event("entry.update", "2026-10-16T02:10:11.000Z"), null);
    SplunkHecForwarder f = splunk(store, new AuditState(dir, MAPPER), null);
    while (f.deliverOnce()) {
      // drain
    }
    assertThat(got).extracting(r -> r.body().split("\n").length).containsExactly(100, 50);
  }

  @Test
  void backgroundDeliveryRetriesUntilTheOutputRecovers() throws Exception {
    List<Request> got = hec(2);
    AuditStore store = storeWith(2);
    AuditState state = new AuditState(dir, MAPPER);
    SplunkHecForwarder f = splunk(store, state, null);
    f.initialBackoffMillis = 20;
    f.start();
    try {
      long deadline = System.currentTimeMillis() + 10_000;
      while (state.cursor("splunk").seq() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20);
      assertThat(state.cursor("splunk").seq()).isEqualTo(2);
      assertThat(got).hasSize(3);
      store.append(AuditChainTest.event("auth.logout", "2026-10-16T03:00:00.000Z"), null);
      while (state.cursor("splunk").seq() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20);
      assertThat(state.cursor("splunk").seq()).isEqualTo(3);
      assertThat(f.status().lastErrorAt()).isNotNull();
      assertThat(f.status().failingSince()).isNull();
    } finally {
      f.stop();
    }
  }

  @Test
  void syslogSendsRfc5424OverTcpWithOctetCounting() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      AuditStore store = storeWith(2);
      AuditProperties.Syslog cfg = new AuditProperties.Syslog();
      cfg.setHost("127.0.0.1");
      cfg.setPort(server.getLocalPort());
      cfg.setProtocol("tcp");
      cfg.setFacility("local0");
      AuditState state = new AuditState(dir, MAPPER);
      SyslogForwarder f = new SyslogForwarder(cfg, "portal-1", "directory-portal", store, state, null);
      ByteArrayOutputStream received = new ByteArrayOutputStream();
      Thread reader = new Thread(() -> {
        try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
          in.transferTo(received);
        } catch (IOException e) {
          // closed
        }
      });
      reader.start();
      assertThat(f.deliverOnce()).isTrue();
      f.stop();
      reader.join(5_000);

      String stream = received.toString(StandardCharsets.UTF_8);
      byte[] bytes = received.toByteArray();
      List<String> messages = new ArrayList<>();
      int pos = 0;
      while (pos < bytes.length) {
        int space = pos;
        while (bytes[space] != ' ') space++;
        int len = Integer.parseInt(new String(bytes, pos, space - pos, StandardCharsets.US_ASCII));
        messages.add(new String(bytes, space + 1, len, StandardCharsets.UTF_8));
        pos = space + 1 + len;
      }
      assertThat(pos).as(stream).isEqualTo(bytes.length);
      assertThat(messages).hasSize(2);
      List<AuditStore.Stored> stored = store.readAfter(0, 10);
      assertThat(messages.get(0)).isEqualTo("<134>1 2026-10-16T02:10:11.120Z portal-1 directory-portal - auth.login - " + stored.get(0).line());
      assertThat(messages.get(1)).startsWith("<134>1 2026-10-16T02:10:11.121Z portal-1 directory-portal - entry.update - {\"schema\":");
      assertThat(state.cursor("syslog").seq()).isEqualTo(2);
    }
  }

  @Test
  void syslogMapsSeverities() {
    assertThat(SyslogForwarder.severityCode("info")).isEqualTo(6);
    assertThat(SyslogForwarder.severityCode("notice")).isEqualTo(5);
    assertThat(SyslogForwarder.severityCode("warning")).isEqualTo(4);
    assertThat(SyslogForwarder.severityCode("alert")).isEqualTo(1);
  }
}
