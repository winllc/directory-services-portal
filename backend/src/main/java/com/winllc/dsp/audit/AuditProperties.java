package com.winllc.dsp.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit outputs and retention, bound from the shared {@code ENS_AUDIT_*} environment variables
 * (application.yml). The store itself (the monthly JSONL files) is configured by
 * {@code portal.audit.dir}.
 */
@ConfigurationProperties("ens.audit")
public class AuditProperties {
  /** One raw JSON line per event on stdout ({@code ens.audit} logger). */
  private boolean stdout = true;
  /** Rolling JSONL file written through the {@code ens.audit} logger; off when empty. */
  private String file = "";
  /** Rolled files to keep (one per day, or more on a day past 100 MB). */
  private int fileKeep = 30;
  /** Days of history to keep; 0 keeps everything. */
  private int retentionDays = 0;
  /** Most stored lines one admin search or export reads (newest first). */
  private int queryMaxScan = 2_000_000;
  private final Splunk splunk = new Splunk();
  private final Syslog syslog = new Syslog();

  public static class Splunk {
    private String url = "";
    private String token = "";
    private String tokenFile = "";
    private String index = "";
    private String sourcetype = "ens:audit";
    private boolean insecure;

    public boolean enabled() {
      return url != null && !url.isBlank();
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenFile() { return tokenFile; }
    public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
    public String getIndex() { return index; }
    public void setIndex(String index) { this.index = index; }
    public String getSourcetype() { return sourcetype; }
    public void setSourcetype(String sourcetype) { this.sourcetype = sourcetype; }
    public boolean isInsecure() { return insecure; }
    public void setInsecure(boolean insecure) { this.insecure = insecure; }
  }

  public static class Syslog {
    private String host = "";
    /** Default 514 (udp, tcp) or 6514 (tls). */
    private Integer port;
    /** udp, tcp or tls. */
    private String protocol = "tcp";
    private String facility = "local0";

    public boolean enabled() {
      return host != null && !host.isBlank();
    }

    public int effectivePort() {
      if (port != null && port > 0) return port;
      return "tls".equalsIgnoreCase(protocol) ? 6514 : 514;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getFacility() { return facility; }
    public void setFacility(String facility) { this.facility = facility; }
  }

  public boolean isStdout() { return stdout; }
  public void setStdout(boolean stdout) { this.stdout = stdout; }
  public String getFile() { return file; }
  public void setFile(String file) { this.file = file; }
  public int getFileKeep() { return fileKeep; }
  public void setFileKeep(int fileKeep) { this.fileKeep = fileKeep; }
  public int getRetentionDays() { return retentionDays; }
  public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
  public int getQueryMaxScan() { return queryMaxScan; }
  public void setQueryMaxScan(int queryMaxScan) { this.queryMaxScan = queryMaxScan; }
  public Splunk getSplunk() { return splunk; }
  public Syslog getSyslog() { return syslog; }
}
