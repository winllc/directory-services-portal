package com.winllc.dsp.secrets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key that secrets the portal stores itself (webhook signing secrets) are encrypted with
 * ({@code DSP_SECRETS_KEY}, or preferably {@code DSP_SECRETS_KEY_FILE}): 32 random bytes, base64,
 * e.g. {@code openssl rand -base64 32}. Unset, they are stored unencrypted as before (with a
 * warning at startup).
 */
@ConfigurationProperties("portal.secrets")
public class SecretsProperties {
  private String key = "";
  /** A file holding the key, read instead of {@link #key} when set (preferred). */
  private String keyFile = "";

  /** The configured key text (base64), or null when none is configured. */
  public String resolvedKey() {
    if (keyFile != null && !keyFile.isBlank()) {
      try {
        String k = Files.readString(Path.of(keyFile.trim())).trim();
        return k.isEmpty() ? null : k;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read DSP_SECRETS_KEY_FILE " + keyFile + ": " + e.getMessage(), e);
      }
    }
    return key == null || key.isBlank() ? null : key.trim();
  }

  public String getKey() { return key; }
  public void setKey(String key) { this.key = key; }
  public String getKeyFile() { return keyFile; }
  public void setKeyFile(String keyFile) { this.keyFile = keyFile; }
}
