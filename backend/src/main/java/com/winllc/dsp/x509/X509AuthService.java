package com.winllc.dsp.x509;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
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
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

  private final PortalProperties props;
  private final ClientCertificateExtractor extractor;
  private final CertificateValidator validator;
  private final LdapDirectory directory;
  private final AuthService auth;

  public X509AuthService(PortalProperties props, ClientCertificateExtractor extractor, CertificateValidator validator,
      LdapDirectory directory, AuthService auth) {
    this.props = props;
    this.extractor = extractor;
    this.validator = validator;
    this.directory = directory;
    this.auth = auth;
  }

  public boolean enabled() {
    return props.getX509().isEnabled();
  }

  public PortalProperties.X509 properties() {
    return props.getX509();
  }

  /** True when the request carries a client certificate (without validating it). */
  public boolean hasCertificate(HttpServletRequest request) {
    try {
      return enabled() && extractor.extract(request).isPresent();
    } catch (CertificateRejectedException e) {
      return false;
    }
  }

  public SessionUser authenticate(HttpServletRequest request) {
    if (!enabled()) throw new CertificateRejectedException("Certificate sign-in is not enabled");
    X509Certificate[] chain = extractor.extract(request)
        .orElseThrow(() -> new CertificateRejectedException("No client certificate was presented"));
    CertificateIdentity identity = CertificateIdentity.of(chain[0]);
    try {
      validator.validate(chain);
      String dn = mapToUser(identity);
      if (props.getX509().isRequireCertificateMatch()) requireMatch(dn, chain[0]);
      String username = identity.cn() != null ? identity.cn() : identity.subject();
      SessionUser user = auth.buildUser(dn, username, "x509", identity.subject());
      log.info("Certificate sign-in: {} (serial {}) -> {}", identity.subject(), identity.serial(), dn);
      return user;
    } catch (CertificateRejectedException e) {
      log.warn("Certificate sign-in rejected for {} (issuer {}): {}", identity.subject(), identity.issuer(), e.getMessage());
      throw e;
    }
  }

  String mapToUser(CertificateIdentity identity) {
    PortalProperties.X509 cfg = props.getX509();
    String base = props.effectiveUserSearchBase();
    if ("subject-dn".equalsIgnoreCase(cfg.getMapping())) {
      String subject = identity.subject();
      if (!Dns.isValid(subject) || !Dns.isUnder(subject, base, true)) {
        throw new CertificateRejectedException("The certificate subject is not a user in this directory");
      }
      // The entry must look like a user (same shape password sign-in accepts), so certificates
      // naming service accounts or containers are refused.
      Filter userShape = AuthService.template(props.getUserFilter().replace("{{username}}", "*"), Map.of());
      List<DirectoryEntry> found;
      try {
        found = directory.search(subject, SearchScope.BASE, userShape, 1, List.of("1.1")).entries();
      } catch (DirectoryException e) {
        if (e.code() != DirectoryException.Code.NO_SUCH_OBJECT) throw e;
        found = List.of();
      }
      if (found.isEmpty()) throw new CertificateRejectedException("No directory account matches this certificate");
      return found.get(0).dn();
    }

    Map<String, String> values = identity.placeholders();
    Matcher m = PLACEHOLDER.matcher(cfg.getUserFilter());
    while (m.find()) {
      if (!values.containsKey(m.group(1))) {
        throw new CertificateRejectedException("The certificate has no " + m.group(1) + " value to identify the user");
      }
    }
    Filter filter = AuthService.template(cfg.getUserFilter(), values);
    List<DirectoryEntry> found = directory.search(base, SearchScope.SUB, filter, 2, List.of("1.1")).entries();
    if (found.isEmpty()) throw new CertificateRejectedException("No directory account matches this certificate");
    if (found.size() > 1) throw new CertificateRejectedException("More than one directory account matches this certificate");
    return found.get(0).dn();
  }

  private void requireMatch(String dn, X509Certificate cert) {
    String presented;
    try {
      presented = Base64.getEncoder().encodeToString(cert.getEncoded());
    } catch (CertificateEncodingException e) {
      throw new CertificateRejectedException("The client certificate could not be encoded", e);
    }
    DirectoryEntry entry = directory.get(dn, List.of("userCertificate", "userCertificate;binary"));
    boolean published = entry != null
        && (entry.values("userCertificate").contains(presented) || entry.values("userCertificate;binary").contains(presented));
    if (!published) throw new CertificateRejectedException("This certificate is not registered on your directory account");
  }
}
