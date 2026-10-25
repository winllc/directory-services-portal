package com.winllc.dsp.sso;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in through an identity provider ({@code SSO_*}): OpenID Connect and SAML 2.0, each off
 * until configured. The identity the provider asserts (a claim or attribute) is mapped to exactly
 * one directory entry with a filter; groups and administrator status then come from the directory
 * as for password sign-in. A provider's groups grant administrator rights only when listed in
 * {@code admin-groups}.
 */
@ConfigurationProperties("portal.sso")
public class SsoProperties {
  private final Oidc oidc = new Oidc();
  private final Saml saml = new Saml();
  /**
   * The address users open the portal at (https://portal.example.com), used for the redirect URI,
   * the SAML entity id and assertion consumer service instead of the request's host. Required for
   * SAML unless an entity id is given, so a forged Host or Forwarded header can't change them.
   */
  private String publicUrl = "";
  /**
   * How long a session may last when the provider's groups (not the directory) made the user an
   * administrator: the provider is only asked again at sign-in, so a removal there takes effect
   * when this ends (or the absolute timeout, if shorter).
   */
  private java.time.Duration idpAdminLifetime = java.time.Duration.ofHours(1);
  /**
   * Sign-ons a client address may start per minute (per API replica); more are sent back to the
   * login page. Each start keeps a sign-in request for 10 minutes, so this bounds what one client
   * can make the portal hold.
   */
  private int startLimitPerMinute = 30;
  /** Sign-in requests in flight kept in memory at most (without the shared database); more are refused. */
  private int maxPendingRequests = 10_000;

  public int getStartLimitPerMinute() { return startLimitPerMinute; }
  public void setStartLimitPerMinute(int startLimitPerMinute) { this.startLimitPerMinute = startLimitPerMinute; }
  public int getMaxPendingRequests() { return maxPendingRequests; }
  public void setMaxPendingRequests(int maxPendingRequests) { this.maxPendingRequests = maxPendingRequests; }

  public java.time.Duration getIdpAdminLifetime() { return idpAdminLifetime; }
  public void setIdpAdminLifetime(java.time.Duration idpAdminLifetime) { this.idpAdminLifetime = idpAdminLifetime; }

  public String getPublicUrl() { return publicUrl; }
  public void setPublicUrl(String publicUrl) { this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", ""); }

  /** The base for provider-facing URLs: the public URL, else Spring's {baseUrl} (from the request). */
  public String base() {
    return publicUrl.isBlank() ? "{baseUrl}" : publicUrl;
  }

  public Oidc getOidc() { return oidc; }
  public Saml getSaml() { return saml; }

  /** True when any provider is configured. */
  public boolean enabled() {
    return oidc.enabled() || saml.enabled();
  }

  /** Settings shared by both protocols: mapping the asserted identity to a directory entry. */
  public abstract static class Provider {
    /** {@code {{value}}} is the asserted identity, filter-escaped. */
    private String userFilter = "(&(objectClass=inetOrgPerson)(uid={{value}}))";
    /** Group values from the provider that make a user an administrator (";"-separated). */
    private String adminGroups = "";
    private String label = "";

    public String getUserFilter() { return userFilter; }
    public void setUserFilter(String userFilter) { this.userFilter = userFilter; }
    public String getAdminGroups() { return adminGroups; }
    public void setAdminGroups(String adminGroups) { this.adminGroups = adminGroups; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public List<String> adminGroupList() {
      return split(adminGroups);
    }

    public abstract boolean enabled();
  }

  /** OpenID Connect: authorization code flow with PKCE, against the provider's discovery document. */
  public static class Oidc extends Provider {
    /** The issuer; its {@code /.well-known/openid-configuration} must name the same issuer. */
    private String issuerUri = "";
    private String clientId = "";
    private String clientSecret = "";
    /** A file holding the client secret, read instead of {@link #clientSecret} when set (preferred). */
    private String clientSecretFile = "";
    private String scopes = "openid profile email";
    /** The ID token (or UserInfo) claim that identifies the user. */
    private String usernameClaim = "preferred_username";
    /** The claim listing the user's groups (for {@code admin-groups}). */
    private String groupsClaim = "groups";

    @Override
    public boolean enabled() {
      return !issuerUri.isBlank() && !clientId.isBlank();
    }

    public String resolvedClientSecret() {
      if (clientSecretFile != null && !clientSecretFile.isBlank()) {
        try {
          return Files.readString(Path.of(clientSecretFile.trim())).replaceFirst("\\r?\\n$", "");
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot read SSO_OIDC_CLIENT_SECRET_FILE " + clientSecretFile + ": " + e.getMessage(), e);
        }
      }
      return clientSecret;
    }

    public List<String> scopeList() {
      return Arrays.stream(scopes.split("[\\s,]+")).filter(s -> !s.isBlank()).toList();
    }

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = issuerUri == null ? "" : issuerUri.trim(); }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId == null ? "" : clientId.trim(); }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getClientSecretFile() { return clientSecretFile; }
    public void setClientSecretFile(String clientSecretFile) { this.clientSecretFile = clientSecretFile; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public String getUsernameClaim() { return usernameClaim; }
    public void setUsernameClaim(String usernameClaim) { this.usernameClaim = usernameClaim; }
    public String getGroupsClaim() { return groupsClaim; }
    public void setGroupsClaim(String groupsClaim) { this.groupsClaim = groupsClaim; }
  }

  /** SAML 2.0 Web SSO: the portal is the service provider; the IdP is read from its metadata. */
  public static class Saml extends Provider {
    /** The IdP's metadata: an https:// URL or a file (file:/path or a plain path). */
    private String metadataLocation = "";
    /** This service provider's entity id; blank: the metadata URL, {baseUrl}/api/auth/sso/saml/metadata. */
    private String entityId = "";
    /** The attribute that identifies the user; blank: the NameID. */
    private String usernameAttribute = "";
    /** The attribute listing the user's groups (for {@code admin-groups}). */
    private String groupsAttribute = "";
    /** PEM private key and certificate for signing AuthnRequests (when the IdP requires it). */
    private String signingKeyFile = "";
    private String signingCertificateFile = "";
    /** Clock skew tolerated when checking an assertion's NotBefore and NotOnOrAfter. */
    private Duration clockSkew = Duration.ofMinutes(2);

    @Override
    public boolean enabled() {
      return !metadataLocation.isBlank();
    }

    /** The metadata as a Spring resource location (file paths get the file: prefix). */
    public String resourceLocation() {
      String l = metadataLocation.trim();
      String lower = l.toLowerCase(Locale.ROOT);
      if (lower.startsWith("https:") || lower.startsWith("http:") || lower.startsWith("file:") || lower.startsWith("classpath:")) return l;
      return "file:" + l;
    }

    public boolean signing() {
      return !signingKeyFile.isBlank() && !signingCertificateFile.isBlank();
    }

    public String getMetadataLocation() { return metadataLocation; }
    public void setMetadataLocation(String metadataLocation) { this.metadataLocation = metadataLocation == null ? "" : metadataLocation.trim(); }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId == null ? "" : entityId.trim(); }
    public String getUsernameAttribute() { return usernameAttribute; }
    public void setUsernameAttribute(String usernameAttribute) { this.usernameAttribute = usernameAttribute; }
    public String getGroupsAttribute() { return groupsAttribute; }
    public void setGroupsAttribute(String groupsAttribute) { this.groupsAttribute = groupsAttribute; }
    public String getSigningKeyFile() { return signingKeyFile; }
    public void setSigningKeyFile(String signingKeyFile) { this.signingKeyFile = signingKeyFile; }
    public String getSigningCertificateFile() { return signingCertificateFile; }
    public void setSigningCertificateFile(String signingCertificateFile) { this.signingCertificateFile = signingCertificateFile; }
    public Duration getClockSkew() { return clockSkew; }
    public void setClockSkew(Duration clockSkew) { this.clockSkew = clockSkew; }
  }

  /**
   * What is wrong with the configuration (empty when nothing): checked at startup, which refuses
   * to run with an unsafe provider setting.
   */
  public List<String> problems() {
    List<String> out = new java.util.ArrayList<>();
    if (idpAdminLifetime == null || idpAdminLifetime.isNegative() || idpAdminLifetime.isZero()) {
      out.add("SSO_IDP_ADMIN_SESSION_LIFETIME must be a positive duration");
    }
    if (!publicUrl.isBlank() && !secureUrl(publicUrl)) out.add("PORTAL_PUBLIC_URL must be an https:// URL (http:// only for localhost)");
    if (oidc.enabled()) {
      if (!secureUrl(oidc.getIssuerUri())) out.add("SSO_OIDC_ISSUER_URI must be an https:// URL (http:// only for localhost)");
      if (!oidc.getUserFilter().contains("{{value}}")) out.add("SSO_OIDC_USER_FILTER must contain {{value}}");
      if (oidc.getUsernameClaim().isBlank()) out.add("SSO_OIDC_USERNAME_CLAIM must name a claim");
    } else if (!oidc.getIssuerUri().isBlank() || !oidc.getClientId().isBlank()) {
      out.add("OpenID Connect needs both SSO_OIDC_ISSUER_URI and SSO_OIDC_CLIENT_ID");
    }
    if (saml.enabled()) {
      String l = saml.resourceLocation().toLowerCase(Locale.ROOT);
      if (l.startsWith("http:") && !secureUrl(saml.getMetadataLocation())) {
        out.add("SSO_SAML_METADATA must be an https:// URL or a file (http:// only for localhost)");
      }
      if (!saml.getUserFilter().contains("{{value}}")) out.add("SSO_SAML_USER_FILTER must contain {{value}}");
      if (publicUrl.isBlank() && saml.getEntityId().isBlank()) {
        out.add("SAML needs PORTAL_PUBLIC_URL (or SSO_SAML_ENTITY_ID): the entity id must not come from the request's Host");
      }
      if (saml.getSigningKeyFile().isBlank() != saml.getSigningCertificateFile().isBlank()) {
        out.add("SSO_SAML_SIGNING_KEY_FILE and SSO_SAML_SIGNING_CERT_FILE go together");
      }
      if (saml.getClockSkew() == null || saml.getClockSkew().isNegative() || saml.getClockSkew().compareTo(Duration.ofMinutes(10)) > 0) {
        out.add("SSO_SAML_CLOCK_SKEW must be between 0 and 10 minutes");
      }
    }
    return out;
  }

  static boolean secureUrl(String url) {
    try {
      URI u = URI.create(url.trim());
      if ("https".equalsIgnoreCase(u.getScheme())) return u.getHost() != null;
      return "http".equalsIgnoreCase(u.getScheme()) && ("localhost".equals(u.getHost()) || "127.0.0.1".equals(u.getHost()));
    } catch (RuntimeException e) {
      return false;
    }
  }

  static List<String> split(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }
}
