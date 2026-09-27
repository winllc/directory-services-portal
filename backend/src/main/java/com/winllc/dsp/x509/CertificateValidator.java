package com.winllc.dsp.x509;

import com.winllc.dsp.config.PortalProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Checks that a client certificate is usable for sign-in: within its validity period, allowed
 * for TLS client authentication and, when trusted CAs are configured, issued by one of them
 * (optionally with revocation checking).
 */
@Component
public class CertificateValidator {
  static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
  static final String EKU_ANY = "2.5.29.37.0";

  private final PortalProperties props;
  private volatile CachedAnchors anchors;

  private record CachedAnchors(String path, long modified, Set<TrustAnchor> anchors) {}

  public CertificateValidator(PortalProperties props) {
    this.props = props;
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

    Set<TrustAnchor> trusted = trustAnchors();
    if (trusted != null) validatePath(chain, trusted);
  }

  private void validatePath(X509Certificate[] chain, Set<TrustAnchor> trusted) {
    // Path = leaf plus any intermediates, excluding certificates that are themselves anchors.
    Set<X509Certificate> anchorCerts = trusted.stream().map(TrustAnchor::getTrustedCert).collect(Collectors.toSet());
    List<X509Certificate> path = new ArrayList<>();
    for (X509Certificate c : chain) if (!anchorCerts.contains(c) || path.isEmpty()) path.add(c);
    try {
      CertPath certPath = CertificateFactory.getInstance("X.509").generateCertPath(path);
      PKIXParameters params = new PKIXParameters(trusted);
      params.setRevocationEnabled(props.getX509().isCheckRevocation());
      if (props.getX509().isCheckRevocation()) {
        Security.setProperty("ocsp.enable", "true");
        System.setProperty("com.sun.security.enableCRLDP", "true");
      }
      CertPathValidator.getInstance("PKIX").validate(certPath, params);
    } catch (CertPathValidatorException e) {
      String reason = e.getReason() == CertPathValidatorException.BasicReason.REVOKED
          ? "The client certificate has been revoked"
          : "The client certificate was not issued by a trusted certificate authority";
      throw new CertificateRejectedException(reason, e);
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
