package com.winllc.dsp.license;

/**
 * The ENS Solutions license signing public keys this build trusts (base64 SPKI DER, as published
 * by the ENS licensing portal at /api/licensing/keys). Built in, never read from configuration: a
 * key an operator could change would let anyone sign their own license. Add a new key here, and
 * release, before ENS starts signing with it. Tests trust the published TEST key through their own
 * LicenseManager bean instead (src/test); it never belongs here.
 */
final class EnsKeys {
  static final String[] PUBLIC_KEYS = {
    // Ed25519, key id dabdc531a28916c5.
    "MCowBQYDK2VwAyEA6S4JBbfMhCuyaCT2Nosmr1MQSb5r3aNE7sQstTmQZCc=",
    // ECDSA P-256 (prime256v1), key id e0a2e4ff9c4f5bfc: verifies licenses on Java runtimes in
    // FIPS mode, which have no Ed25519.
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEpfibIMCbQo+BM4Y5j+ofEuAwBGQ7I8tgMjLva9XLHVKaef41gzah8av615b6bqblN0S+eL4B2cqPKAXYFrXPRA==",
  };

  private EnsKeys() {}
}
