package com.winllc.dsp.x509;

import com.winllc.dsp.config.PortalProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.security.cert.X509Certificate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/**
 * Obtains the client certificate chain for a request: from the TLS connection (servlet source)
 * or from a header set by a trusted reverse proxy (header source). The header is ignored unless
 * the request comes directly from a configured trusted proxy address.
 */
@Component
public class ClientCertificateExtractor {
  private static final Logger log = LoggerFactory.getLogger(ClientCertificateExtractor.class);
  static final String SERVLET_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

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
      return Optional.empty();
    }
    return Optional.of(new X509Certificate[] {CertificateParser.parseHeader(header)});
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
