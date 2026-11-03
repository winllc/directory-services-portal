package com.winllc.dsp.config;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One LDAP directory the portal connects to: an entry of {@code portal.directories}
 * ({@code PORTAL_DIRECTORIES_<n>_<SETTING>}), or the single directory made from the older
 * {@code LDAP_*} settings (id {@value #DEFAULT_ID}). Unset values take the same defaults as the
 * {@code LDAP_*} settings.
 *
 * <p>{@code urls} lists one or more servers holding the same data (a replica set): they are tried in
 * order ({@code server-selection: failover}, the default) or in turn ({@code round-robin}).
 */
public class DirectoryProperties {
  /** The id of the directory made from the older single-directory settings. */
  public static final String DEFAULT_ID = "default";
  /** Lower-case letters, digits, "-" and "_": the id appears in URLs, audit records and webhook payloads. */
  public static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

  private String id;
  private String name;
  private List<String> urls = new ArrayList<>();
  /** "failover" (in order) or "round-robin". */
  private String serverSelection = "failover";
  private String bindDn = "";
  private String bindPassword = "";
  /** File holding the bind password, read instead of {@link #bindPassword} when set (preferred). */
  private String bindPasswordFile = "";
  private boolean startTls;
  private boolean tlsRejectUnauthorized = true;
  private int timeoutMs = 10_000;
  private int poolSize = 10;
  private String baseDn = "dc=example,dc=com";
  private String userSearchBase = "";
  /** {@code {{username}}} is replaced with the escaped login name. */
  private String userFilter = "(&(objectClass=inetOrgPerson)(uid={{username}}))";
  private String usernameAttribute = "uid";
  private String groupSearchBase = "";
  /** {@code {{dn}}} / {@code {{username}}} placeholders. */
  private String groupFilter = "(|(member={{dn}})(uniqueMember={{dn}}))";
  /** Separated by ";" because DNs contain commas. Administrators of the whole portal. */
  private String adminUsers = "";
  private String adminGroups = "";
  /** Certificate sign-in looks for the user in this directory. */
  private boolean x509 = true;
  /** Overrides X509_USER_FILTER for this directory (blank: that filter). */
  private String x509UserFilter = "";
  /** Single sign-on looks for the user in this directory. */
  private boolean sso = true;
  /** Overrides the provider's user filter (SSO_*_USER_FILTER) for this directory (blank: that filter). */
  private String ssoUserFilter = "";

  /** The server URLs, each entry also split at commas and white space. */
  public List<String> urlList() {
    List<String> out = new ArrayList<>();
    if (urls == null) return out;
    for (String u : urls) {
      if (u == null) continue;
      Arrays.stream(u.split("[,\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
    }
    return out;
  }

  public boolean roundRobin() {
    return "round-robin".equalsIgnoreCase(serverSelection == null ? "" : serverSelection.trim());
  }

  public String displayName() {
    return name == null || name.isBlank() ? id : name.trim();
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

  private static List<String> split(String value) {
    if (value == null) return List.of();
    return Arrays.stream(value.split("[;\\n]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  /**
   * The bind password: the content of {@link #bindPasswordFile} (without a trailing new line)
   * when that is set and not empty, else {@link #bindPassword}.
   *
   * @throws UncheckedIOException when the file can't be read (the message names the file, never the password)
   */
  public String resolvedBindPassword() {
    if (bindPasswordFile != null && !bindPasswordFile.isBlank()) {
      try {
        String s = Files.readString(Path.of(bindPasswordFile.trim())).replaceFirst("\\r?\\n$", "");
        if (!s.isEmpty()) return s;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read the bind password file " + bindPasswordFile + " of directory " + id + ": " + e.getMessage(), e);
      }
    }
    return bindPassword;
  }

  /**
   * Problems with this directory's settings (empty when valid). {@code where} names it in messages
   * (e.g. {@code portal.directories[1]}). Never includes a password.
   */
  public List<String> problems(String where) {
    List<String> out = new ArrayList<>();
    if (id == null || id.isBlank()) {
      out.add(where + ": id is required");
    } else if (!ID.matcher(id).matches()) {
      out.add(where + ": id \"" + id + "\" must be 1-32 lower-case letters, digits, \"-\" or \"_\", starting with a letter or digit");
    }
    List<String> list = urlList();
    if (list.isEmpty()) out.add(where + ": at least one server URL is required (urls)");
    for (String u : list) {
      try {
        LDAPURL url = new LDAPURL(u);
        if (!url.hostProvided()) out.add(where + ": URL " + u + " names no host");
      } catch (LDAPException e) {
        out.add(where + ": URL " + u + " is not a valid ldap:// or ldaps:// URL");
      }
    }
    if (serverSelection != null && !serverSelection.isBlank() && !serverSelection.trim().equalsIgnoreCase("failover") && !roundRobin()) {
      out.add(where + ": server-selection must be failover or round-robin");
    }
    if (baseDn == null || baseDn.isBlank()) out.add(where + ": base-dn is required");
    if (timeoutMs <= 0) out.add(where + ": timeout-ms must be positive");
    if (poolSize <= 0) out.add(where + ": pool-size must be positive");
    checkFilter(out, where, "user-filter", userFilter, true);
    checkFilter(out, where, "group-filter", groupFilter, false);
    checkFilter(out, where, "x509-user-filter", x509UserFilter, false);
    checkFilter(out, where, "sso-user-filter", ssoUserFilter, false);
    if (ssoUserFilter != null && !ssoUserFilter.isBlank() && !ssoUserFilter.contains("{{value}}")) {
      out.add(where + ": sso-user-filter must contain {{value}}");
    }
    if (userFilter != null && !userFilter.contains("{{username}}")) out.add(where + ": user-filter must contain {{username}}");
    try {
      resolvedBindPassword();
    } catch (UncheckedIOException e) {
      out.add(e.getMessage());
    }
    return out;
  }

  private static void checkFilter(List<String> out, String where, String name, String template, boolean required) {
    if (template == null || template.isBlank()) {
      if (required) out.add(where + ": " + name + " is required");
      return;
    }
    // Placeholders are replaced with escaped values at use; any value stands in for them here.
    String sample = template.replaceAll("\\{\\{\\w+}}", "x").replaceAll("\\{\\w+}", "x");
    try {
      Filter.create(sample);
    } catch (LDAPException e) {
      out.add(where + ": " + name + " is not a valid LDAP filter");
    }
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id == null ? null : id.trim(); }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public List<String> getUrls() { return urls; }
  public void setUrls(List<String> urls) { this.urls = urls; }
  public String getServerSelection() { return serverSelection; }
  public void setServerSelection(String serverSelection) { this.serverSelection = serverSelection; }
  public String getBindDn() { return bindDn; }
  public void setBindDn(String bindDn) { this.bindDn = bindDn; }
  public String getBindPassword() { return bindPassword; }
  public void setBindPassword(String bindPassword) { this.bindPassword = bindPassword; }
  public String getBindPasswordFile() { return bindPasswordFile; }
  public void setBindPasswordFile(String bindPasswordFile) { this.bindPasswordFile = bindPasswordFile; }
  public boolean isStartTls() { return startTls; }
  public void setStartTls(boolean startTls) { this.startTls = startTls; }
  public boolean isTlsRejectUnauthorized() { return tlsRejectUnauthorized; }
  public void setTlsRejectUnauthorized(boolean v) { this.tlsRejectUnauthorized = v; }
  public int getTimeoutMs() { return timeoutMs; }
  public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
  public int getPoolSize() { return poolSize; }
  public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
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
  public boolean isX509() { return x509; }
  public void setX509(boolean x509) { this.x509 = x509; }
  public String getX509UserFilter() { return x509UserFilter; }
  public void setX509UserFilter(String x509UserFilter) { this.x509UserFilter = x509UserFilter; }
  public boolean isSso() { return sso; }
  public void setSso(boolean sso) { this.sso = sso; }
  public String getSsoUserFilter() { return ssoUserFilter; }
  public void setSsoUserFilter(String ssoUserFilter) { this.ssoUserFilter = ssoUserFilter; }

  @Override
  public String toString() {
    // Never the password.
    return "Directory[" + id + ", " + urlList() + "]";
  }
}
