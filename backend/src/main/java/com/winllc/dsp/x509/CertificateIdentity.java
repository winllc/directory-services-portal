package com.winllc.dsp.x509;

import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1ObjectIdentifier;
import com.unboundid.asn1.ASN1Sequence;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;

/**
 * Identity values extracted from a client certificate, used as placeholders in the user
 * filter: {{subject}}, {{cn}}, {{uid}}, {{email}}, {{upn}}, {{edipi}}, {{uuid}}, {{serial}}.
 *
 * <ul>
 *   <li>{@code upn}: the SAN otherName UserPrincipalName (CAC/PIV);
 *   <li>{@code edipi}: the DoD EDIPI, the digits of a UPN of the form {@code 1234567890@mil}, else
 *       the trailing ten digits of a CN {@code LAST.FIRST.MI.1234567890};
 *   <li>{@code email}: the rfc822Name SAN, else the subject's emailAddress;
 *   <li>{@code uuid}: the UUID of a SAN URI {@code urn:uuid:...} (the PIV FASC-N UUID), without
 *       the {@code urn:uuid:} prefix.
 * </ul>
 */
public record CertificateIdentity(String subject, String issuer, String serial, String cn, String uid, String email, String upn,
    String edipi, String uuid) {

  static final String OID_EMAIL_ADDRESS = "1.2.840.113549.1.9.1";
  static final String OID_UID = "0.9.2342.19200300.100.1.1";
  /** Microsoft User Principal Name (used by smart cards such as CAC/PIV). */
  static final String OID_UPN = "1.3.6.1.4.1.311.20.2.3";
  /** The placeholder names, as {{name}} or {name}. */
  public static final List<String> NAMES = List.of("subject", "cn", "uid", "email", "upn", "edipi", "uuid", "serial");
  private static final Pattern EDIPI_UPN = Pattern.compile("^(\\d+)@mil$", Pattern.CASE_INSENSITIVE);
  private static final Pattern EDIPI_CN = Pattern.compile("\\.(\\d{10})$");
  private static final Pattern URN_UUID = Pattern.compile("^urn:uuid:([0-9a-fA-F-]{36})$", Pattern.CASE_INSENSITIVE);

  public static CertificateIdentity of(X509Certificate cert) {
    String subject = cert.getSubjectX500Principal().getName(X500Principal.RFC2253);
    String issuer = cert.getIssuerX500Principal().getName(X500Principal.RFC2253);
    Map<String, String> rdns = mostSpecificRdnValues(subject);
    String email = null;
    String upn = null;
    String uuid = null;
    try {
      Collection<List<?>> sans = cert.getSubjectAlternativeNames();
      if (sans != null) {
        for (List<?> san : sans) {
          int type = (Integer) san.get(0);
          if (type == 1 && email == null) email = String.valueOf(san.get(1));
          if (type == 0 && upn == null && san.get(1) instanceof byte[] der) upn = otherName(der, OID_UPN);
          if (type == 6 && uuid == null) {
            Matcher m = URN_UUID.matcher(String.valueOf(san.get(1)).trim());
            if (m.matches()) uuid = m.group(1);
          }
        }
      }
    } catch (CertificateParsingException e) {
      // Malformed SAN extension: fall back to subject attributes.
    }
    if (email == null) email = rdns.get(OID_EMAIL_ADDRESS);
    String cn = rdns.get("cn");
    return new CertificateIdentity(subject, issuer, cert.getSerialNumber().toString(16), cn,
        rdns.get("uid") != null ? rdns.get("uid") : rdns.get(OID_UID), email, upn, edipi(upn, cn), uuid);
  }

  /** The digits of a UPN {@code 1234567890@mil}, else the trailing {@code .1234567890} of the CN. */
  static String edipi(String upn, String cn) {
    if (upn != null) {
      Matcher m = EDIPI_UPN.matcher(upn.trim());
      if (m.matches()) return m.group(1);
    }
    if (cn != null) {
      Matcher m = EDIPI_CN.matcher(cn.trim());
      if (m.find()) return m.group(1);
    }
    return null;
  }

  /** Placeholder values (only those present). */
  public Map<String, String> placeholders() {
    Map<String, String> m = new LinkedHashMap<>();
    put(m, "subject", subject);
    put(m, "cn", cn);
    put(m, "uid", uid);
    put(m, "email", email);
    put(m, "upn", upn);
    put(m, "edipi", edipi);
    put(m, "uuid", uuid);
    put(m, "serial", serial);
    return m;
  }

  private static void put(Map<String, String> m, String k, String v) {
    if (v != null && !v.isBlank()) m.put(k, v);
  }

  /** Attribute values from the subject, keyed by lower-case type; the most specific RDN wins. */
  private static Map<String, String> mostSpecificRdnValues(String rfc2253) {
    Map<String, String> out = new LinkedHashMap<>();
    try {
      List<Rdn> rdns = new LdapName(rfc2253).getRdns(); // least specific first
      for (int i = rdns.size() - 1; i >= 0; i--) {
        Rdn rdn = rdns.get(i);
        String type = rdn.getType().toLowerCase();
        if (type.startsWith("oid.")) type = type.substring(4);
        if (!out.containsKey(type)) {
          String value = decodeValue(rdn.getValue());
          if (value != null) out.put(type, value);
        }
      }
    } catch (InvalidNameException e) {
      // Leave empty; mapping will report the missing value.
    }
    return out;
  }

  /** RFC 2253 renders unknown attribute types as #hex BER, which LdapName returns as byte[]. */
  private static String decodeValue(Object value) {
    if (value instanceof String s) return s;
    if (value instanceof byte[] ber) {
      try {
        return new String(ASN1Element.decode(ber).getValue(), StandardCharsets.UTF_8);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  /** Extract a UTF-8 value of the given type from a DER-encoded otherName general name. */
  static String otherName(byte[] der, String wantedOid) {
    try {
      ASN1Element el = ASN1Element.decode(der);
      // The JDK returns either the OtherName SEQUENCE or the [0]-tagged GeneralName wrapping it.
      ASN1Element[] parts = ASN1Sequence.decodeAsSequence(el).elements();
      if (parts.length < 2) return null;
      String oid = ASN1ObjectIdentifier.decodeAsObjectIdentifier(parts[0]).getOID().toString();
      if (!wantedOid.equals(oid)) return null;
      ASN1Element explicit = ASN1Element.decode(parts[1].getValue()); // [0] EXPLICIT
      return new String(explicit.getValue(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      return null;
    }
  }
}
