package com.winllc.dsp.x509;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The bounds on OCSP and CRL fetches: schemes, addresses, redirects and size. */
class RevocationFetcherTest {
  private HttpServer server;
  private String base;
  private final RevocationFetcher fetcher = new RevocationFetcher();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/small", e -> send(e, 200, new byte[100]));
    server.createContext("/big", e -> send(e, 200, new byte[5000]));
    server.createContext("/to-small", e -> redirect(e, base + "/small"));
    server.createContext("/to-file", e -> redirect(e, "file:///etc/passwd"));
    server.createContext("/to-metadata", e -> redirect(e, "http://169.254.169.254/latest/meta-data/"));
    server.createContext("/loop", e -> redirect(e, base + "/loop"));
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void send(com.sun.net.httpserver.HttpExchange e, int status, byte[] body) throws IOException {
    e.sendResponseHeaders(status, body.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(body);
    }
  }

  private static void redirect(com.sun.net.httpserver.HttpExchange e, String location) throws IOException {
    e.getResponseHeaders().set("Location", location);
    e.sendResponseHeaders(302, -1);
    e.close();
  }

  @Test
  void fetchesWithinTheLimitAndFollowsHttpRedirects() throws IOException {
    assertThat(fetcher.fetch(URI.create(base + "/small"), null, null, 1000)).hasSize(100);
    assertThat(fetcher.fetch(URI.create(base + "/to-small"), null, null, 1000)).hasSize(100);
  }

  @Test
  void refusesOversizedResponses() {
    assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/big"), null, null, 1000)).hasMessageContaining("more than the 1000");
  }

  @Test
  void refusesOtherSchemesAndRedirectLoops() {
    assertThatThrownBy(() -> fetcher.fetch(URI.create("ldap://ldap.example/cn=CRL"), null, null, 1000)).hasMessageContaining("only http and https");
    assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/to-file"), null, null, 1000)).hasMessageContaining("only http and https");
    assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/loop"), null, null, 1000)).hasMessageContaining("Too many redirects");
  }

  @Test
  void neverConnectsToLinkLocalOrMetadataAddresses() throws Exception {
    assertThatThrownBy(() -> fetcher.fetch(URI.create("http://169.254.169.254/latest/meta-data/"), null, null, 1000))
        .hasMessageContaining("link-local, multicast or metadata");
    assertThatThrownBy(() -> fetcher.fetch(URI.create(base + "/to-metadata"), null, null, 1000))
        .hasMessageContaining("link-local, multicast or metadata");
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("100.100.100.200"))).isTrue();
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("fd00:ec2::254"))).isTrue();
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("fe80::1"))).isTrue();
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("0.0.0.0"))).isTrue();
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("224.0.0.1"))).isTrue();
    // A local relay or an on-premises responder is fine.
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("127.0.0.1"))).isFalse();
    assertThat(RevocationFetcher.blocked(InetAddress.getByName("10.20.30.40"))).isFalse();
  }
}
