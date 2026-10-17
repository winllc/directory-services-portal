package com.winllc.dsp.x509;

import com.winllc.dsp.config.EnsProperties;
import com.winllc.dsp.config.PortalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.security.auth.x500.X500Principal;
import org.springframework.stereotype.Component;

/**
 * Checks that a client certificate is usable for sign-in: within its validity period, allowed
 * for TLS client authentication, issued by an allowed issuer (ENS_X509_ALLOWED_ISSUERS) and, when
 * trusted CAs are configured, chaining to one of them and not revoked ({@link RevocationChecker},
 * ENS_X509_REVOCATION). Trusted CAs are required in header mode and for revocation checking;
 * without them only the servlet source, where Tomcat has already verified the chain, is accepted.
 */
@Component
public class CertificateValidator {
  static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
  static final String EKU_ANY = "2.5.29.37.0";

  private final PortalProperties props;
  private final EnsProperties ens;
  private final RevocationChecker revocation;
  private volatile CachedAnchors anchors;

  private record CachedAnchors(String path, long modified, Set<TrustAnchor> anchors) {}

  public CertificateValidator(PortalProperties props, EnsProperties ens, RevocationChecker revocation) {
    this.props = props;
    this.ens = ens;
    this.revocation = revocation;
  }

  public void validate(X509Certificate[] chain) {
    X509Certificate leaf = chain[0];
    try {
      leaf.checkValidity();
    } catch (CertificateExpiredException e) {
      throw new CertificateRejectedException("The client certificate has expired");
    } catch (CertificateNotYetValidException e) {
      throw new CertificateRejectedException("The client certificate is not valid yet");
    }
    try {
      List<String> eku = leaf.getExtendedKeyUsage();
      if (eku != null && !eku.contains(EKU_CLIENT_AUTH) && !eku.contains(EKU_ANY)) {
        throw new CertificateRejectedException("The client certificate is not issued for client authentication");
      }
    } catch (CertificateParsingException e) {
      throw new CertificateRejectedException("The client certificate has a malformed extended key usage");
    }
    boolean[] keyUsage = leaf.getKeyUsage();
    if (keyUsage != null && !(keyUsage[0] || (keyUsage.length > 4 && keyUsage[4]))) {
      throw new CertificateRejectedException("The client certificate's key usage does not allow authentication");
    }

    List<X500Principal> allowed = ens.getX509().allowedIssuerList();
    if (!allowed.isEmpty() && !allowed.contains(leaf.getIssuerX500Principal())) {
      throw new CertificateRejectedException("The client certificate's issuer is not allowed to issue certificates for this portal",
          CertificateRejectedException.ISSUER_NOT_ALLOWED);
    }

    Set<TrustAnchor> trusted = trustAnchors();
    if (trusted != null) {
      List<X509Certificate> path = path(chain, trusted);
      X509Certificate anchor = validatePath(path, trusted);
      revocation.check(path, path.size() > 1 ? path.get(1) : anchor, trusted);
    } else if (props.getX509().headerSource()) {
      // A forwarded certificate is only as good as the API's own chain check (the startup checks
      // refuse header mode without a CA file; this covers a configuration changed at runtime).
      throw new CertificateRejectedException("Certificate sign-in through a proxy needs X509_TRUSTED_CA_FILE");
    } else if (revocation.mode() != RevocationChecker.Mode.OFF && !revocation.failOpen()) {
      // Revocation is verified against the trusted CAs (the startup checks require them).
      throw CertificateRejectedException.revocationUnknown();
    }
  }

  /** Path = leaf plus any intermediates, excluding certificates that are themselves anchors. */
  private static List<X509Certificate> path(X509Certificate[] chain, Set<TrustAnchor> trusted) {
    Set<X509Certificate> anchorCerts = trusted.stream().map(TrustAnchor::getTrustedCert).collect(Collectors.toSet());
    List<X509Certificate> path = new ArrayList<>();
    for (X509Certificate c : chain) if (!anchorCerts.contains(c) || path.isEmpty()) path.add(c);
    return path;
  }

  /** Validates the chain (revocation is checked separately); returns the trust anchor's certificate. */
  private static X509Certificate validatePath(List<X509Certificate> path, Set<TrustAnchor> trusted) {
    try {
      CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
      PKIXParameters params = new PKIXParameters(trusted);
      params.setRevocationEnabled(false);
      PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult) CertPathValidator.getInstance("PKIX").validate(certPath, params);
      return result.getTrustAnchor().getTrustedCert();
    } catch (CertPathValidatorException e) {
      throw new CertificateRejectedException("The client certificate was not issued by a trusted certificate authority", e);
    } catch (GeneralSecurityException e) {
      throw new CertificateRejectedException("The client certificate chain could not be validated", e);
    }
  }

  /** Trust anchors from the configured PEM file (reloaded when the file changes); null when not configured. */
  private Set<TrustAnchor> trustAnchors() {
    String file = props.getX509().getTrustedCaFile();
    if (file == null || file.isBlank()) return null;
    Path path = Path.of(file);
    try {
      long modified = Files.getLastModifiedTime(path).toMillis();
      CachedAnchors c = anchors;
      if (c == null || !c.path().equals(file) || c.modified() != modified) {
        List<X509Certificate> cas = CertificateParser.parsePem(Files.readString(path));
        if (cas.isEmpty()) throw new IllegalStateException("No certificates found in " + file);
        c = new CachedAnchors(file, modified, cas.stream().map(ca -> new TrustAnchor(ca, null)).collect(Collectors.toUnmodifiableSet()));
        anchors = c;
      }
      return c.anchors();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read trusted CA file " + file + ": " + e.getMessage(), e);
    }
  }

  static String describe(X509Certificate[] chain) {
    return Arrays.stream(chain).map(c -> c.getSubjectX500Principal().getName()).collect(Collectors.joining(" <- "));
  }
}
