package com.winllc.dsp.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The optional shared database (PostgreSQL) that lets several API replicas run side by side
 * ({@code DSP_DATABASE_*}). Unset, the portal keeps everything as before: sessions, sign-in
 * throttling and jobs in memory, configuration and the audit store in {@code DATA_DIR}, and must
 * run as a single instance.
 */
@ConfigurationProperties("portal.database")
public class DatabaseProperties {
  /** A {@code jdbc:postgresql://} URL; blank leaves the shared store off. */
  private String url = "";
  private String username = "";
  private String password = "";
  /** A file holding the password, read instead of {@link #password} when set (preferred). */
  private String passwordFile = "";
  private int poolSize = 10;
  /**
   * How often a replica checks whether another one changed the configuration (it always reads the
   * latest version before changing it).
   */
  private Duration configRefresh = Duration.ofSeconds(2);

  public boolean enabled() {
    return url != null && !url.isBlank();
  }

  /**
   * The password: the content of {@link #passwordFile} (without a trailing new line) when that is
   * set, else {@link #password}.
   */
  public String resolvedPassword() {
    if (passwordFile != null && !passwordFile.isBlank()) {
      try {
        return Files.readString(Path.of(passwordFile.trim())).replaceFirst("\\r?\\n$", "");
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read DSP_DATABASE_PASSWORD_FILE " + passwordFile + ": " + e.getMessage(), e);
      }
    }
    return password;
  }

  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getPassword() { return password; }
  public void setPassword(String password) { this.password = password; }
  public String getPasswordFile() { return passwordFile; }
  public void setPasswordFile(String passwordFile) { this.passwordFile = passwordFile; }
  public int getPoolSize() { return poolSize; }
  public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
  public Duration getConfigRefresh() { return configRefresh; }
  public void setConfigRefresh(Duration configRefresh) { this.configRefresh = configRefresh; }
}
