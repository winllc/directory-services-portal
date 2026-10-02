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
  private final X509 x509 = new X509();
  private final Audit audit = new Audit();
  /** Allow username/password sign-in (disable for certificate-only deployments). */
  private boolean passwordLoginEnabled = true;
  /** Optional text shown on the login page (e.g. demo credentials or a help-desk contact). */
  private String loginHint = "";
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

  /** The audit log of changes and sign-ins (see {@code AuditLog}). */
  public static class Audit {
    /** Where the monthly audit files are written; blank means {@code <dataDir>/audit}. */
    private String dir = "";
    /** How many of the newest events the admin API can search. Older ones stay in the files. */
    private int memoryEvents = 10_000;

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public int getMemoryEvents() { return memoryEvents; }
    public void setMemoryEvents(int memoryEvents) { this.memoryEvents = memoryEvents; }
  }

  /** X.509 client-certificate sign-in. */
  public static class X509 {
    private boolean enabled;
    /** "servlet": this server terminates mutual TLS; "header": a trusted reverse proxy forwards the certificate. */
    private String source = "servlet";
    /** Header carrying the client certificate in header mode (PEM, URL-encoded PEM, base64 DER or Envoy XFCC). */
    private String header = "X-SSL-Client-Cert";
    /** Addresses/CIDRs allowed to send the certificate header, separated by ";" or ",". */
    private String trustedProxies = "127.0.0.1/32;::1/128";
    /** Optional PEM file of CA certificates; when set the portal validates the chain itself. */
    private String trustedCaFile = "";
    /** Check revocation (OCSP / CRL distribution points) during chain validation. */
    private boolean checkRevocation;
    /** "filter": search with {@link #userFilter}; "subject-dn": the subject DN is the user's entry DN. */
    private String mapping = "filter";
    /** Placeholders: {{cn}} {{uid}} {{email}} {{upn}} {{subject}} {{serial}} (values are filter-escaped). */
    private String userFilter = "(&(objectClass=inetOrgPerson)(uid={{cn}}))";
    /** Also require the presented certificate to be stored in the user's userCertificate attribute. */
    private boolean requireCertificateMatch;
    /** Sign users in automatically when their browser presents a valid certificate. */
    private boolean autoLogin;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getHeader() { return header; }
    public void setHeader(String header) { this.header = header; }
    public String getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(String trustedProxies) { this.trustedProxies = trustedProxies; }
    public String getTrustedCaFile() { return trustedCaFile; }
    public void setTrustedCaFile(String trustedCaFile) { this.trustedCaFile = trustedCaFile; }
    public boolean isCheckRevocation() { return checkRevocation; }
    public void setCheckRevocation(boolean checkRevocation) { this.checkRevocation = checkRevocation; }
    public String getMapping() { return mapping; }
    public void setMapping(String mapping) { this.mapping = mapping; }
    public String getUserFilter() { return userFilter; }
    public void setUserFilter(String userFilter) { this.userFilter = userFilter; }
    public boolean isRequireCertificateMatch() { return requireCertificateMatch; }
    public void setRequireCertificateMatch(boolean v) { this.requireCertificateMatch = v; }
    public boolean isAutoLogin() { return autoLogin; }
    public void setAutoLogin(boolean autoLogin) { this.autoLogin = autoLogin; }

    public boolean headerSource() {
      return "header".equalsIgnoreCase(source);
    }

    public List<String> trustedProxyList() {
      return trustedProxies == null ? List.of()
          : Arrays.stream(trustedProxies.split("[;,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
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
  public X509 getX509() { return x509; }
  public Audit getAudit() { return audit; }
  public boolean isPasswordLoginEnabled() { return passwordLoginEnabled; }
  public String getLoginHint() { return loginHint; }
  public void setLoginHint(String loginHint) { this.loginHint = loginHint; }
  public void setPasswordLoginEnabled(boolean passwordLoginEnabled) { this.passwordLoginEnabled = passwordLoginEnabled; }
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
