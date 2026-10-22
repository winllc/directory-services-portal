package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.winllc.dsp.integration.IntegrationProperties;
import com.winllc.dsp.webhooks.Deliveries;
import com.winllc.dsp.webhooks.UrlGuard;
import com.winllc.dsp.webhooks.WebhookSender;
import com.winllc.dsp.webhooks.WebhookService;
import com.winllc.dsp.webhooks.Webhooks;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Webhook delivery runs against local receivers, for either outbox (the file alone, the table with
 * the shared database): a dead receiver is backed off as a whole, and one webhook can't take a
 * whole run. Each check pauses the service's background runs, so it sees exactly one run.
 */
public final class WebhookRuns {
  private WebhookRuns() {}

  /** TLS for a receiver on localhost (and hooks.example), and a client that trusts it. */
  public record Tls(SSLContext server, SSLContext client) {}

  public static Tls localhostTls() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(256);
    KeyPair keys = gen.generateKeyPair();
    X500Name name = new X500Name("CN=localhost");
    Instant now = Instant.now();
    var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now.toEpochMilli()), Date.from(now.minusSeconds(60)),
        Date.from(now.plus(Duration.ofDays(1))), name, keys.getPublic());
    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
        new GeneralName(GeneralName.dNSName, "localhost"), new GeneralName(GeneralName.dNSName, "hooks.example")}));
    X509Certificate cert = new JcaX509CertificateConverter()
        .getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
    KeyStore ks = KeyStore.getInstance("PKCS12");
    ks.load(null, null);
    ks.setKeyEntry("server", keys.getPrivate(), "pw".toCharArray(), new X509Certificate[] {cert});
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, "pw".toCharArray());
    SSLContext serverTls = SSLContext.getInstance("TLS");
    serverTls.init(kmf.getKeyManagers(), null, null);
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("server", cert);
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext clientTls = SSLContext.getInstance("TLS");
    clientTls.init(null, tmf.getTrustManagers(), null);
    return new Tls(serverTls, clientTls);
  }

  /** An HTTPS receiver that answers 204 after {@code delay}, counting requests per path. */
  public static final class Receiver implements AutoCloseable {
    private final HttpsServer server;
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    public Receiver(SSLContext tls, Duration delay) throws IOException {
      server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(tls));
      server.createContext("/", exchange -> {
        exchange.getRequestBody().readAllBytes();
        try {
          Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        hits.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new AtomicInteger()).incrementAndGet();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
      });
      server.start();
    }

    public String url(String path) {
      return "https://localhost:" + server.getAddress().getPort() + path;
    }

    public int hits(String path) {
      AtomicInteger n = hits.get(path);
      return n == null ? 0 : n.get();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }

  /** Accepts TCP connections and never answers (a receiver behind a black hole, or hung). */
  public static final class Blackhole implements AutoCloseable {
    private final ServerSocket socket;
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Socket> held = new CopyOnWriteArrayList<>();

    public Blackhole() throws IOException {
      socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
      Thread t = new Thread(() -> {
        while (!socket.isClosed()) {
          try {
            held.add(socket.accept());
            connections.incrementAndGet();
          } catch (IOException e) {
            return;
          }
        }
      }, "test-blackhole");
      t.setDaemon(true);
      t.start();
    }

    public String url() {
      return "https://localhost:" + socket.getLocalPort() + "/dead";
    }

    public int connections() {
      return connections.get();
    }

    @Override
    public void close() throws IOException {
      socket.close();
      for (Socket s : held) s.close();
    }
  }

  /** Sends to localhost (allowed), with a short deadline. */
  public static void sendLocally(WebhookService service, Tls tls, Duration deadline) {
    UrlGuard guard = new UrlGuard(List.of("localhost"), host -> {
      if (host.equals("localhost")) return new InetAddress[] {InetAddress.getLoopbackAddress()};
      throw new UnknownHostException(host);
    });
    service.use(guard, new WebhookSender(tls.client(), Duration.ofMillis(500), Duration.ofSeconds(5), deadline));
  }

  /** {@code count} due deliveries for a webhook, made {@code age} ago. */
  public static List<Deliveries.Delivery> due(String webhookId, int count, Duration age) {
    Instant at = Instant.now().minus(age).truncatedTo(ChronoUnit.MILLIS);
    List<Deliveries.Delivery> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(new Deliveries.Delivery(UUID.randomUUID().toString(), webhookId, "evt_" + UUID.randomUUID(), "entry.changed", "{}",
          Deliveries.PENDING, 0, at, at, null, null, null, null));
    }
    return out;
  }

  private static String hook(Webhooks webhooks, String url) throws IOException {
    return webhooks.create("run " + System.nanoTime(), url, List.of("entry.changed"), List.of(), true, "admin").webhook().id();
  }

  private static void remove(WebhookService service, Webhooks webhooks, String... ids) throws IOException {
    for (String id : ids) {
      webhooks.delete(id);
      service.deliveries().cancel(id, "test over");
    }
    service.deliveries().flush();
  }

  /**
   * 200 due deliveries for a receiver that accepts the connection and never answers, queued before
   * a few for a live one: one run tries the dead receiver once, postpones the rest of its
   * deliveries to the same time, delivers every live one, and ends well within its budget; the
   * postponed deliveries aren't tried again (in that run or the next).
   */
  public static void aDeadReceiverIsBackedOffAsAWhole(WebhookService service, Webhooks webhooks, IntegrationProperties props) throws Exception {
    Tls tls = localhostTls();
    Duration deadline = Duration.ofSeconds(1);
    service.pauseBackground(true);
    try (Blackhole dead = new Blackhole(); Receiver live = new Receiver(tls.server(), Duration.ZERO)) {
      sendLocally(service, tls, deadline);
      String deadHook = hook(webhooks, dead.url());
      String liveHook = hook(webhooks, live.url("/live"));
      try {
        assertThat(service.deliveries().enqueue(due(deadHook, 200, Duration.ofMinutes(5)))).isEqualTo(200);
        assertThat(service.deliveries().enqueue(due(liveHook, 5, Duration.ZERO))).isEqualTo(5);

        long started = System.nanoTime();
        service.dispatch();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(live.hits("/live")).isEqualTo(5);
        assertThat(dead.connections()).isEqualTo(1);
        assertThat(took).isLessThan(props.getWebhooks().getRunBudget()).isLessThan(deadline.multipliedBy(2).plusSeconds(3));
        List<Deliveries.Delivery> deadRows = service.deliveries().recent(deadHook, 500);
        assertThat(deadRows).hasSize(200).allMatch(d -> Deliveries.PENDING.equals(d.status()));
        List<Deliveries.Delivery> tried = deadRows.stream().filter(d -> d.attempts() > 0).toList();
        assertThat(tried).hasSize(1);
        assertThat(tried.get(0).attempts()).isEqualTo(1);
        assertThat(tried.get(0).lastStatus()).isNull();
        assertThat(tried.get(0).lastError()).isNotBlank();
        Instant next = tried.get(0).nextAttemptAt();
        assertThat(next).isAfter(Instant.now().plusSeconds(20));
        // The others wait just as long, untried.
        assertThat(deadRows).allMatch(d -> d.nextAttemptAt().equals(next), "every delivery of the dead webhook waits until " + next);
        assertThat(service.deliveries().recent(liveHook, 10)).hasSize(5).allMatch(d -> Deliveries.DELIVERED.equals(d.status()));

        // Postponed: the next run doesn't try them either.
        service.dispatch();
        assertThat(dead.connections()).isEqualTo(1);
        assertThat(service.deliveries().recent(deadHook, 500)).filteredOn(d -> d.attempts() > 0).hasSize(1);
      } finally {
        remove(service, webhooks, deadHook, liveHook);
      }
    } finally {
      service.pauseBackground(false);
    }
  }

  /**
   * One webhook gets at most 10 attempts of a run (the claim passes over the rest of its
   * deliveries, leaving them untouched); another webhook's deliveries go out in the same run.
   */
  public static void aWebhookGetsAtMostTenAttemptsARun(WebhookService service, Webhooks webhooks) throws Exception {
    Tls tls = localhostTls();
    service.pauseBackground(true);
    try (Receiver receiver = new Receiver(tls.server(), Duration.ZERO)) {
      sendLocally(service, tls, Duration.ofSeconds(2));
      String busy = hook(webhooks, receiver.url("/busy"));
      String other = hook(webhooks, receiver.url("/other"));
      try {
        service.deliveries().enqueue(due(busy, 15, Duration.ofMinutes(1)));
        service.deliveries().enqueue(due(other, 3, Duration.ZERO));
        service.dispatch();
        assertThat(receiver.hits("/busy")).isEqualTo(WebhookService.ENDPOINT_TRIES);
        assertThat(receiver.hits("/other")).isEqualTo(3);
        List<Deliveries.Delivery> left = service.deliveries().recent(busy, 20).stream().filter(d -> Deliveries.PENDING.equals(d.status())).toList();
        assertThat(left).hasSize(5).allMatch(d -> d.attempts() == 0 && !d.nextAttemptAt().isAfter(Instant.now()));
        // The next run sends them.
        service.dispatch();
        assertThat(receiver.hits("/busy")).isEqualTo(15);
      } finally {
        remove(service, webhooks, busy, other);
      }
    } finally {
      service.pauseBackground(false);
    }
  }

  /**
   * A receiver that answers slowly gets no more of a run than its time share: here 1 s, with
   * 400 ms answers, so at most 3 attempts; a quick receiver's deliveries still go out.
   */
  public static void aSlowReceiverGetsNoMoreThanItsTimeShare(WebhookService service, Webhooks webhooks) throws Exception {
    Tls tls = localhostTls();
    service.pauseBackground(true);
    service.endpointLimits(WebhookService.ENDPOINT_TRIES, Duration.ofSeconds(1));
    try (Receiver slow = new Receiver(tls.server(), Duration.ofMillis(400)); Receiver quick = new Receiver(tls.server(), Duration.ZERO)) {
      sendLocally(service, tls, Duration.ofSeconds(2));
      String slowHook = hook(webhooks, slow.url("/slow"));
      String quickHook = hook(webhooks, quick.url("/quick"));
      try {
        service.deliveries().enqueue(due(slowHook, 10, Duration.ofMinutes(1)));
        service.deliveries().enqueue(due(quickHook, 2, Duration.ZERO));
        service.dispatch();
        assertThat(slow.hits("/slow")).isBetween(1, 3);
        assertThat(quick.hits("/quick")).isEqualTo(2);
      } finally {
        remove(service, webhooks, slowHook, quickHook);
      }
    } finally {
      service.endpointLimits(WebhookService.ENDPOINT_TRIES, WebhookService.ENDPOINT_TIME);
      service.pauseBackground(false);
    }
  }

  /** No attempt starts after the run budget (here 1 s, with 400 ms answers: at most 3). */
  public static void noAttemptStartsAfterTheRunBudget(WebhookService service, Webhooks webhooks, IntegrationProperties props) throws Exception {
    Tls tls = localhostTls();
    Duration budget = props.getWebhooks().getRunBudget();
    Duration deadline = Duration.ofSeconds(2);
    service.pauseBackground(true);
    props.getWebhooks().setRunBudget(Duration.ofSeconds(1));
    try (Receiver slow = new Receiver(tls.server(), Duration.ofMillis(400))) {
      sendLocally(service, tls, deadline);
      String hook = hook(webhooks, slow.url("/slow"));
      try {
        service.deliveries().enqueue(due(hook, 10, Duration.ofMinutes(1)));
        long started = System.nanoTime();
        int attempted = service.dispatch();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(slow.hits("/slow")).isBetween(1, 3);
        assertThat(attempted).isBetween(1, 3);
        // Ends within the budget plus the last attempt's deadline.
        assertThat(took).isLessThan(Duration.ofSeconds(1).plus(deadline).plusSeconds(1));
        assertThat(service.deliveries().recent(hook, 20)).filteredOn(d -> d.attempts() == 0).hasSizeGreaterThanOrEqualTo(7);
      } finally {
        remove(service, webhooks, hook);
      }
    } finally {
      props.getWebhooks().setRunBudget(budget);
      service.pauseBackground(false);
    }
  }

  /**
   * The outbox itself: a claim passes over the webhooks it is told to skip (without claiming or
   * counting their deliveries), and postpone moves only that webhook's pending deliveries due
   * before the given time.
   */
  public static void claimSkipsAndPostponeMoves(Deliveries store) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    String a = UUID.randomUUID().toString();
    String b = UUID.randomUUID().toString();
    Deliveries.Delivery a1 = due(a, 1, Duration.ofSeconds(30)).get(0);
    Deliveries.Delivery a2 = due(a, 1, Duration.ofSeconds(20)).get(0);
    Deliveries.Delivery later = due(a, 1, Duration.ofHours(-1)).get(0);
    Deliveries.Delivery done = new Deliveries.Delivery(UUID.randomUUID().toString(), a, "evt_x", "entry.changed", "{}", Deliveries.DELIVERED, 1,
        now.minusSeconds(60), now.minusSeconds(60), now.minusSeconds(60), 204, null, now.minusSeconds(60));
    Deliveries.Delivery b1 = due(b, 1, Duration.ofSeconds(10)).get(0);
    store.enqueue(List.of(a1, a2, later, done, b1));
    Duration lease = Duration.ofMinutes(1);

    List<Deliveries.Delivery> claimed = store.claim(now, 10, lease, Set.of(a));
    assertThat(claimed).extracting(Deliveries.Delivery::webhookId).doesNotContain(a);
    assertThat(claimed).extracting(Deliveries.Delivery::id).contains(b1.id());
    assertThat(store.recent(a, 10)).filteredOn(d -> Deliveries.PENDING.equals(d.status())).allMatch(d -> d.attempts() == 0);

    Instant until = now.plus(Duration.ofMinutes(10));
    assertThat(store.postpone(a, until)).isEqualTo(2);
    Map<String, Deliveries.Delivery> rows = new ConcurrentHashMap<>();
    store.recent(a, 10).forEach(d -> rows.put(d.id(), d));
    assertThat(rows.get(a1.id()).nextAttemptAt()).isEqualTo(until);
    assertThat(rows.get(a2.id()).nextAttemptAt()).isEqualTo(until);
    assertThat(rows.get(a1.id()).attempts()).isZero();
    // Never earlier, and finished deliveries and other webhooks' are left alone.
    assertThat(rows.get(later.id()).nextAttemptAt()).isEqualTo(later.nextAttemptAt());
    assertThat(rows.get(done.id()).nextAttemptAt()).isEqualTo(done.nextAttemptAt());
    assertThat(rows.get(done.id()).status()).isEqualTo(Deliveries.DELIVERED);
    assertThat(store.recent(b, 10).get(0).attempts()).isEqualTo(1);

    assertThat(store.claim(now, 10, lease)).extracting(Deliveries.Delivery::webhookId).doesNotContain(a, b);
    assertThat(store.claim(until, 10, lease)).extracting(Deliveries.Delivery::id).contains(a1.id(), a2.id()).doesNotContain(later.id());
    store.cancel(a, "test over");
    store.cancel(b, "test over");
  }
}
