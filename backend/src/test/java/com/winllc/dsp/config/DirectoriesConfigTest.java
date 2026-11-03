package com.winllc.dsp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.ldap.Directories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The directories as configured: the older single-directory LDAP_* variables (still the default
 * directory, "default"), LDAP_URL with several servers, and the list PORTAL_DIRECTORIES_<n>_*
 * (bound exactly as the environment would be), validated at startup. Nothing here connects: pools
 * are made on first use.
 */
@ExtendWith(OutputCaptureExtension.class)
class DirectoriesConfigTest {
  static final String SECRET = "Sup3r-s3cret-bind-pw";

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PortalProperties.class)
  @Import(DirectoryConfig.class)
  static class Wiring {}

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(Wiring.class)
      .withPropertyValues("DIRECTORY_MODE=ldap");

  /** The variables as the process environment (relaxed binding: PORTAL_DIRECTORIES_0_BINDDN is portal.directories[0].bind-dn). */
  private ApplicationContextRunner env(Map<String, String> vars) {
    return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", new LinkedHashMap<>(vars))));
  }

  @Test
  void theOlderVariablesMakeTheDefaultDirectory() {
    runner.withPropertyValues("LDAP_URL=ldaps://ldap.example.com:636", "LDAP_BASE_DN=dc=corp,dc=example", "LDAP_BIND_DN=cn=svc,dc=corp,dc=example",
        "LDAP_BIND_PASSWORD=" + SECRET, "ADMIN_USERS=root;ops", "LDAP_USER_FILTER=(&(objectClass=person)(sAMAccountName={{username}}))",
        "LDAP_USERNAME_ATTRIBUTE=sAMAccountName", "LDAP_POOL_SIZE=3").run(ctx -> {
          assertThat(ctx).hasNotFailed();
          Directories dirs = ctx.getBean(Directories.class);
          assertThat(dirs.all()).hasSize(1);
          Directories.Directory d = dirs.primary();
          assertThat(d.id()).isEqualTo("default");
          assertThat(d.config().urlList()).containsExactly("ldaps://ldap.example.com:636");
          assertThat(d.config().getBaseDn()).isEqualTo("dc=corp,dc=example");
          assertThat(d.config().getBindDn()).isEqualTo("cn=svc,dc=corp,dc=example");
          assertThat(d.config().adminUserList()).containsExactly("root", "ops");
          assertThat(d.config().getUsernameAttribute()).isEqualTo("sAMAccountName");
          assertThat(d.config().getPoolSize()).isEqualTo(3);
          assertThat(dirs.several()).isFalse();
          // "default" and no id at all both name it.
          assertThat(dirs.find(null)).containsSame(d);
          assertThat(dirs.find("default")).containsSame(d);
        });
  }

  @Test
  void ldapUrlMayListSeveralServersForFailover(CapturedOutput output) {
    runner.withPropertyValues("LDAP_URL=ldap://dc1.example.com:389, ldap://dc2.example.com:389 ldap://dc3.example.com", "LDAP_BIND_PASSWORD=" + SECRET)
        .run(ctx -> {
          assertThat(ctx).hasNotFailed();
          Directories.Directory d = ctx.getBean(Directories.class).primary();
          assertThat(d.config().urlList()).containsExactly("ldap://dc1.example.com:389", "ldap://dc2.example.com:389", "ldap://dc3.example.com");
          assertThat(d.config().roundRobin()).isFalse();
          assertThat(d.ldap().urls()).hasSize(3);
        });
    assertThat(output).contains("failover in this order").doesNotContain(SECRET);
    runner.withPropertyValues("LDAP_URL=ldap://dc1.example.com ldap://dc2.example.com", "LDAP_SERVER_SELECTION=round-robin")
        .run(ctx -> assertThat(ctx.getBean(Directories.class).primary().config().roundRobin()).isTrue());
  }

  @Test
  void bindsTheListFromTheEnvironment(CapturedOutput output) {
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("PORTAL_DIRECTORIES_0_ID", "corp");
    vars.put("PORTAL_DIRECTORIES_0_NAME", "Corporate forest");
    vars.put("PORTAL_DIRECTORIES_0_URLS", "ldaps://dc1.corp.example:636,ldaps://dc2.corp.example:636");
    vars.put("PORTAL_DIRECTORIES_0_BINDDN", "cn=portal,ou=svc,dc=corp,dc=example");
    vars.put("PORTAL_DIRECTORIES_0_BINDPASSWORD", SECRET);
    vars.put("PORTAL_DIRECTORIES_0_BASEDN", "dc=corp,dc=example");
    vars.put("PORTAL_DIRECTORIES_0_ADMINGROUPS", "cn=portal-admins,ou=groups,dc=corp,dc=example");
    vars.put("PORTAL_DIRECTORIES_0_TIMEOUTMS", "4000");
    vars.put("PORTAL_DIRECTORIES_1_ID", "enclave-b");
    vars.put("PORTAL_DIRECTORIES_1_URLS", "ldap://b1.enclave.example ldap://b2.enclave.example");
    vars.put("PORTAL_DIRECTORIES_1_SERVERSELECTION", "round-robin");
    vars.put("PORTAL_DIRECTORIES_1_STARTTLS", "true");
    vars.put("PORTAL_DIRECTORIES_1_BASEDN", "o=enclave-b");
    vars.put("PORTAL_DIRECTORIES_1_USERFILTER", "(&(objectClass=person)(cn={{username}}))");
    vars.put("PORTAL_DIRECTORIES_1_X509", "false");
    vars.put("PORTAL_DIRECTORIES_1_SSOUSERFILTER", "(&(objectClass=person)(mail={{value}}))");
    // The older variables are not used once the list is set.
    env(vars).withPropertyValues("LDAP_URL=ldap://ignored.example.com").run(ctx -> {
      assertThat(ctx).hasNotFailed();
      Directories dirs = ctx.getBean(Directories.class);
      assertThat(dirs.all()).extracting(Directories.Directory::id).containsExactly("corp", "enclave-b");
      Directories.Directory corp = dirs.primary();
      assertThat(corp.name()).isEqualTo("Corporate forest");
      assertThat(corp.config().urlList()).containsExactly("ldaps://dc1.corp.example:636", "ldaps://dc2.corp.example:636");
      assertThat(corp.config().resolvedBindPassword()).isEqualTo(SECRET);
      assertThat(corp.config().adminGroupList()).containsExactly("cn=portal-admins,ou=groups,dc=corp,dc=example");
      assertThat(corp.config().getTimeoutMs()).isEqualTo(4000);
      Directories.Directory b = dirs.find("enclave-b").orElseThrow();
      assertThat(b.name()).isEqualTo("enclave-b");
      assertThat(b.config().roundRobin()).isTrue();
      assertThat(b.config().isStartTls()).isTrue();
      assertThat(b.config().isX509()).isFalse();
      assertThat(b.config().isSso()).isTrue();
      assertThat(b.config().getSsoUserFilter()).isEqualTo("(&(objectClass=person)(mail={{value}}))");
      // Without licensing in this context every directory is enabled; "default" stands for the first.
      assertThat(dirs.several()).isTrue();
      assertThat(dirs.find("default")).containsSame(corp);
      assertThat(dirs.find("nope")).isEmpty();
    });
    assertThat(output).contains("Directory corp \"Corporate forest\"", "Directory enclave-b", "round-robin").doesNotContain(SECRET)
        .doesNotContain("ignored.example.com");
  }

  @Test
  void bindsTheListFromYaml() {
    runner.withPropertyValues("portal.directories[0].id=a", "portal.directories[0].urls[0]=ldap://a1.example",
        "portal.directories[0].urls[1]=ldap://a2.example", "portal.directories[0].base-dn=dc=a", "portal.directories[1].id=b",
        "portal.directories[1].urls=ldap://b.example", "portal.directories[1].base-dn=dc=b").run(ctx -> {
          assertThat(ctx).hasNotFailed();
          Directories dirs = ctx.getBean(Directories.class);
          assertThat(dirs.primary().config().urlList()).containsExactly("ldap://a1.example", "ldap://a2.example");
          assertThat(dirs.find("b").orElseThrow().config().getBaseDn()).isEqualTo("dc=b");
        });
  }

  @Test
  void refusesInvalidDirectories(CapturedOutput output) {
    Map<String, String> vars = new LinkedHashMap<>();
    vars.put("PORTAL_DIRECTORIES_0_ID", "corp");
    vars.put("PORTAL_DIRECTORIES_0_URLS", "ldap://dc1.example");
    vars.put("PORTAL_DIRECTORIES_0_BINDPASSWORD", SECRET);
    vars.put("PORTAL_DIRECTORIES_1_ID", "corp");
    vars.put("PORTAL_DIRECTORIES_1_URLS", "ldap://dc2.example");
    vars.put("PORTAL_DIRECTORIES_2_ID", "No Spaces!");
    vars.put("PORTAL_DIRECTORIES_2_URLS", "http://not-ldap.example");
    vars.put("PORTAL_DIRECTORIES_3_ID", "empty");
    vars.put("PORTAL_DIRECTORIES_4_ID", "mixed");
    vars.put("PORTAL_DIRECTORIES_4_URLS", "ldap://m1.example ldaps://m2.example");
    vars.put("PORTAL_DIRECTORIES_4_SERVERSELECTION", "round-robin");
    env(vars).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause()
          .hasMessageContaining("Refusing to start: invalid directory settings")
          .hasMessageContaining("the id corp is used by more than one directory")
          .hasMessageContaining("id \"No Spaces!\" must be")
          .hasMessageContaining("URL http://not-ldap.example is not a valid ldap:// or ldaps:// URL")
          .hasMessageContaining("portal.directories[3] (empty): at least one server URL is required")
          .hasMessageContaining("round-robin needs every URL to use the same scheme")
          .hasMessageNotContaining(SECRET);
    });
    assertThat(output).doesNotContain(SECRET);
    runner.withPropertyValues("LDAP_URL=not a url").run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("LDAP_*[0] (default): URL not is not a valid");
    });
  }

  @Test
  void readsEachBindPasswordFromItsFile() throws Exception {
    Path file = Files.createTempFile("corp-bind", "");
    Files.writeString(file, SECRET + "\n");
    Map<String, String> vars = new LinkedHashMap<>(Map.of(
        "PORTAL_DIRECTORIES_0_ID", "corp", "PORTAL_DIRECTORIES_0_URLS", "ldap://dc1.example",
        "PORTAL_DIRECTORIES_0_BINDPASSWORDFILE", file.toString(), "PORTAL_DIRECTORIES_0_BINDPASSWORD", "not-this-one"));
    env(vars).run(ctx -> assertThat(ctx.getBean(Directories.class).primary().config().resolvedBindPassword()).isEqualTo(SECRET));
    vars.put("PORTAL_DIRECTORIES_0_BINDPASSWORDFILE", file.resolveSibling("missing-bind-password").toString());
    env(vars).run(ctx -> {
      assertThat(ctx).hasFailed();
      assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("Cannot read the bind password file").hasMessageContaining("of directory corp");
    });
  }

  @Test
  void memoryModeServesOneOrTwoDemoDirectories() {
    new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Wiring.class)
        .withPropertyValues("DIRECTORY_MODE=memory", "DEMO_DIRECTORIES=2").run(ctx -> {
          Directories dirs = ctx.getBean(Directories.class);
          assertThat(dirs.all()).extracting(Directories.Directory::id).containsExactly("default", DirectoryConfig.DEMO_SECOND_ID);
          for (Directories.Directory d : dirs.all()) {
            assertThat(d.embedded()).isNotNull();
            d.ldap().ping();
          }
          // Two servers with the same demo data, each its own directory.
          assertThat(dirs.all().get(0).ldap().url()).isNotEqualTo(dirs.all().get(1).ldap().url());
        });
  }

  @Test
  void problemsListEveryDirectoryWithoutSecrets() {
    DirectoryProperties ok = new DirectoryProperties();
    ok.setId("ok");
    ok.setUrls(List.of("ldaps://a.example:636"));
    ok.setBindPassword(SECRET);
    assertThat(Directories.problems(List.of(ok), "portal.directories")).isEmpty();
    DirectoryProperties bad = new DirectoryProperties();
    bad.setUrls(List.of());
    bad.setUserFilter("(uid=nothing)");
    bad.setBindPassword(SECRET);
    assertThat(Directories.problems(List.of(ok, bad), "portal.directories"))
        .contains("portal.directories[1]: id is required", "portal.directories[1]: at least one server URL is required (urls)",
            "portal.directories[1]: user-filter must contain {{username}}")
        .noneMatch(p -> p.contains(SECRET));
    assertThat(bad.toString()).doesNotContain(SECRET);
  }
}
