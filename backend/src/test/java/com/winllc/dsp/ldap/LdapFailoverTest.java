package com.winllc.dsp.ldap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchScope;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One directory, several servers (UnboundID in-memory servers on loopback; a closed port stands
 * for a server that is down): the first that answers is used, so the first URL being down doesn't
 * take the directory down.
 */
class LdapFailoverTest {
  static final String BASE = "dc=example,dc=com";
  static InMemoryDirectoryServer first;
  static InMemoryDirectoryServer second;

  @BeforeAll
  static void servers() throws Exception {
    first = server("first");
    second = server("second");
  }

  @AfterAll
  static void stop() {
    first.shutDown(true);
    second.shutDown(true);
  }

  private static InMemoryDirectoryServer server(String name) throws Exception {
    InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
    config.addAdditionalBindCredentials("cn=admin", "secret");
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("ldap", InetAddress.getLoopbackAddress(), 0, null));
    InMemoryDirectoryServer s = new InMemoryDirectoryServer(config);
    s.add("dn: " + BASE, "objectClass: top", "objectClass: domain", "dc: example", "description: " + name);
    s.add("dn: uid=alice," + BASE, "objectClass: inetOrgPerson", "uid: alice", "cn: Alice", "sn: A", "userPassword: password");
    s.startListening();
    return s;
  }

  private static String url(InMemoryDirectoryServer s) {
    return "ldap://127.0.0.1:" + s.getListenPort();
  }

  /** A loopback port nothing listens on. */
  private static String down() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return "ldap://127.0.0.1:" + socket.getLocalPort();
    }
  }

  private static LdapDirectory directory(boolean roundRobin, String... urls) {
    return new LdapDirectory(new LdapDirectory.Settings("corp", List.of(urls), roundRobin, "cn=admin", "secret", false, true, 2000, 2));
  }

  private static String description(LdapDirectory d) {
    return d.get(BASE, List.of("description")).first("description");
  }

  @Test
  void usesTheSecondServerWhenTheFirstIsDown() throws Exception {
    try (LdapDirectory d = directory(false, down(), url(first))) {
      d.ping();
      assertThat(description(d)).isEqualTo("first");
      assertThat(d.currentServer()).endsWith(":" + first.getListenPort());
      assertThat(d.search(BASE, SearchScope.SUB, Filter.createEqualityFilter("uid", "alice"), 5, List.of("cn")).entries()).hasSize(1);
      // Password sign-in fails over too.
      assertThat(d.authenticate("uid=alice," + BASE, "password")).isTrue();
      assertThat(d.authenticate("uid=alice," + BASE, "wrong")).isFalse();
    }
  }

  @Test
  void prefersTheFirstServerThatAnswers() throws Exception {
    try (LdapDirectory d = directory(false, url(first), url(second))) {
      assertThat(description(d)).isEqualTo("first");
      assertThat(d.currentServer()).endsWith(":" + first.getListenPort());
      assertThat(d.urls()).containsExactly(url(first), url(second));
    }
    try (LdapDirectory d = directory(false, url(second), url(first))) {
      assertThat(description(d)).isEqualTo("second");
    }
  }

  @Test
  void unavailableOnlyWhenEveryServerIsDown() throws Exception {
    try (LdapDirectory d = directory(false, down(), down())) {
      assertThatThrownBy(d::ping).isInstanceOf(DirectoryException.class)
          .satisfies(e -> assertThat(((DirectoryException) e).code()).isEqualTo(DirectoryException.Code.UNAVAILABLE));
    }
  }

  @Test
  void roundRobinUsesEveryServerThatAnswers() throws Exception {
    try (LdapDirectory d = directory(true, down(), url(second))) {
      d.ping();
      assertThat(description(d)).isEqualTo("second");
    }
  }

  @Test
  void oneServerAsBefore() {
    try (LdapDirectory d = new LdapDirectory(new LdapDirectory.Settings(url(first), "cn=admin", "secret", false, true, 2000, 2))) {
      assertThat(d.id()).isEqualTo("default");
      assertThat(d.url()).isEqualTo(url(first));
      assertThat(description(d)).isEqualTo("first");
    }
  }
}
