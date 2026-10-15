package com.winllc.dsp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Signs license files with the test-only private key in src/test/resources/license, in the same
 * canonical form as the vendor's LicenseTool (sorted key=value lines joined by \n, Ed25519).
 */
final class TestLicenses {
  private TestLicenses() {}

  /** A license text for the portal with the given features, expiry (YYYY-MM-DD or "never") and overrides. */
  static String sign(String features, String expires, Map<String, String> overrides) {
    Map<String, String> fields = new TreeMap<>();
    fields.put("licenseId", "test-variant");
    fields.put("product", "directory-services-portal");
    fields.put("licensee", "Test Suite");
    fields.put("issued", "2026-01-01");
    fields.put("expires", expires);
    fields.put("features", features);
    fields.putAll(overrides);
    StringBuilder canonical = new StringBuilder();
    fields.forEach((k, v) -> canonical.append(k).append('=').append(v).append('\n'));
    try {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(privateKey());
      signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return canonical + "signature=" + Base64.getEncoder().encodeToString(signer.sign()) + "\n";
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String sign(String features, String expires) {
    return sign(features, expires, Map.of());
  }

  private static PrivateKey privateKey() throws GeneralSecurityException {
    try (InputStream in = TestLicenses.class.getClassLoader().getResourceAsStream("license/ens-license-test-private.pem")) {
      String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
      byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
