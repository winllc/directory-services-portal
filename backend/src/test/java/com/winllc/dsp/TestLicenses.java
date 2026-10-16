package com.winllc.dsp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Signs {@code ens-license/1} files with the published ENS TEST key (Ed25519 private key = PKCS#8
 * of the seed 0x07 x 32), which only the test configuration trusts ({@link TestLicensing}). The
 * production build trusts only the production key, so nothing signed here works outside tests.
 *
 * <pre>
 * String lic = TestLicenses.license().features("metrics", "directory-editor").days(30).sign();
 * </pre>
 */
final class TestLicenses {
  /** The published test key's public half (portal-kit fixtures/test-public-key.txt). */
  static final String TEST_PUBLIC_KEY = "MCowBQYDK2VwAyEA6kpsY+KcUgq+9VB7Ey7F+ZVHdq6+vnuSQh7qaRRG0iw=";
  static final String PRODUCT = "directory-portal";
  static final List<String> ALL_FEATURES = List.of("directory-editor", "form-builder", "self-service-profile", "white-pages",
      "smart-card-sign-in", "metrics", "data-analysis", "schema-transfer");

  private static final PrivateKey KEY;
  private static final String KEY_ID;

  static {
    try {
      byte[] der = HexFormat.of().parseHex("302e020100300506032b657004220420" + "07".repeat(32));
      KEY = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
      PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(
          new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(TEST_PUBLIC_KEY)));
      KEY_ID = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pub.getEncoded())).substring(0, 16);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private TestLicenses() {}

  static Builder license() {
    return new Builder();
  }

  /** A subscription with every feature, valid for a year: what the test suite normally runs with. */
  static String full() {
    return license().sign();
  }

  static final class Builder {
    String id = "lic_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    int revision = 1;
    String product = PRODUCT;
    String edition = "subscription";
    List<String> features = new ArrayList<>(ALL_FEATURES);
    Instant notBefore = Instant.now().minus(Duration.ofDays(1));
    Instant expiresAt = Instant.now().plus(Duration.ofDays(365));
    int graceDays = 30;
    Instant maintenanceUntil;
    String installationId;
    boolean watermark;
    long quantity = 2500;

    Builder id(String id) { this.id = id; return this; }
    Builder revision(int revision) { this.revision = revision; return this; }
    Builder product(String product) { this.product = product; return this; }
    Builder features(String... ids) { this.features = new ArrayList<>(Arrays.asList(ids)); return this; }
    Builder without(String... ids) { this.features.removeAll(Arrays.asList(ids)); return this; }
    Builder installation(String installationId) { this.installationId = installationId; return this; }
    Builder quantity(long quantity) { this.quantity = quantity; return this; }

    /** Expires this many days from now (negative: already expired). */
    Builder days(long days) { this.expiresAt = Instant.now().plus(Duration.ofDays(days)); return this; }

    Builder graceDays(int graceDays) { this.graceDays = graceDays; return this; }

    /** Expired 60 days ago, past its 30-day grace: read-only. */
    Builder expired() { return days(-60).graceDays(30); }

    Builder notYetValid() { this.notBefore = Instant.now().plus(Duration.ofDays(10)); return this; }

    Builder evaluation() {
      this.edition = "evaluation";
      this.watermark = true;
      this.graceDays = 0;
      return days(30);
    }

    String sign() {
      String payload = "{"
          + q("format") + ":" + q("ens-license/1") + ","
          + q("id") + ":" + q(id) + ","
          + q("revision") + ":" + revision + ","
          + q("issuer") + ":" + q("ENS Solutions, LLC") + ","
          + q("product") + ":" + q(product) + ","
          + q("edition") + ":" + q(edition) + ","
          + q("licensee") + ":{" + q("name") + ":" + q("Test Suite") + "," + q("organization") + ":" + q("ENS QA") + ","
          + q("email") + ":" + q("qa@example.gov") + "},"
          + q("limit") + ":{" + q("quantity") + ":" + quantity + "," + q("unit") + ":" + q("directory users") + "},"
          + q("features") + ":[" + features.stream().map(f -> q(f.contains(".") ? f : product + "." + f)).collect(Collectors.joining(",")) + "],"
          + q("support") + ":" + q("standard") + ","
          + q("issuedAt") + ":" + q(notBefore.toString()) + ","
          + q("notBefore") + ":" + q(notBefore.toString()) + ","
          + q("expiresAt") + ":" + (expiresAt == null ? "null" : q(expiresAt.toString())) + ","
          + q("graceDays") + ":" + graceDays + ","
          + q("maintenanceUntil") + ":" + (maintenanceUntil == null ? "null" : q(maintenanceUntil.toString())) + ","
          + q("installationId") + ":" + (installationId == null ? "null" : q(installationId)) + ","
          + q("watermark") + ":" + watermark
          + "}";
      String body = KEY_ID + "." + b64(payload.getBytes(StandardCharsets.UTF_8));
      try {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(KEY);
        s.update(body.getBytes(StandardCharsets.US_ASCII));
        String token = body + "." + b64(s.sign());
        return "# ENS Solutions license file (test key)\n# Product: " + product + "\n-----BEGIN ENS LICENSE-----\n"
            + token.replaceAll("(.{64})", "$1\n").strip() + "\n-----END ENS LICENSE-----\n";
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private static String q(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
