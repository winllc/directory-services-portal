package com.winllc.dsp.groups;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Governed mode: group requests go to the ENS identity governance console (syncd's console)
 * instead of the groups' owners. Bound from GOVERNANCE_URL, GOVERNANCE_TOKEN (or
 * GOVERNANCE_TOKEN_FILE), GOVERNANCE_PORTAL_URL and GOVERNANCE_SYSTEMS in application.yml. Off
 * while GOVERNANCE_URL or the token is empty.
 *
 * <p>{@code systems} maps a directory id to the governance system whose groups it holds:
 * {@code dir-a=<uuid>;dir-b=<uuid>}, or {@code *=<uuid>} for every directory. A directory with no
 * system stays ungoverned.
 */
@ConfigurationProperties("portal.governance")
public class GovernanceProperties {
  /** The console's address, e.g. https://syncd.example.com. */
  private String url;
  /** An API token of the console with the scope governance:delegate. */
  private String token;
  private String tokenFile;
  /** Where people follow their requests in the console (its /my pages); by default {@code url}. */
  private String portalUrl;
  private String systems;

  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }
  public String getToken() { return token; }
  public void setToken(String token) { this.token = token; }
  public String getTokenFile() { return tokenFile; }
  public void setTokenFile(String tokenFile) { this.tokenFile = tokenFile; }
  public String getPortalUrl() { return portalUrl; }
  public void setPortalUrl(String portalUrl) { this.portalUrl = portalUrl; }
  public String getSystems() { return systems; }
  public void setSystems(String systems) { this.systems = systems; }

  /** The token: GOVERNANCE_TOKEN, else the file's first line; null when neither is set. */
  public String token() {
    if (token != null && !token.isBlank()) return token.trim();
    if (tokenFile == null || tokenFile.isBlank()) return null;
    try {
      String t = Files.readString(Path.of(tokenFile)).trim();
      return t.isEmpty() ? null : t.lines().findFirst().orElse(null);
    } catch (IOException e) {
      throw new UncheckedIOException("GOVERNANCE_TOKEN_FILE can't be read", e);
    }
  }

  public boolean enabled() {
    return url != null && !url.isBlank() && token() != null && !systemMap().isEmpty();
  }

  /** The console's address without a trailing slash. */
  public String base() {
    return url == null ? "" : withoutTrailingSlashes(url.trim());
  }

  /** Where a request is followed in the console. */
  public String requestLink(String requestId) {
    String root = portalUrl != null && !portalUrl.isBlank() ? withoutTrailingSlashes(portalUrl.trim()) : base();
    return root + "/my/requests/" + requestId;
  }

  Map<String, String> systemMap() {
    Map<String, String> out = new LinkedHashMap<>();
    if (systems == null) return out;
    for (String pair : systems.split("[;,]")) {
      int eq = pair.indexOf('=');
      if (eq <= 0) continue;
      String dir = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
      String sys = pair.substring(eq + 1).trim();
      if (!dir.isEmpty() && !sys.isEmpty()) out.put(dir, sys);
    }
    return out;
  }

  /** The governance system of a directory, or null when it is not governed. */
  public String systemFor(String directoryId) {
    if (url == null || url.isBlank() || token() == null) return null;
    Map<String, String> map = systemMap();
    String key = directoryId == null ? "" : directoryId.toLowerCase(Locale.ROOT);
    return map.containsKey(key) ? map.get(key) : map.get("*");
  }

  /** {@code s} without the slashes it ends with (a loop, not a pattern that backtracks on many slashes). */
  static String withoutTrailingSlashes(String s) {
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '/') end--;
    return s.substring(0, end);
  }
}
