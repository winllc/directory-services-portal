package com.winllc.dsp.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.security.auth.x500.X500Principal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings shared by every ENS application (platform baseline v1), bound from the
 * {@code ENS_*} environment variables in application.yml: security headers, the DoD
 * notice-and-consent banner, session controls and X.509 revocation checking. The audit outputs
 * ({@code ens.audit.*}) have their own class, {@code AuditProperties}.
 */
@ConfigurationProperties("ens")
public class EnsProperties {
  private final Csp csp = new Csp();
  private final Cookie cookie = new Cookie();
  private final ConsentBanner consentBanner = new ConsentBanner();
  private final Session session = new Session();
  private final X509 x509 = new X509();

  public Csp getCsp() { return csp; }
  public Cookie getCookie() { return cookie; }
  public ConsentBanner getConsentBanner() { return consentBanner; }
  public Session getSession() { return session; }
  public X509 getX509() { return x509; }

  /** Content-Security-Policy. */
  public static class Csp {
    /** Send Content-Security-Policy-Report-Only instead (an escape hatch, not a setting to keep). */
    private boolean reportOnly;

    public boolean isReportOnly() { return reportOnly; }
    public void setReportOnly(boolean reportOnly) { this.reportOnly = reportOnly; }
  }

  /** The session cookie (and the portal's own cookies). */
  public static class Cookie {
    /** Always mark cookies Secure, for TLS terminated at a proxy (they are on direct TLS anyway). */
    private boolean secure;

    public boolean isSecure() { return secure; }
    public void setSecure(boolean secure) { this.secure = secure; }
  }

  /** The DoD notice-and-consent banner, acknowledged before any sign-in (DISA STIG). */
  public static class ConsentBanner {
    public static final String DEFAULT_TITLE = "U.S. Government (USG) Information System (IS) Notice and Consent";
    public static final String DEFAULT_TEXT = """
        You are accessing a U.S. Government (USG) Information System (IS) that is provided for USG-authorized use only. \
        By using this IS (which includes any device attached to this IS), you consent to the following conditions:

        - The USG routinely intercepts and monitors communications on this IS for purposes including, but not limited \
        to, penetration testing, COMSEC monitoring, network operations and defense, personnel misconduct (PM), law \
        enforcement (LE), and counterintelligence (CI) investigations.

        - At any time, the USG may inspect and seize data stored on this IS.

        - Communications using, or data stored on, this IS are not private, are subject to routine monitoring, \
        interception, and search, and may be disclosed or used for any USG-authorized purpose.

        - This IS includes security measures (e.g., authentication and access controls) to protect USG \
        interests--not for your personal benefit or privacy.

        - Notwithstanding the above, using this IS does not constitute consent to PM, LE or CI investigative \
        searching or monitoring of the content of privileged communications, or work product, related to personal \
        representation or services by attorneys, psychotherapists, or clergy, and their assistants. Such \
        communications and work product are private and confidential. See User Agreement for details.""";

    private boolean enabled;
    private String title = "";
    /** Plain text; blank lines separate paragraphs. Empty: the standard DoD text. */
    private String text = "";
    /** A file holding the text, read instead of {@link #text} when set. */
    private String file = "";

    public String effectiveTitle() {
      return title == null || title.isBlank() ? DEFAULT_TITLE : title.trim();
    }

    /**
     * The banner text: the file's content when {@link #file} is set, else {@link #text}, else the
     * standard DoD text.
     *
     * @throws UncheckedIOException when the file can't be read
     */
    public String effectiveText() {
      if (file != null && !file.isBlank()) {
        try {
          String s = Files.readString(Path.of(file.trim())).strip();
          if (!s.isEmpty()) return s;
        } catch (IOException e) {
          throw new UncheckedIOException("Cannot read ENS_CONSENT_BANNER_FILE " + file + ": " + e.getMessage(), e);
        }
      }
      return text == null || text.isBlank() ? DEFAULT_TEXT : text.strip();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
  }

  /** Session lifetime: idle timeouts, an absolute limit and concurrent sessions per user. */
  public static class Session {
    private Duration idleTimeout = Duration.ofMinutes(30);
    /** Idle timeout for administrators; null: the same as {@link #idleTimeout}. */
    private Duration adminIdleTimeout;
    /** A session ends this long after sign-in, whatever its activity. */
    private Duration absoluteTimeout = Duration.ofHours(12);
    /** Concurrent sessions per user (0: no limit); over it, the oldest is expired. */
    private int maxPerUser;

    public Duration idleTimeoutFor(boolean admin) {
      return admin && adminIdleTimeout != null && !adminIdleTimeout.isZero() ? adminIdleTimeout : idleTimeout;
    }

    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    public Duration getAdminIdleTimeout() { return adminIdleTimeout; }
    public void setAdminIdleTimeout(Duration adminIdleTimeout) { this.adminIdleTimeout = adminIdleTimeout; }
    public Duration getAbsoluteTimeout() { return absoluteTimeout; }
    public void setAbsoluteTimeout(Duration absoluteTimeout) { this.absoluteTimeout = absoluteTimeout; }
    public int getMaxPerUser() { return maxPerUser; }
    public void setMaxPerUser(int maxPerUser) { this.maxPerUser = maxPerUser; }
  }

  /** Revocation checking and issuer restrictions for client certificates (CAC/PIV). */
  public static class X509 {
    public static final List<String> MODES = List.of("off", "ocsp", "crl", "ocsp-then-crl");

    /**
     * off, ocsp, crl or ocsp-then-crl. Empty (unset) means ocsp-then-crl, soft-fail (see {@link #failOpen}). While
     * it is empty or off, the older X509_CHECK_REVOCATION=true means ocsp-then-crl, failing closed.
     */
    private String revocation = "";
    /** Overrides the certificate's AIA OCSP responder (e.g. a local relay on an air-gapped network). */
    private String ocspResponderUrl = "";
    /** Directory of CRL files (DER or PEM); when set, CRL distribution points are not fetched. */
    private String crlDir = "";
    /**
     * closed: refuse when the status can't be determined; open: allow and log a warning. Empty (unset): open while
     * {@link #revocation} is unset too, closed once a mode is chosen.
     */
    private String revocationFail = "";
    /** How long a good or revoked answer is kept, per issuer and serial. */
    private Duration revocationCache = Duration.ofMinutes(10);
    /** Issuer DNs certificates must come from, separated by ";" or new lines (DNs contain commas). Empty: any trusted CA. */
    private String allowedIssuers = "";

    /** What an unset ENS_X509_REVOCATION means: a live check, OCSP and then the CRL. */
    public static final String DEFAULT_MODE = "ocsp-then-crl";

    /** The mode as configured (lower case); blank when not set. */
    public String configuredMode() {
      return revocation == null ? "" : revocation.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether ENS_X509_REVOCATION was left unset, and so means {@link #DEFAULT_MODE}. */
    public boolean isModeDefaulted() {
      return configuredMode().isEmpty();
    }

    /** The fail mode as configured (lower case); blank when not set. */
    public String configuredFail() {
      return revocationFail == null ? "" : revocationFail.trim().toLowerCase(Locale.ROOT);
    }

    public List<X500Principal> allowedIssuerList() {
      if (allowedIssuers == null || allowedIssuers.isBlank()) return List.of();
      return Arrays.stream(allowedIssuers.split("[;\\n]")).map(String::trim).filter(s -> !s.isEmpty()).map(X500Principal::new).toList();
    }

    /** Problems with the configuration (empty when valid). */
    public List<String> problems() {
      List<String> out = new ArrayList<>();
      String mode = configuredMode();
      if (!mode.isEmpty() && !MODES.contains(mode)) out.add("ENS_X509_REVOCATION must be one of " + String.join(", ", MODES) + ": " + revocation);
      String fail = configuredFail();
      if (!fail.isEmpty() && !fail.equals("closed") && !fail.equals("open")) out.add("ENS_X509_REVOCATION_FAIL must be closed or open: " + revocationFail);
      if (ocspResponderUrl != null && !ocspResponderUrl.isBlank() && !ocspResponderUrl.trim().matches("(?i)https?://\\S+")) {
        out.add("ENS_X509_OCSP_RESPONDER_URL must be an http:// or https:// URL: " + ocspResponderUrl);
      }
      if (crlDir != null && !crlDir.isBlank() && !Files.isDirectory(Path.of(crlDir.trim()))) {
        out.add("ENS_X509_CRL_DIR " + crlDir + " is not a directory");
      }
      if (revocationCache == null || revocationCache.isNegative()) out.add("ENS_X509_REVOCATION_CACHE must not be negative");
      try {
        allowedIssuerList();
      } catch (IllegalArgumentException e) {
        out.add("ENS_X509_ALLOWED_ISSUERS has a DN that can't be parsed (separate DNs with \";\"): " + e.getMessage());
      }
      return out;
    }

    /**
     * Whether an undetermined status lets the certificate in. Unset, it is open only while ENS_X509_REVOCATION is
     * unset as well: the default is a live check that refuses what it finds revoked and lets through, with a
     * warning, what no responder or CRL answers for, so a site that cannot reach them still signs people in. A site
     * that chose a mode left this unset to get closed in earlier releases, and still gets closed.
     */
    public boolean failOpen() {
      String fail = configuredFail();
      return fail.isEmpty() ? isModeDefaulted() : fail.equals("open");
    }

    public String getRevocation() { return revocation; }
    public void setRevocation(String revocation) { this.revocation = revocation; }
    public String getOcspResponderUrl() { return ocspResponderUrl; }
    public void setOcspResponderUrl(String ocspResponderUrl) { this.ocspResponderUrl = ocspResponderUrl; }
    public String getCrlDir() { return crlDir; }
    public void setCrlDir(String crlDir) { this.crlDir = crlDir; }
    public String getRevocationFail() { return revocationFail; }
    public void setRevocationFail(String revocationFail) { this.revocationFail = revocationFail; }
    public Duration getRevocationCache() { return revocationCache; }
    public void setRevocationCache(Duration revocationCache) { this.revocationCache = revocationCache; }
    public String getAllowedIssuers() { return allowedIssuers; }
    public void setAllowedIssuers(String allowedIssuers) { this.allowedIssuers = allowedIssuers; }
  }
}
