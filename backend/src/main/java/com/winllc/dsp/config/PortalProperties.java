package com.winllc.dsp.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Portal settings (see application.yml; every value can be set through the environment
 * variables documented in .env.example).
 */
@ConfigurationProperties("portal")
public class PortalProperties {

  /** "memory" runs the embedded demo directory; "ldap" connects to {@link Ldap#url}. */
  private String directoryMode = "memory";

  private final Ldap ldap = new Ldap();
  private String baseDn = "dc=example,dc=com";
  private String userSearchBase = "";
  /** {@code {{username}}} is replaced with the escaped login name. */
  private String userFilter = "(&(objectClass=inetOrgPerson)(uid={{username}}))";
  private String usernameAttribute = "uid";
  private String groupSearchBase = "";
  /** {@code {{dn}}} / {@code {{username}}} placeholders. */
  private String groupFilter = "(|(member={{dn}})(uniqueMember={{dn}}))";
  /** Separated by ";" because DNs contain commas. */
  private String adminUsers = "";
  private String adminGroups = "";
  private String dataDir = "./data";
  /** Seed demo forms/definitions when the config store is empty; defaults to true in memory mode. */
  private Boolean seedDemoConfig;

  public static class Ldap {
    private String url = "ldap://localhost:389";
    private String bindDn = "";
    private String bindPassword = "";
    private boolean startTls;
    private boolean tlsRejectUnauthorized = true;
    private int timeoutMs = 10_000;
    private int poolSize = 10;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBindDn() { return bindDn; }
    public void setBindDn(String bindDn) { this.bindDn = bindDn; }
    public String getBindPassword() { return bindPassword; }
    public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }
    public boolean isStartTls() { return startTls; }
    public void setStartTls(boolean startTls) { this.startTls = startTls; }
    public boolean isTlsRejectUnauthorized() { return tlsRejectUnauthorized; }
    public void setTlsRejectUnauthorized(boolean v) { this.tlsRejectUnauthorized = v; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
  }

  public boolean isMemoryMode() {
    return "memory".equalsIgnoreCase(directoryMode);
  }

  public String effectiveUserSearchBase() {
    return userSearchBase == null || userSearchBase.isBlank() ? baseDn : userSearchBase;
  }

  public String effectiveGroupSearchBase() {
    return groupSearchBase == null || groupSearchBase.isBlank() ? baseDn : groupSearchBase;
  }

  public List<String> adminUserList() {
    return split(adminUsers);
  }

  public List<String> adminGroupList() {
    return split(adminGroups);
  }

  public boolean shouldSeedDemoConfig() {
    return seedDemoConfig != null ? seedDemoConfig : isMemoryMode();
  }

  private static List<String> split(String value) {
    if (value == null) return List.of();
    return Arrays.stream(value.split("[;\\n]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  public String getDirectoryMode() { return directoryMode; }
  public void setDirectoryMode(String directoryMode) { this.directoryMode = directoryMode; }
  public Ldap getLdap() { return ldap; }
  public String getBaseDn() { return baseDn; }
  public void setBaseDn(String baseDn) { this.baseDn = baseDn; }
  public String getUserSearchBase() { return userSearchBase; }
  public void setUserSearchBase(String userSearchBase) { this.userSearchBase = userSearchBase; }
  public String getUserFilter() { return userFilter; }
  public void setUserFilter(String userFilter) { this.userFilter = userFilter; }
  public String getUsernameAttribute() { return usernameAttribute; }
  public void setUsernameAttribute(String usernameAttribute) { this.usernameAttribute = usernameAttribute; }
  public String getGroupSearchBase() { return groupSearchBase; }
  public void setGroupSearchBase(String groupSearchBase) { this.groupSearchBase = groupSearchBase; }
  public String getGroupFilter() { return groupFilter; }
  public void setGroupFilter(String groupFilter) { this.groupFilter = groupFilter; }
  public String getAdminUsers() { return adminUsers; }
  public void setAdminUsers(String adminUsers) { this.adminUsers = adminUsers; }
  public String getAdminGroups() { return adminGroups; }
  public void setAdminGroups(String adminGroups) { this.adminGroups = adminGroups; }
  public String getDataDir() { return dataDir; }
  public void setDataDir(String dataDir) { this.dataDir = dataDir; }
  public Boolean getSeedDemoConfig() { return seedDemoConfig; }
  public void setSeedDemoConfig(Boolean seedDemoConfig) { this.seedDemoConfig = seedDemoConfig; }
}
