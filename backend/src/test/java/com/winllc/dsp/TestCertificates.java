package com.winllc.dsp;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates CA and client certificates for tests (BouncyCastle, test scope only). */
final class TestCertificates {
  private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());
  private static final KeyPair KEYS = generateKeys();

  record Ca(X509Certificate cert, KeyPair keys) {}

  private TestCertificates() {}

  private static KeyPair generateKeys() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static Ca ca(String subject) {
    try {
      Instant now = Instant.now();
      JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name(subject), BigInteger.valueOf(SERIAL.incrementAndGet()),
          Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(3650))), name(subject), KEYS.getPublic());
      b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
      b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
      return new Ca(sign(b, KEYS), KEYS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Builder-style options for client certificates. */
  static final class Client {
    String subject;
    String email;
    String upn;
    KeyPurposeId eku = KeyPurposeId.id_kp_clientAuth;
    Instant notBefore = Instant.now().minus(Duration.ofDays(1));
    Instant notAfter = Instant.now().plus(Duration.ofDays(365));

    Client(String subject) {
      this.subject = subject;
    }

    Client email(String v) {
      email = v;
      return this;
    }

    Client upn(String v) {
      upn = v;
      return this;
    }

    Client eku(KeyPurposeId v) {
      eku = v;
      return this;
    }

    Client expired() {
      notBefore = Instant.now().minus(Duration.ofDays(30));
      notAfter = Instant.now().minus(Duration.ofDays(1));
      return this;
    }

    X509Certificate issuedBy(Ca ca) {
      try {
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(ca.cert(), BigInteger.valueOf(SERIAL.incrementAndGet()),
            Date.from(notBefore), Date.from(notAfter), name(subject), KEYS.getPublic());
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(eku));
        List<GeneralName> names = new ArrayList<>();
        if (email != null) names.add(new GeneralName(GeneralName.rfc822Name, email));
        if (upn != null) {
          ASN1EncodableVector v = new ASN1EncodableVector();
          v.add(new ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.3"));
          v.add(new DERTaggedObject(true, 0, new DERUTF8String(upn)));
          names.add(new GeneralName(GeneralName.otherName, new DERSequence(v)));
        }
        if (!names.isEmpty()) b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
        return sign(b, ca.keys());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Parse an LDAP-order (RFC 2253) DN the way real CAs encode it (most specific RDN last). */
  static X500Name name(String rfc2253) {
    return X500Name.getInstance(new javax.security.auth.x500.X500Principal(rfc2253).getEncoded());
  }

  static Client client(String subject) {
    return new Client(subject);
  }

  private static X509Certificate sign(JcaX509v3CertificateBuilder b, KeyPair signer) throws Exception {
    return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").build(signer.getPrivate())));
  }

  static String pem(X509Certificate cert) {
    try {
      return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
          + "\n-----END CERTIFICATE-----\n";
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
