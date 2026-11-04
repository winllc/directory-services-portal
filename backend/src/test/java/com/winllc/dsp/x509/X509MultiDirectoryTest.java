package com.winllc.dsp.x509;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.LdapDirectory;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Which directory a certificate's user is in: every enabled directory that takes certificate
 * sign-in is searched in configured order, and the first that holds a matching account wins (it
 * must hold exactly one); the others that also match are reported. A directory can be left out
 * (x509 false) or given its own filter (x509-user-filter).
 */
class X509MultiDirectoryTest {
  private final List<InMemoryDirectoryServer> servers = new ArrayList<>();

  @AfterEach
  void stop() {
    servers.forEach(s -> s.shutDown(true));
  }

  private Directories.Directory directory(int index, String id, String base, boolean x509, String filter, String... uids) throws Exception {
    InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(base);
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", InetAddress.getLoopbackAddress(), 0, null));
    config.addAdditionalBindCredentials("cn=admin", "secret");
    InMemoryDirectoryServer s = new InMemoryDirectoryServer(config);
    s.add("dn: " + base, "objectClass: top", "objectClass: organization", "o: " + id);
    for (String uid : uids) {
      s.add("dn: uid=" + uid + "," + base, "objectClass: inetOrgPerson", "uid: " + uid, "cn: " + uid, "sn: " + uid, "mail: " + uid + "@" + id + ".example");
    }
    s.startListening();
    servers.add(s);
    DirectoryProperties p = new DirectoryProperties();
    p.setId(id);
    p.setUrls(List.of("ldap://127.0.0.1:" + s.getListenPort()));
    p.setBaseDn(base);
    p.setX509(x509);
    p.setX509UserFilter(filter);
    return new Directories.Directory(index, p,
        new LdapDirectory(new LdapDirectory.Settings(id, p.urlList(), false, "cn=admin", "secret", false, true, 2000, 2)), null);
  }

  private static X509AuthService service(Directories dirs, String mapping) {
    PortalProperties props = new PortalProperties();
    props.getX509().setEnabled(true);
    props.getX509().setMapping(mapping);
    props.getX509().setUserFilter("(&(objectClass=inetOrgPerson)(uid={{cn}}))");
    return new X509AuthService(props, null, null, dirs, null, null, null);
  }

  private static CertificateIdentity cert(String subject, String cn) {
    return new CertificateIdentity(subject, "CN=Test CA", "01", cn, null, null, null, null, null);
  }

  @Test
  void mapsToTheFirstDirectoryInOrderThatHoldsTheUser() throws Exception {
    Directories.Directory corp = directory(0, "corp", "o=corp", true, "", "alice", "bob");
    Directories.Directory lab = directory(1, "lab", "o=lab", true, "", "alice", "zoe");
    X509AuthService x509 = service(new Directories(List.of(corp, lab), null), "filter");
    assertThat(x509.mapToUser(cert("CN=bob", "bob")).directory().id()).isEqualTo("corp");
    X509AuthService.Mapped zoe = x509.mapToUser(cert("CN=zoe", "zoe"));
    assertThat(zoe.directory().id()).isEqualTo("lab");
    assertThat(zoe.dn()).isEqualTo("uid=zoe,o=lab");
    assertThat(zoe.alsoMatched()).isEmpty();
    // In both: the first in configured order, the other reported.
    X509AuthService.Mapped alice = x509.mapToUser(cert("CN=alice", "alice"));
    assertThat(alice.directory().id()).isEqualTo("corp");
    assertThat(alice.dn()).isEqualTo("uid=alice,o=corp");
    assertThat(alice.alsoMatched()).containsExactly("lab");
    assertThatThrownBy(() -> x509.mapToUser(cert("CN=nobody", "nobody"))).hasMessageContaining("No directory account matches");

    // The order is the configuration's.
    Directories.Directory labFirst = new Directories.Directory(0, lab.config(), lab.ldap(), null);
    Directories.Directory corpSecond = new Directories.Directory(1, corp.config(), corp.ldap(), null);
    X509AuthService.Mapped swapped = service(new Directories(List.of(labFirst, corpSecond), null), "filter").mapToUser(cert("CN=alice", "alice"));
    assertThat(swapped.directory().id()).isEqualTo("lab");
    assertThat(swapped.alsoMatched()).containsExactly("corp");
  }

  @Test
  void twoAccountsInTheWinningDirectoryAreStillRefused() throws Exception {
    // carol and carol2 both match the lab's filter; corp holds no carol.
    Directories dirs = new Directories(List.of(directory(0, "corp", "o=corp", true, "", "alice"),
        directory(1, "lab", "o=lab", true, "(&(objectClass=inetOrgPerson)(uid={{cn}}*))", "carol", "carol2")), null);
    assertThatThrownBy(() -> service(dirs, "filter").mapToUser(cert("CN=carol", "carol"))).isInstanceOf(CertificateRejectedException.class)
        .hasMessageContaining("More than one directory account");
  }

  @Test
  void aDirectoryCanBeLeftOutOrMapItsOwnWay() throws Exception {
    Directories dirs = new Directories(List.of(directory(0, "corp", "o=corp", true, "", "alice"),
        directory(1, "lab", "o=lab", false, "", "alice")), null);
    assertThat(service(dirs, "filter").mapToUser(cert("CN=alice", "alice")).directory().id()).isEqualTo("corp");

    Directories byMail = new Directories(List.of(directory(0, "corp", "o=corp", true, "", "alice"),
        directory(1, "lab", "o=lab", true, "(&(objectClass=inetOrgPerson)(mail={{cn}}@lab.example))", "carol")), null);
    X509AuthService x509 = service(byMail, "filter");
    assertThat(x509.mapToUser(cert("CN=carol", "carol")).directory().id()).isEqualTo("lab");
    assertThat(x509.mapToUser(cert("CN=alice", "alice")).directory().id()).isEqualTo("corp");
  }

  @Test
  void subjectDnMappingUsesTheDirectoryWhoseTreeHoldsTheSubject() throws Exception {
    Directories dirs = new Directories(List.of(directory(0, "corp", "o=corp", true, "", "alice"),
        directory(1, "lab", "o=lab", true, "", "alice")), null);
    X509AuthService x509 = service(dirs, "subject-dn");
    assertThat(x509.mapToUser(cert("uid=alice,o=lab", "alice")).directory().id()).isEqualTo("lab");
    assertThat(x509.mapToUser(cert("uid=alice,o=corp", "alice")).directory().id()).isEqualTo("corp");
    assertThatThrownBy(() -> x509.mapToUser(cert("uid=alice,o=elsewhere", "alice"))).hasMessageContaining("not a user in this directory");
  }
}
