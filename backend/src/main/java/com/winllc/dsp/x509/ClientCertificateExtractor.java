package com.winllc.dsp.x509;

import com.winllc.dsp.config.PortalProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Obtains the client certificate chain for a request: from the TLS connection (servlet source)
 * or from a header set by the portal's reverse proxy (header source). The header is refused
 * unless the request comes directly from a configured trusted proxy address and carries the
 * proxy secret ({@link #PROXY_SECRET_HEADER}), which the portal's web tier sends with every
 * request: an address alone does not prove the sender is the proxy (any pod or local process
 * may share it), and the header carries no proof that the sender holds the certificate's key.
 */
@Component
public class ClientCertificateExtractor {
  private static final Logger log = LoggerFactory.getLogger(ClientCertificateExtractor.class);
  static final String SERVLET_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  /** Header in which the web tier sends the proxy secret (X509_PROXY_SECRET / X509_PROXY_SECRET_FILE). */
  public static final String PROXY_SECRET_HEADER = "X-DSP-Proxy-Secret";
  static final String NOT_FROM_PROXY = "The client certificate was not forwarded by the portal's proxy";

  private final PortalProperties props;

  public ClientCertificateExtractor(PortalProperties props) {
    this.props = props;
  }

  public Optional<X509Certificate[]> extract(HttpServletRequest request) {
    PortalProperties.X509 cfg = props.getX509();
    if (!cfg.headerSource()) {
      Object attr = request.getAttribute(SERVLET_ATTRIBUTE);
      return attr instanceof X509Certificate[] chain && chain.length > 0 ? Optional.of(chain) : Optional.empty();
    }
    String header = request.getHeader(cfg.getHeader());
    if (header == null || header.isBlank()) return Optional.empty();
    String peer = peerAddress(request);
    if (!fromTrustedProxy(peer, cfg)) {
      log.warn("Ignoring {} header from untrusted address {}", cfg.getHeader(), peer);
      throw new CertificateRejectedException(NOT_FROM_PROXY, CertificateRejectedException.UNTRUSTED_HEADER);
    }
    if (!carriesProxySecret(request, cfg)) {
      log.warn("Ignoring {} header from {}: {} is missing or wrong (X509_PROXY_SECRET)", cfg.getHeader(), peer, PROXY_SECRET_HEADER);
      throw new CertificateRejectedException(NOT_FROM_PROXY, CertificateRejectedException.UNTRUSTED_HEADER);
    }
    return Optional.of(new X509Certificate[] {CertificateParser.parseHeader(header)});
  }

  /** Whether the request carries the configured proxy secret (compared in constant time). */
  static boolean carriesProxySecret(HttpServletRequest request, PortalProperties.X509 cfg) {
    String expected;
    try {
      expected = cfg.resolvedProxySecret();
    } catch (UncheckedIOException e) {
      log.warn(e.getMessage());
      return false;
    }
    String presented = request.getHeader(PROXY_SECRET_HEADER);
    if (expected == null || presented == null) return false;
    // Digests of equal length, so the time taken says nothing about the expected value.
    return MessageDigest.isEqual(sha256(expected), sha256(presented));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Address of the directly connected peer. Forwarded-header support
   * ({@code FORWARD_HEADERS_STRATEGY=framework}) wraps the request and reports the original
   * client as the remote address; the proxy trust decision must use the actual connection, so
   * unwrap to the container's request. (Tomcat's "native" strategy rewrites the address in
   * place and therefore cannot be combined with header-based certificates.)
   */
  static String peerAddress(HttpServletRequest request) {
    ServletRequest current = request;
    while (current instanceof ServletRequestWrapper wrapper) current = wrapper.getRequest();
    return current.getRemoteAddr();
  }

  static boolean fromTrustedProxy(String remoteAddr, PortalProperties.X509 cfg) {
    if (remoteAddr == null) return false;
    for (String range : cfg.trustedProxyList()) {
      try {
        if (new IpAddressMatcher(range).matches(remoteAddr)) return true;
      } catch (IllegalArgumentException e) {
        log.warn("Invalid trusted proxy entry '{}'", range);
      }
    }
    return false;
  }
}
