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
    "MCowBQYDK2VwAyEA6S4JBbfMhCuyaCT2Nosmr1MQSb5r3aNE7sQstTmQZCc=", // key id dabdc531a28916c5
  };

  private EnsKeys() {}
}
