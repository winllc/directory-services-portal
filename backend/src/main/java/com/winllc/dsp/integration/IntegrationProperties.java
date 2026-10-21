package com.winllc.dsp.integration;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The integration API ({@code /api/v1}), its API tokens, outbound webhooks, and entry import and
 * export (see README, Integration API). Every value has a safe default; nothing here is needed for
 * the portal to run as before.
 */
@ConfigurationProperties("portal.integration")
public class IntegrationProperties {
  private final Api api = new Api();
  private final Webhooks webhooks = new Webhooks();
  private final Transfer transfer = new Transfer();

  public Api getApi() { return api; }
  public Webhooks getWebhooks() { return webhooks; }
  public Transfer getTransfer() { return transfer; }

  public static class Api {
    /** Requests per minute per token when the token doesn't set its own (per API replica). */
    private int rateLimitPerMinute = 120;
    /** The longest lifetime an administrator may give a token. */
    private int maxTokenDays = 365;
    /**
     * Unknown, expired or revoked tokens per minute per client address (per replica); further bad
     * tokens from it get 429 instead of 401. Requests with a valid token are never refused by it.
     */
    private int failedTokenLimitPerMinute = 30;

    public int getFailedTokenLimitPerMinute() { return failedTokenLimitPerMinute; }
    public void setFailedTokenLimitPerMinute(int failedTokenLimitPerMinute) { this.failedTokenLimitPerMinute = failedTokenLimitPerMinute; }

    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public int getMaxTokenDays() { return maxTokenDays; }
    public void setMaxTokenDays(int maxTokenDays) { this.maxTokenDays = maxTokenDays; }
  }

  public static class Webhooks {
    /** Off: no webhook is sent (deliveries stay queued) and none can be created. */
    private boolean enabled = true;
    /**
     * Host names (exact, or {@code *.example.com}) whose webhooks may reach private, loopback or
     * link-local addresses, ";" or "," separated. Cloud metadata addresses are refused regardless.
     */
    private String allowedHosts = "";
    /** Attempts per delivery before it is marked failed (with growing pauses between them). */
    private int maxAttempts = 8;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    /** The most one delivery attempt may take in all (connect, handshake, request, answer). */
    private Duration sendTimeout = Duration.ofSeconds(20);
    /** How long finished deliveries stay in the delivery log. */
    private Duration logRetention = Duration.ofDays(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(String allowedHosts) { this.allowedHosts = allowedHosts == null ? "" : allowedHosts; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public Duration getLogRetention() { return logRetention; }
    public Duration getSendTimeout() { return sendTimeout; }
    public void setSendTimeout(Duration sendTimeout) { this.sendTimeout = sendTimeout; }
    public void setLogRetention(Duration logRetention) { this.logRetention = logRetention; }

    public List<String> allowedHostList() {
      return Arrays.stream(allowedHosts.split("[;,]")).map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }
  }

  public static class Transfer {
    /** Most entries one export may hold. */
    private int exportMaxEntries = 10_000;
    /** Largest import file, in bytes. */
    private int importMaxBytes = 5 * 1024 * 1024;
    /** Most rows (CSV) or records (LDIF) one import may hold. */
    private int importMaxRows = 5_000;
    /** Most members added or removed by one bulk membership change. */
    private int bulkMaxMembers = 5_000;

    public int getExportMaxEntries() { return exportMaxEntries; }
    public void setExportMaxEntries(int exportMaxEntries) { this.exportMaxEntries = exportMaxEntries; }
    public int getImportMaxBytes() { return importMaxBytes; }
    public void setImportMaxBytes(int importMaxBytes) { this.importMaxBytes = importMaxBytes; }
    public int getImportMaxRows() { return importMaxRows; }
    public void setImportMaxRows(int importMaxRows) { this.importMaxRows = importMaxRows; }
    public int getBulkMaxMembers() { return bulkMaxMembers; }
    public void setBulkMaxMembers(int bulkMaxMembers) { this.bulkMaxMembers = bulkMaxMembers; }
  }
}
