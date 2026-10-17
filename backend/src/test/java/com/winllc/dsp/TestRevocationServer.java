package com.winllc.dsp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.cert.X509CRL;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RespID;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * An OCSP responder ({@code POST /ocsp}, answers signed by the CA) and a CRL distribution point
 * ({@code GET /crl}) on 127.0.0.1, for the revocation tests (BouncyCastle, test scope only).
 */
final class TestRevocationServer implements AutoCloseable {
  final Set<BigInteger> revoked = ConcurrentHashMap.newKeySet();
  final AtomicInteger ocspRequests = new AtomicInteger();
  final AtomicInteger crlRequests = new AtomicInteger();
  volatile X509CRL crl;
  private final TestCertificates.Ca ca;
  private final HttpServer server;

  TestRevocationServer(TestCertificates.Ca ca) throws IOException {
    this.ca = ca;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ocsp", this::ocsp);
    server.createContext("/crl", this::crl);
    server.start();
  }

  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private void ocsp(HttpExchange exchange) throws IOException {
    ocspRequests.incrementAndGet();
    try (exchange) {
      OCSPReq request = new OCSPReq(exchange.getRequestBody().readAllBytes());
      Instant now = Instant.now();
      BasicOCSPRespBuilder builder = new BasicOCSPRespBuilder(new RespID(X500Name.getInstance(ca.cert().getSubjectX500Principal().getEncoded())));
      for (Req r : request.getRequestList()) {
        CertificateID id = r.getCertID();
        CertificateStatus status = revoked.contains(id.getSerialNumber())
            ? new RevokedStatus(Date.from(now.minus(Duration.ofMinutes(1))), CRLReason.keyCompromise)
            : CertificateStatus.GOOD;
        builder.addResponse(id, status, Date.from(now.minus(Duration.ofMinutes(1))), Date.from(now.plus(Duration.ofHours(1))), null);
      }
      byte[] body = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL,
          builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(ca.keys().getPrivate()), null, Date.from(now))).getEncoded();
      send(exchange, "application/ocsp-response", body);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  private void crl(HttpExchange exchange) throws IOException {
    crlRequests.incrementAndGet();
    try (exchange) {
      if (crl == null) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      send(exchange, "application/pkix-crl", crl.getEncoded());
    } catch (java.security.cert.CRLException e) {
      throw new IOException(e);
    }
  }

  private static void send(HttpExchange exchange, String type, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
