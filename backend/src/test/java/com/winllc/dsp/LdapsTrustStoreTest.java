package com.winllc.dsp;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.util.ssl.JVMDefaultTrustManager;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.LdapDirectory;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Directories reached over ldaps:// whose servers' CA is trusted only through the trust store given
 * to the JVM (-Djavax.net.ssl.trustStore, as the Helm chart's ldap.truststore and the package's
 * JAVA_OPTS set it), not through the JDK's own cacerts.
 */
class LdapsTrustStoreTest {
  private static final String BASE = "dc=example,dc=com";
  private static final char[] PASSWORD = "changeit".toCharArray();
  private static final String[] PROPERTIES = {"javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType", "javax.net.ssl.trustStorePassword"};

  @TempDir static Path dir;
  static TestCertificates.Ca ca;
  static X509Certificate serverCert;
  static Path trustStore;
  static final List<InMemoryDirectoryServer> servers = new ArrayList<>();
  private final String[] saved = new String[PROPERTIES.length];

  @BeforeAll
  static void servers() throws Exception {
    ca = TestCertificates.ca("CN=Corporate LDAP CA,O=Example");
    serverCert = TestCertificates.client("CN=localhost").eku(KeyPurposeId.id_kp_serverAuth).issuedBy(ca);

    Path keyStore = dir.resolve("server.p12");
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(null, null);
    keys.setKeyEntry("server", TestCertificates.keys().getPrivate(), PASSWORD, new X509Certificate[] {serverCert, ca.cert()});
    try (OutputStream out = Files.newOutputStream(keyStore)) {
      keys.store(out, PASSWORD);
    }
    trustStore = dir.resolve("truststore.p12");
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("corp-ca", ca.cert());
    try (OutputStream out = Files.newOutputStream(trustStore)) {
      trust.store(out, PASSWORD);
    }

    SSLUtil serverSsl = new SSLUtil(new KeyStoreKeyManager(keyStore.toFile(), PASSWORD, "PKCS12", "server"), null);
    for (String name : List.of("corp", "lab")) {
      InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
      config.addAdditionalBindCredentials("cn=admin", "secret");
      config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig("ldaps", InetAddress.getLoopbackAddress(), 0,
          serverSsl.createSSLServerSocketFactory(), null));
      InMemoryDirectoryServer s = new InMemoryDirectoryServer(config);
      s.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: example", "description: " + name);
      s.startListening();
      servers.add(s);
    }
  }

  @AfterAll
  static void stop() {
    servers.forEach(s -> s.shutDown(true));
  }

  private void useTrustStore() {
    for (int i = 0; i < PROPERTIES.length; i++) saved[i] = System.getProperty(PROPERTIES[i]);
    System.setProperty(PROPERTIES[0], trustStore.toString());
    System.setProperty(PROPERTIES[1], "PKCS12");
    System.setProperty(PROPERTIES[2], new String(PASSWORD));
  }

  @AfterEach
  void restore() {
    for (int i = 0; i < PROPERTIES.length; i++) {
      if (saved[i] == null) System.clearProperty(PROPERTIES[i]);
      else System.setProperty(PROPERTIES[i], saved[i]);
    }
  }

  private static LdapDirectory directory(String id, InMemoryDirectoryServer s) {
    return new LdapDirectory(new LdapDirectory.Settings(id, List.of("ldaps://localhost:" + s.getListenPort()), false, "cn=admin", "secret",
        false, true, 3000, 1));
  }

  @Test
  void everyDirectoryTrustsTheServersThroughTheGivenTrustStore() {
    useTrustStore();
    try (LdapDirectory corp = directory("corp", servers.get(0)); LdapDirectory lab = directory("lab", servers.get(1))) {
      assertThatCode(corp::ping).doesNotThrowAnyException();
      assertThatCode(lab::ping).doesNotThrowAnyException();
    }
  }

  /** Still verified: without the trust store, a server whose CA is not trusted is refused. */
  @Test
  void aServerTheTrustStoreDoesNotVouchForIsRefused() {
    try (LdapDirectory corp = directory("corp", servers.get(0))) {
      assertThatThrownBy(corp::ping).isInstanceOf(DirectoryException.class);
    }
  }

  /** Why: UnboundID's JVMDefaultTrustManager reads the JDK's cacerts, whatever javax.net.ssl.trustStore says. */
  @Test
  void theSdksJvmDefaultTrustManagerIgnoresTheGivenTrustStore() {
    useTrustStore();
    assertThatThrownBy(() -> JVMDefaultTrustManager.getInstance().checkServerTrusted(new X509Certificate[] {serverCert, ca.cert()}, "RSA"))
        .isInstanceOf(CertificateException.class);
  }
}
