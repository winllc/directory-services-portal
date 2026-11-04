package com.winllc.dsp.x509;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Signs users in with an X.509 client certificate: extract, validate, then map the certificate
 * to exactly one directory user (by filter template or subject DN), optionally requiring the
 * certificate to be published in the user's {@code userCertificate} attribute.
 */
@Service
public class X509AuthService {
  private static final Logger log = LoggerFactory.getLogger(X509AuthService.class);
  /** {{name}}, or {name} for the certificate placeholders (the form the other ENS applications use). */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\{\\{(\\w+)}}|\\{(" + String.join("|", CertificateIdentity.NAMES) + ")}");

  private final PortalProperties props;
  private final ClientCertificateExtractor extractor;
  private final CertificateValidator validator;
  private final Directories directories;
  private final AuthService auth;
  private final AuditLog audit;
  private final Licensing licensing;

  public X509AuthService(PortalProperties props, ClientCertificateExtractor extractor, CertificateValidator validator,
      Directories directories, AuthService auth, AuditLog audit, Licensing licensing) {
    this.licensing = licensing;
    this.props = props;
    this.extractor = extractor;
    this.validator = validator;
    this.directories = directories;
    this.auth = auth;
    this.audit = audit;
  }

  public boolean enabled() {
    return props.getX509().isEnabled();
  }

  /**
   * Whether the license allows certificate sign-in: when it includes smart-card sign-in, or when no
   * license is in effect at all, so that an administrator who signs in with a certificate can still
   * reach the license screen and install one.
   */
  public boolean licensed() {
    return licensing.unlicensed() || licensing.allows(LicensedFeature.SMART_CARD_SIGN_IN);
  }

  /** Certificate sign-in is configured and licensed (what the login page offers). */
  public boolean available() {
    return enabled() && licensed();
  }

  public PortalProperties.X509 properties() {
    return props.getX509();
  }

  /** True when the request carries a client certificate (without validating it). */
  public boolean hasCertificate(HttpServletRequest request) {
    try {
      return enabled() && extractor.extract(request).isPresent();
    } catch (CertificateRejectedException e) {
      if (CertificateRejectedException.UNTRUSTED_HEADER.equals(e.reasonCode())) auditNotExtracted(request, e);
      return false;
    }
  }

  public SessionUser authenticate(HttpServletRequest request) {
    if (!enabled()) throw new CertificateRejectedException("Certificate sign-in is not enabled");
    if (!licensed()) {
      String why = "Smart-card (certificate) sign-in is not included in this portal's license. Sign in with your user name and password.";
      audit.event("auth.login").anonymous(null).detail("method", "x509").detail("reasonCode", "feature_not_licensed").denied(why);
      throw new CertificateRejectedException(why);
    }
    X509Certificate[] chain;
    try {
      chain = extractor.extract(request).orElseThrow(() -> new CertificateRejectedException("No client certificate was presented"));
    } catch (CertificateRejectedException e) {
      auditNotExtracted(request, e);
      throw e;
    }
    CertificateIdentity identity = CertificateIdentity.of(chain[0]);
    AuditLog.Draft event = audit.event("auth.login").target("certificate", identity.subject(), identity.cn())
        .detail("method", "x509").detail("certificateSerial", identity.serial()).detail("certificateIssuer", identity.issuer());
    try {
      validator.validate(chain);
      Mapped mapped = mapToUser(identity);
      String dn = mapped.dn();
      event.directory(mapped.directory().id());
      if (!mapped.alsoMatched().isEmpty()) event.detail("alsoMatched", String.join(",", mapped.alsoMatched()));
      if (props.getX509().isRequireCertificateMatch()) requireMatch(mapped.directory(), dn, chain[0]);
      String username = identity.cn() != null ? identity.cn() : identity.subject();
      SessionUser user = auth.buildUser(mapped.directory(), dn, username, "x509", identity.subject());
      log.info("Certificate sign-in: {} (serial {}) -> {} in directory {}", identity.subject(), identity.serial(), dn, mapped.directory().id());
      event.by(user).target("user", user.dn(), user.displayName()).detail("certificateSubject", identity.subject()).success();
      return user;
    } catch (CertificateRejectedException e) {
      log.warn("Certificate sign-in rejected for {} (issuer {}): {}", identity.subject(), identity.issuer(), e.getMessage());
      event.anonymous(identity.cn() != null ? identity.cn() : identity.subject()).detail("reasonCode", e.reasonCode()).failed(e.auditReason());
      throw e;
    }
  }

  /**
   * A sign-in that failed before there was a certificate to identify. A forwarded header that did
   * not come from the portal's proxy also records the connecting peer, which (unlike the source
   * address, taken from X-Forwarded-For) the sender cannot choose.
   */
  private void auditNotExtracted(HttpServletRequest request, CertificateRejectedException e) {
    AuditLog.Draft event = audit.event("auth.login").anonymous(null).detail("method", "x509");
    if (e.reasonCode() != null) {
      event.detail("reasonCode", e.reasonCode()).detail("peerAddress", ClientCertificateExtractor.peerAddress(request));
    }
    event.failed(e.getMessage());
  }

  /**
   * The user a certificate maps to: their directory and DN, and the other directories that also
   * hold an account matching it (empty with one directory).
   */
  record Mapped(Directories.Directory directory, String dn, List<String> alsoMatched) {}

  /**
   * The directory user the certificate maps to. Every enabled directory that takes certificate
   * sign-in (its {@code x509} setting, default true) is searched, in configured order, with its own
   * user search base and its {@code x509-user-filter} (or X509_USER_FILTER). The first directory
   * with a match wins, and must hold exactly one matching entry; later directories that also match
   * are reported ({@code alsoMatched}) but don't refuse the sign-in.
   */
  Mapped mapToUser(CertificateIdentity identity) {
    PortalProperties.X509 cfg = props.getX509();
    boolean subjectDn = "subject-dn".equalsIgnoreCase(cfg.getMapping());
    Directories.Directory home = null;
    List<DirectoryEntry> homeEntries = List.of();
    List<String> alsoMatched = new java.util.ArrayList<>();
    for (Directories.Directory dir : directories.enabled()) {
      if (!dir.config().isX509()) continue;
      List<DirectoryEntry> hits = search(dir, identity, cfg, subjectDn);
      if (hits.isEmpty()) continue;
      if (home == null) {
        home = dir;
        homeEntries = hits;
      } else {
        alsoMatched.add(dir.id());
      }
    }
    if (home == null) {
      throw new CertificateRejectedException(subjectDn && directories.enabled().stream().noneMatch(d -> d.config().isX509()
          && Dns.isValid(identity.subject()) && Dns.isUnder(identity.subject(), d.config().effectiveUserSearchBase(), true))
          ? "The certificate subject is not a user in this directory" : "No directory account matches this certificate");
    }
    if (homeEntries.size() > 1) throw new CertificateRejectedException("More than one directory account matches this certificate");
    if (!alsoMatched.isEmpty()) {
      log.warn("A certificate matches accounts in several directories: signed in to {}, the first in configured order; also matched: {}",
          home.id(), String.join(", ", alsoMatched));
    }
    return new Mapped(home, homeEntries.get(0).dn(), List.copyOf(alsoMatched));
  }

  /** The entries of one directory the certificate matches (at most two: more than one is ambiguous). */
  private List<DirectoryEntry> search(Directories.Directory dir, CertificateIdentity identity, PortalProperties.X509 cfg, boolean subjectDn) {
    String base = dir.config().effectiveUserSearchBase();
    if (subjectDn) {
      String subject = identity.subject();
      if (!Dns.isValid(subject) || !Dns.isUnder(subject, base, true)) return List.of();
      // The entry must look like a user (same shape password sign-in accepts), so certificates
      // naming service accounts or containers are refused.
      Filter userShape = AuthService.template(dir.config().getUserFilter().replace("{{username}}", "*"), Map.of());
      try {
        return dir.ldap().search(subject, SearchScope.BASE, userShape, 1, List.of("1.1")).entries();
      } catch (DirectoryException e) {
        if (e.code() != DirectoryException.Code.NO_SUCH_OBJECT) throw e;
        return List.of();
      }
    }
    String template = dir.config().getX509UserFilter() == null || dir.config().getX509UserFilter().isBlank()
        ? cfg.getUserFilter() : dir.config().getX509UserFilter();
    return dir.ldap().search(base, SearchScope.SUB, userFilter(template, identity.placeholders()), 2, List.of("1.1")).entries();
  }

  /** The mapping filter with each placeholder replaced by its filter-escaped value. */
  static Filter userFilter(String template, Map<String, String> values) {
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1) != null ? m.group(1) : m.group(2);
      String value = values.get(name);
      if (value == null) throw new CertificateRejectedException("The certificate has no " + name + " value to identify the user");
      m.appendReplacement(out, Matcher.quoteReplacement(Filter.encodeValue(value)));
    }
    m.appendTail(out);
    try {
      return Filter.create(out.toString());
    } catch (LDAPException e) {
      throw new IllegalStateException("Invalid X509_USER_FILTER: " + template, e);
    }
  }

  private void requireMatch(Directories.Directory dir, String dn, X509Certificate cert) {
    String presented;
    try {
      presented = Base64.getEncoder().encodeToString(cert.getEncoded());
    } catch (CertificateEncodingException e) {
      throw new CertificateRejectedException("The client certificate could not be encoded", e);
    }
    DirectoryEntry entry = dir.ldap().get(dn, List.of("userCertificate", "userCertificate;binary"));
    boolean published = entry != null
        && (entry.values("userCertificate").contains(presented) || entry.values("userCertificate;binary").contains(presented));
    if (!published) throw new CertificateRejectedException("This certificate is not registered on your directory account");
  }
}
