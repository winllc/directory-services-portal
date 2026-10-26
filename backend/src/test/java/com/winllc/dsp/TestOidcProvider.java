package com.winllc.dsp;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal OpenID Connect provider for tests: discovery, JWKS and a token endpoint that checks
 * the PKCE verifier and returns an RS256 ID token with the claims a test chose for its code.
 */
final class TestOidcProvider implements AutoCloseable {
  /** What the provider issues for one authorization code. */
  record Grant(String nonce, String codeChallenge, Map<String, Object> claims, String issuer, String audience) {}

  final HttpServer server;
  final String issuer;
  final RSAKey key;
  final Map<String, Grant> codes = new ConcurrentHashMap<>();
  /** Every token request's form parameters (for checking what the portal sent). */
  final List<Map<String, String>> tokenRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

  TestOidcProvider() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    issuer = "http://localhost:" + server.getAddress().getPort();
    server.createContext("/.well-known/openid-configuration", ex -> json(ex, 200, """
        {"issuer":"%1$s","authorization_endpoint":"%1$s/authorize","token_endpoint":"%1$s/token","jwks_uri":"%1$s/jwks",
         "response_types_supported":["code"],"subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],
         "code_challenge_methods_supported":["S256"],"token_endpoint_auth_methods_supported":["client_secret_basic"],
         "end_session_endpoint":"%1$s/logout"}"""
        .formatted(issuer)));
    server.createContext("/jwks", ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toString()));
    server.createContext("/token", this::token);
    server.start();
  }

  private void token(HttpExchange ex) throws IOException {
    Map<String, String> form = new HashMap<>();
    for (String pair : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
    }
    tokenRequests.add(form);
    Grant g = codes.remove(form.get("code"));
    if (g == null) {
      json(ex, 400, "{\"error\":\"invalid_grant\"}");
      return;
    }
    String verifier = form.get("code_verifier");
    if (verifier == null || !s256(verifier).equals(g.codeChallenge())) {
      json(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE verification failed\"}");
      return;
    }
    try {
      JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
          .issuer(g.issuer() != null ? g.issuer() : issuer)
          .audience(g.audience() != null ? g.audience() : "portal")
          .subject("sub-" + g.claims().getOrDefault("preferred_username", "x"))
          .issueTime(new Date())
          .expirationTime(Date.from(Instant.now().plusSeconds(300)))
          .claim("nonce", g.nonce());
      g.claims().forEach(claims::claim);
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
      jwt.sign(new RSASSASigner(key));
      json(ex, 200, "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":300,\"id_token\":\"" + jwt.serialize() + "\"}");
    } catch (Exception e) {
      json(ex, 500, "{\"error\":\"server_error\"}");
    }
  }

  static String s256(String verifier) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void json(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
