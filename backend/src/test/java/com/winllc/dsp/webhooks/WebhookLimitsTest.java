package com.winllc.dsp.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The send deadline, the outbox cap and batched writes of the file store. */
class WebhookLimitsTest {
  @TempDir Path dir;

  @Test
  void aReceiverThatDripsBytesCannotHoldTheSenderPastTheDeadline() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread drip = new Thread(() -> {
        try (Socket s = server.accept()) {
          OutputStream out = s.getOutputStream();
          // One byte every 300 ms, forever: each read succeeds within any per-read timeout.
          for (int i = 0; i < 200; i++) {
            out.write(0x16);
            out.flush();
            Thread.sleep(300);
          }
        } catch (Exception ignored) {
          // the sender gave up
        }
      });
      drip.setDaemon(true);
      drip.start();
      WebhookSender sender = new WebhookSender(SSLContext.getDefault(), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(1));
      UrlGuard.Target target = new UrlGuard.Target(URI.create("https://localhost:" + server.getLocalPort() + "/"), "localhost", server.getLocalPort(),
          InetAddress.getLoopbackAddress());
      Instant start = Instant.now();
      WebhookSender.Result r = sender.send(target, Map.of(), "{}".getBytes());
      assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(4));
      assertThat(r.delivered()).isFalse();
      assertThat(r.error()).isNotBlank();
    }
  }

  @Test
  void aSlowNameLookupCountsAgainstTheDeadline() {
    UrlGuard slow = new UrlGuard(List.of(), host -> {
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new InetAddress[] {InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34})};
    });
    Instant start = Instant.now();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> slow.resolve(URI.create("https://hooks.example/"), Duration.ofMillis(300)))
        .isInstanceOf(UrlGuard.Refused.class).hasMessageContaining("did not resolve");
    assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
  }

  private static Deliveries.Delivery delivery(String webhook) {
    Instant now = Instant.now();
    return new Deliveries.Delivery(UUID.randomUUID().toString(), webhook, "evt_" + UUID.randomUUID(), "entry.changed", "{}", Deliveries.PENDING, 0, now,
        now, null, null, null, null);
  }

  @Test
  void theFileOutboxIsCappedAndWrittenInBatches() throws Exception {
    Path file = dir.resolve(FileDeliveries.FILE);
    FileDeliveries store = new FileDeliveries(file, JsonMapper.builder().build());
    store.maxPending(3);
    List<Deliveries.Delivery> batch = new ArrayList<>();
    for (int i = 0; i < 5; i++) batch.add(delivery("w1"));
    assertThat(store.enqueue(batch)).isEqualTo(3);
    assertThat(store.enqueue(List.of(delivery("w1")))).isZero();
    // Nothing written per change: the file appears at the next flush, with what was accepted.
    assertThat(Files.exists(file)).isFalse();
    store.flush();
    assertThat(new FileDeliveries(file, JsonMapper.builder().build()).recent("w1", 10)).hasSize(3);
    // A delivered one makes room again.
    Deliveries.Delivery claimed = store.claim(Instant.now(), 1, Duration.ofMinutes(1)).get(0);
    store.record(claimed.attempted(Instant.now(), 204, null, Deliveries.DELIVERED, claimed.nextAttemptAt()));
    assertThat(store.enqueue(List.of(delivery("w1")))).isEqualTo(1);
  }
}
