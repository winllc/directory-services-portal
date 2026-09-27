package com.winllc.dsp.x509;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes client certificates forwarded by reverse proxies. Accepted formats:
 * <ul>
 *   <li>PEM (with newlines, or with newlines replaced by spaces as Apache/HAProxy do)</li>
 *   <li>URL-encoded PEM (nginx {@code $ssl_client_escaped_cert}, AWS ALB)</li>
 *   <li>base64 DER</li>
 *   <li>Envoy {@code x-forwarded-client-cert} ({@code Cert="..."} element)</li>
 * </ul>
 */
public final class CertificateParser {
  private static final Pattern XFCC_CERT = Pattern.compile("(?:^|[;,])\\s*Cert=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern PEM = Pattern.compile("-----BEGIN[ +]CERTIFICATE-----(.*?)-----END[ +]CERTIFICATE-----", Pattern.DOTALL);

  private CertificateParser() {}

  public static X509Certificate parseHeader(String raw) {
    if (raw == null || raw.isBlank()) throw new CertificateRejectedException("The client certificate header is empty");
    String value = raw.trim();
    Matcher xfcc = XFCC_CERT.matcher(value);
    if (xfcc.find()) value = xfcc.group(1);
    if (value.length() > 2 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
    if (value.contains("%")) value = percentDecode(value);

    // Form-style encoders turn the spaces in the PEM markers into '+', so accept both.
    List<X509Certificate> certs = PEM.matcher(value).find() ? parsePem(value) : List.of(parseDer(value));
    if (certs.isEmpty()) throw new CertificateRejectedException("The client certificate header does not contain a certificate");
    return certs.get(0);
  }

  /** All certificates in a PEM document (e.g. a CA bundle). */
  public static List<X509Certificate> parsePem(String pem) {
    List<X509Certificate> out = new ArrayList<>();
    Matcher m = PEM.matcher(pem);
    while (m.find()) out.add(parseDer(m.group(1)));
    return out;
  }

  private static X509Certificate parseDer(String base64) {
    try {
      byte[] der = Base64.getMimeDecoder().decode(base64.replaceAll("\\s+", ""));
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    } catch (IllegalArgumentException | CertificateException | ClassCastException e) {
      throw new CertificateRejectedException("The client certificate could not be decoded", e);
    }
  }

  /** RFC 3986 percent-decoding. Unlike URLDecoder, '+' is kept (it is a valid base64 character). */
  static String percentDecode(String s) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '%' && i + 2 < s.length()) {
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi >= 0 && lo >= 0) {
          out.write((hi << 4) | lo);
          i += 3;
          continue;
        }
      }
      int cp = s.codePointAt(i);
      byte[] b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
      out.write(b, 0, b.length);
      i += Character.charCount(cp);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

}
