package com.winllc.dsp.x509;

import com.unboundid.asn1.ASN1BigInteger;
import com.unboundid.asn1.ASN1BitString;
import com.unboundid.asn1.ASN1Element;
import com.unboundid.asn1.ASN1Exception;
import com.unboundid.asn1.ASN1Null;
import com.unboundid.asn1.ASN1ObjectIdentifier;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.asn1.ASN1Sequence;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a certificate's revocation status can be found (the OCSP responders in its Authority
 * Information Access extension, its CRL distribution points), and the OCSP request for it
 * (RFC 6960, a SHA-1 CertID as responders expect; the hash only identifies the issuer).
 */
final class RevocationSources {
  static final String OID_AIA = "1.3.6.1.5.5.7.1.1";
  static final String OID_AD_OCSP = "1.3.6.1.5.5.7.48.1";
  static final String OID_CRL_DP = "2.5.29.31";
  static final String OID_SHA1 = "1.3.14.3.2.26";
  /** GeneralName uniformResourceIdentifier: [6] IMPLICIT IA5String. */
  private static final byte URI_TAG = (byte) 0x86;

  private RevocationSources() {}

  /** http(s) OCSP responder URLs from the AIA extension. */
  static List<URI> ocspResponders(X509Certificate cert) {
    List<URI> out = new ArrayList<>();
    ASN1Element aia = extension(cert, OID_AIA);
    if (aia == null) return out;
    try {
      for (ASN1Element description : ASN1Sequence.decodeAsSequence(aia).elements()) {
        ASN1Element[] parts = ASN1Sequence.decodeAsSequence(description).elements();
        if (parts.length == 2 && OID_AD_OCSP.equals(ASN1ObjectIdentifier.decodeAsObjectIdentifier(parts[0]).getOID().toString())
            && parts[1].getType() == URI_TAG) {
          addHttp(out, parts[1]);
        }
      }
    } catch (ASN1Exception e) {
      // A malformed extension names no responder.
    }
    return out;
  }

  /** http(s) URLs of the CRL distribution points (LDAP and other schemes are left out). */
  static List<URI> crlDistributionPoints(X509Certificate cert) {
    List<URI> out = new ArrayList<>();
    ASN1Element dps = extension(cert, OID_CRL_DP);
    if (dps != null) collectUris(dps, out, 0);
    return out;
  }

  private static void collectUris(ASN1Element element, List<URI> out, int depth) {
    if (element.getType() == URI_TAG) {
      addHttp(out, element);
      return;
    }
    if (depth > 6 || (element.getType() & 0x20) == 0) return;
    try {
      for (ASN1Element child : ASN1Sequence.decodeAsSequence(element).elements()) collectUris(child, out, depth + 1);
    } catch (ASN1Exception e) {
      // Not constructed after all.
    }
  }

  private static void addHttp(List<URI> out, ASN1Element uri) {
    String value = new String(uri.getValue(), StandardCharsets.US_ASCII).trim();
    try {
      URI u = new URI(value);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
      if ((scheme.equals("http") || scheme.equals("https")) && !out.contains(u)) out.add(u);
    } catch (Exception e) {
      // Not a URI.
    }
  }

  private static ASN1Element extension(X509Certificate cert, String oid) {
    byte[] raw = cert.getExtensionValue(oid);
    if (raw == null) return null;
    try {
      return ASN1Element.decode(ASN1OctetString.decodeAsOctetString(raw).getValue());
    } catch (ASN1Exception e) {
      return null;
    }
  }

  /** A DER OCSPRequest for one certificate, without a nonce (responders often use pre-signed answers). */
  static byte[] ocspRequest(X509Certificate cert, X509Certificate issuer) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] nameHash = sha1.digest(issuer.getSubjectX500Principal().getEncoded());
      ASN1Element[] spki = ASN1Sequence.decodeAsSequence(issuer.getPublicKey().getEncoded()).elements();
      byte[] keyHash = sha1.digest(ASN1BitString.decodeAsBitString(spki[1]).getBytes());
      ASN1Sequence certId = new ASN1Sequence(new ASN1Sequence(new ASN1ObjectIdentifier(OID_SHA1), new ASN1Null()),
          new ASN1OctetString(nameHash), new ASN1OctetString(keyHash), new ASN1BigInteger(cert.getSerialNumber()));
      ASN1Sequence tbsRequest = new ASN1Sequence(new ASN1Sequence(new ASN1Sequence(certId)));
      return new ASN1Sequence(tbsRequest).encode();
    } catch (ASN1Exception | GeneralSecurityException e) {
      throw new IllegalStateException("Could not build an OCSP request: " + e.getMessage(), e);
    }
  }
}
