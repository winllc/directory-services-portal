package com.winllc.dsp.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
  private final Metrics metrics = new Metrics();
  private final Jobs jobs = new Jobs();
  private final Banner banner = new Banner();
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

  /** Limits for the metrics and analysis modules. */
  public static class Metrics {
    /** Most entries one metric or analysis source reads; larger directories are reported as truncated. */
    private int maxEntries = 100_000;
    /** How long computed metrics are reused (0 disables the cache). */
    private int cacheSeconds = 60;
    /** Most individual findings an analysis run keeps (counts always cover every entry); they are browsed a page at a time. */
    private int maxIssues = 50_000;
    /** How long a request waits for metrics before they carry on in the background. */
    private int waitMillis = 2_000;

    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    public int getCacheSeconds() { return cacheSeconds; }
    public void setCacheSeconds(int cacheSeconds) { this.cacheSeconds = cacheSeconds; }
    public int getMaxIssues() { return maxIssues; }
    public void setMaxIssues(int maxIssues) { this.maxIssues = maxIssues; }
    public int getWaitMillis() { return waitMillis; }
    public void setWaitMillis(int waitMillis) { this.waitMillis = waitMillis; }
  }

  /** Background computations (analyses, expensive metrics). */
  public static class Jobs {
    private int metricsThreads = 4;
    private int analysisThreads = 2;
    /** Computations one user may have queued or running at once. */
    private int maxActivePerUser = 5;
    /** How long finished jobs and their results are kept. */
    private int retainMinutes = 24 * 60;
    private int maxJobs = 500;

    public int getMetricsThreads() { return metricsThreads; }
    public void setMetricsThreads(int metricsThreads) { this.metricsThreads = metricsThreads; }
    public int getAnalysisThreads() { return analysisThreads; }
    public void setAnalysisThreads(int analysisThreads) { this.analysisThreads = analysisThreads; }
    public int getMaxActivePerUser() { return maxActivePerUser; }
    public void setMaxActivePerUser(int maxActivePerUser) { this.maxActivePerUser = maxActivePerUser; }
    public int getRetainMinutes() { return retainMinutes; }
    public void setRetainMinutes(int retainMinutes) { this.retainMinutes = retainMinutes; }
    public int getMaxJobs() { return maxJobs; }
    public void setMaxJobs(int maxJobs) { this.maxJobs = maxJobs; }
  }

  /**
   * Optional fixed banner shown at the top and bottom of every page (e.g. a classification or
   * environment marking). Disabled while {@code text} is empty.
   */
  public static class Banner {
    /** Hex (#rgb, #rrggbb, #rrggbbaa), rgb()/rgba()/hsl()/hsla() or a CSS color name. */
    static final java.util.regex.Pattern COLOR = java.util.regex.Pattern.compile(
        "^(#[0-9a-fA-F]{3,4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}|[a-zA-Z]{3,20}|(rgb|rgba|hsl|hsla)\\([0-9.,%\\s/+-]{1,60}\\))$");
    static final int MAX_TEXT = 200;

    private String text = "";
    private String foreground = "#ffffff";
    private String background = "#007a33";

    public boolean enabled() {
      return text != null && !text.isBlank();
    }

    /** Problems with the configuration (empty when valid). */
    public List<String> problems() {
      List<String> out = new java.util.ArrayList<>();
      if (text != null && text.length() > MAX_TEXT) out.add("BANNER_TEXT must be at most " + MAX_TEXT + " characters");
      if (!isColor(foreground)) out.add("BANNER_FOREGROUND is not a valid color: " + foreground);
      if (!isColor(background)) out.add("BANNER_BACKGROUND is not a valid color: " + background);
      return out;
    }

    public static boolean isColor(String value) {
      return value != null && COLOR.matcher(value.trim()).matches();
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getForeground() { return foreground; }
    public void setForeground(String foreground) { this.foreground = foreground; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
  }

  /** X.509 client-certificate sign-in. */
  public static class X509 {
    /** Shortest proxy secret accepted (the packages generate 64 hex characters). */
    public static final int MIN_PROXY_SECRET_LENGTH = 32;

    private boolean enabled;
    /** "servlet": this server terminates mutual TLS; "header": a trusted reverse proxy forwards the certificate. */
    private String source = "servlet";
    /** Header carrying the client certificate in header mode (PEM, URL-encoded PEM, base64 DER or Envoy XFCC). */
    private String header = "X-SSL-Client-Cert";
    /** Addresses/CIDRs allowed to send the certificate header, separated by ";" or ",". */
    private String trustedProxies = "127.0.0.1/32;::1/128";
    /**
     * Shared secret the portal's web tier sends to the API in {@code X-DSP-Proxy-Secret} with every
     * request; in header mode the certificate header is honoured only with it.
     */
    private String proxySecret = "";
    /** File holding the proxy secret, read instead of {@link #proxySecret} when set (preferred). */
    private String proxySecretFile = "";
    /** PEM file of CA certificates the portal validates the chain against (required in header mode). */
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
    public String getProxySecret() { return proxySecret; }
    public void setProxySecret(String proxySecret) { this.proxySecret = proxySecret; }
    public String getProxySecretFile() { return proxySecretFile; }
    public void setProxySecretFile(String proxySecretFile) { this.proxySecretFile = proxySecretFile; }
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

    /**
     * The proxy secret: the content of {@link #proxySecretFile} when that is set, else
     * {@link #proxySecret}; null when neither is configured. Surrounding whitespace is ignored.
     *
     * @throws UncheckedIOException when the file can't be read
     */
    public String resolvedProxySecret() {
      if (proxySecretFile != null && !proxySecretFile.isBlank()) {
        try {
          String s = Files.readString(Path.of(proxySecretFile.trim())).trim();
          return s.isEmpty() ? null : s;
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot read X509_PROXY_SECRET_FILE " + proxySecretFile + ": " + e.getMessage(), e);
        }
      }
      return proxySecret == null || proxySecret.isBlank() ? null : proxySecret.trim();
    }

    /**
     * Problems with the configuration (empty when valid). In header mode anyone who can reach the
     * API could otherwise sign in as any user: the certificate header must come with the proxy
     * secret, and the API must check the chain itself.
     */
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      if (!enabled || !headerSource()) return out;
      try {
        String secret = resolvedProxySecret();
        if (secret == null) {
          out.add("set X509_PROXY_SECRET_FILE (or X509_PROXY_SECRET) to the secret the portal's web tier sends in"
              + " X-DSP-Proxy-Secret, so that only it can forward client certificates");
        } else if (secret.length() < MIN_PROXY_SECRET_LENGTH) {
          out.add("the proxy secret (X509_PROXY_SECRET_FILE / X509_PROXY_SECRET) must be at least "
              + MIN_PROXY_SECRET_LENGTH + " characters, e.g. from openssl rand -hex 32");
        }
      } catch (UncheckedIOException e) {
        out.add(e.getMessage());
      }
      if (trustedCaFile == null || trustedCaFile.isBlank()) {
        out.add("set X509_TRUSTED_CA_FILE to the CA certificates (PEM) that issue user certificates, so that the API"
            + " validates every forwarded certificate itself");
      } else if (!Files.isReadable(Path.of(trustedCaFile))) {
        out.add("X509_TRUSTED_CA_FILE " + trustedCaFile + " cannot be read");
      }
      return out;
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
  public Metrics getMetrics() { return metrics; }
  public Jobs getJobs() { return jobs; }
  public Banner getBanner() { return banner; }
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
