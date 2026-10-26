package com.winllc.dsp.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Envelope encryption of secrets the portal stores itself (webhook signing secrets), with the
 * key from {@code DSP_SECRETS_KEY(_FILE)}. Each value gets its own random AES-256 data key, which
 * encrypts it (AES-GCM, bound to the record it belongs to); the data key is stored with it,
 * wrapped by the configured key (AES-GCM). Stored form:
 *
 * <pre>dspenc:v1:&lt;key id&gt;:&lt;wrapped data key&gt;:&lt;encrypted value&gt;</pre>
 *
 * (base64url; each part is a 12-byte nonce followed by the ciphertext and tag). The key id is the
 * first 8 hex digits of a SHA-256 of the key: it says which key a value needs without revealing
 * it, so a wrong key is reported as such. A value without the prefix is a secret stored before
 * encryption (or without a key) and is read as it is.
 */
@Component
public class SecretBox {
  public static final String PREFIX = "dspenc:v1:";
  private static final int NONCE = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64D = Base64.getUrlDecoder();

  /** A stored value that can't be decrypted: sealed with another key, without a key, or damaged. */
  public static class UnreadableSecretException extends RuntimeException {
    private final String keyId;

    public UnreadableSecretException(String message, String keyId) {
      super(message);
      this.keyId = keyId;
    }

    /** The id of the key the value was sealed with (null when it can't be read at all). */
    public String keyId() {
      return keyId;
    }
  }

  private final SecretKey key;
  private final String keyId;

  @Autowired
  public SecretBox(SecretsProperties props) {
    this(parse(props.resolvedKey()));
  }

  private SecretBox(byte[] key) {
    this.key = key == null ? null : new SecretKeySpec(key, "AES");
    this.keyId = key == null ? null : keyId(key);
  }

  /** With this key (32 bytes). */
  public static SecretBox of(byte[] key) {
    if (key == null || key.length != 32) throw new IllegalArgumentException("The secrets key must be 32 bytes");
    return new SecretBox(key.clone());
  }

  /** Without a key: values are stored as given. */
  public static SecretBox none() {
    return new SecretBox((byte[]) null);
  }

  /**
   * The key from its configured text: base64 (standard or URL-safe) of 32 bytes.
   *
   * @throws IllegalStateException when it is not
   */
  static byte[] parse(String text) {
    if (text == null || text.isBlank()) return null;
    byte[] bytes;
    try {
      String t = text.trim();
      bytes = t.indexOf('-') >= 0 || t.indexOf('_') >= 0 ? Base64.getUrlDecoder().decode(t) : Base64.getDecoder().decode(t);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("DSP_SECRETS_KEY must be 32 random bytes in base64 (openssl rand -base64 32); it is not base64");
    }
    if (bytes.length != 32) {
      throw new IllegalStateException("DSP_SECRETS_KEY must be 32 random bytes in base64 (openssl rand -base64 32); it decodes to "
          + bytes.length + " bytes");
    }
    return bytes;
  }

  static String keyId(byte[] key) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      sha.update("dsp-secrets-key-id:".getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(Arrays.copyOf(sha.digest(key), 4));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** True when a key is configured: new values are sealed. */
  public boolean enabled() {
    return key != null;
  }

  /** The configured key's id (null without a key). */
  public String keyId() {
    return keyId;
  }

  /** Whether a stored value is sealed (else it is a plaintext secret from before). */
  public static boolean sealed(String stored) {
    return stored != null && stored.startsWith(PREFIX);
  }

  /** The key id a sealed value names (null for anything else). */
  public static String keyIdOf(String stored) {
    if (!sealed(stored)) return null;
    String[] parts = stored.substring(PREFIX.length()).split(":", -1);
    return parts.length == 3 ? parts[0] : null;
  }

  /**
   * Seals {@code plaintext} for the record named by {@code context} (it can only be opened with
   * the same context). Without a key, returns it unchanged.
   */
  public String seal(String plaintext, String context) {
    if (plaintext == null || key == null || sealed(plaintext)) return plaintext;
    try {
      byte[] dek = new byte[32];
      RANDOM.nextBytes(dek);
      byte[] wrapped = gcm(Cipher.ENCRYPT_MODE, key, null, dek, wrapAad(keyId));
      byte[] value = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), null, plaintext.getBytes(StandardCharsets.UTF_8), valueAad(context));
      Arrays.fill(dek, (byte) 0);
      return PREFIX + keyId + ":" + B64.encodeToString(wrapped) + ":" + B64.encodeToString(value);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not encrypt a secret: " + e.getMessage(), e);
    }
  }

  /**
   * The secret a stored value holds: a plaintext value as it is, a sealed one decrypted.
   *
   * @throws UnreadableSecretException when a sealed value can't be opened with the configured key
   */
  public String open(String stored, String context) {
    if (!sealed(stored)) return stored;
    String[] parts = stored.substring(PREFIX.length()).split(":", -1);
    if (parts.length != 3) throw new UnreadableSecretException("The stored secret is damaged (not in the dspenc:v1 format)", null);
    String id = parts[0];
    if (key == null) {
      throw new UnreadableSecretException("The stored secret is encrypted (key " + id + ") but DSP_SECRETS_KEY is not set", id);
    }
    if (!id.equals(keyId)) {
      throw new UnreadableSecretException("The stored secret is encrypted with another key (key " + id + "; DSP_SECRETS_KEY is key "
          + keyId + ")", id);
    }
    byte[] dek = null;
    try {
      dek = gcm(Cipher.DECRYPT_MODE, key, B64D.decode(parts[1]), null, wrapAad(id));
      byte[] plain = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), B64D.decode(parts[2]), null, valueAad(context));
      return new String(plain, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new UnreadableSecretException("The stored secret does not decrypt with key " + id + " (damaged, or copied from another record)", id);
    } finally {
      if (dek != null) Arrays.fill(dek, (byte) 0);
    }
  }

  private static byte[] wrapAad(String keyId) {
    return ("dsp-dek:v1:" + keyId).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] valueAad(String context) {
    return ("dsp-secret:v1:" + context).getBytes(StandardCharsets.UTF_8);
  }

  /** Encrypts {@code plain} (returning nonce + ciphertext) or decrypts {@code sealed} (nonce + ciphertext). */
  private static byte[] gcm(int mode, SecretKey k, byte[] sealed, byte[] plain, byte[] aad) throws GeneralSecurityException {
    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
    if (mode == Cipher.ENCRYPT_MODE) {
      byte[] nonce = new byte[NONCE];
      RANDOM.nextBytes(nonce);
      c.init(mode, k, new GCMParameterSpec(TAG_BITS, nonce));
      c.updateAAD(aad);
      byte[] ct = c.doFinal(plain);
      return ByteBuffer.allocate(NONCE + ct.length).put(nonce).put(ct).array();
    }
    if (sealed.length < NONCE + TAG_BITS / 8) throw new GeneralSecurityException("too short");
    c.init(mode, k, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE));
    c.updateAAD(aad);
    return c.doFinal(sealed, NONCE, sealed.length - NONCE);
  }
}
