package com.winllc.dsp.license;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.enssolutions.license.LicenseVerifier;
import com.enssolutions.license.LicenseVerifier.Algorithm;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The built-in ENS production keys: the Ed25519 key and the ECDSA P-256 key FIPS-mode runtimes
 * need. No license signed with them can be made here (ENS holds the private keys), so this checks
 * the keys themselves; the fixture tests in com.enssolutions.license cover verification.
 */
class EnsKeysTest {
  static final String ED25519_ID = "dabdc531a28916c5";
  static final String P256_ID = "e0a2e4ff9c4f5bfc";

  @Test
  void trustsBothProductionKeys() {
    assertEquals(
        Map.of(ED25519_ID, Algorithm.ED25519, P256_ID, Algorithm.ECDSA_P256),
        LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS).trustedKeys());
  }

  @Test
  void p256KeyIsPrime256v1WithItsKeyId() throws Exception {
    byte[] spki = Base64.getDecoder().decode(EnsKeys.PUBLIC_KEYS[1]);
    ECPublicKey key =
        (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));

    AlgorithmParameters p256 = AlgorithmParameters.getInstance("EC");
    p256.init(new ECGenParameterSpec("secp256r1"));
    ECParameterSpec expected = p256.getParameterSpec(ECParameterSpec.class);
    assertEquals(expected.getCurve(), key.getParams().getCurve());
    assertEquals(expected.getGenerator(), key.getParams().getGenerator());
    assertEquals(expected.getOrder(), key.getParams().getOrder());

    String id =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(spki)).substring(0, 16);
    assertEquals(P256_ID, id);
  }
}
