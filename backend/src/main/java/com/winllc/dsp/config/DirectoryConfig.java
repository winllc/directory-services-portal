package com.winllc.dsp.config;

import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.EmbeddedDirectory;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.license.Licensing;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the directories: the embedded demo directory (or two) in memory mode, else the configured
 * LDAP directories ({@code portal.directories}, or the one directory of the {@code LDAP_*}
 * settings), each validated at startup.
 */
@Configuration(proxyBeanMethods = false)
public class DirectoryConfig {
  private static final Logger log = LoggerFactory.getLogger(DirectoryConfig.class);
  /** The second embedded demo directory ({@code DEMO_DIRECTORIES=2}). */
  public static final String DEMO_SECOND_ID = "lab";

  @Bean(destroyMethod = "close")
  Directories directories(PortalProperties props, ObjectProvider<MeterRegistry> meters, ObjectProvider<Licensing> licensing) {
    MeterRegistry registry = meters.getIfAvailable();
    List<Directories.Directory> list = new ArrayList<>();
    if (props.isMemoryMode()) {
      // The demo organisation lives under a fixed suffix.
      props.setBaseDn(EmbeddedDirectory.BASE_DN);
      if (props.adminUserList().isEmpty()) props.setAdminUsers("admin");
      if (props.adminGroupList().isEmpty()) props.setAdminGroups("cn=directory-admins,ou=groups," + EmbeddedDirectory.BASE_DN);
      if (props.getLoginHint() == null || props.getLoginHint().isBlank()) props.setLoginHint(EmbeddedDirectory.LOGIN_HINT);
      if (props.directoriesConfigured()) log.warn("DIRECTORY_MODE=memory: portal.directories (PORTAL_DIRECTORIES_*) is not used");
      int count = Math.max(1, Math.min(2, props.getDemoDirectories()));
      for (int i = 0; i < count; i++) {
        EmbeddedDirectory demo = new EmbeddedDirectory();
        DirectoryProperties d = props.legacyDirectory();
        if (i > 0) {
          d.setId(DEMO_SECOND_ID);
          d.setName("Lab directory (demo)");
        } else if (props.getDirectoryName() == null || props.getDirectoryName().isBlank()) {
          d.setName("Demo directory");
        }
        d.setUrls(List.of(demo.url()));
        d.setServerSelection("failover");
        d.setBindDn(EmbeddedDirectory.SERVICE_DN);
        d.setBindPassword(EmbeddedDirectory.SERVICE_PASSWORD);
        d.setBindPasswordFile("");
        d.setStartTls(false);
        d.setTlsRejectUnauthorized(true);
        list.add(new Directories.Directory(i, d, new LdapDirectory(settings(d), registry), demo));
      }
    } else {
      List<DirectoryProperties> configured = props.directoryList();
      String prefix = props.directoriesConfigured() ? "portal.directories" : "LDAP_*";
      List<String> problems = Directories.problems(configured, prefix);
      if (!problems.isEmpty()) {
        throw new IllegalStateException("Refusing to start: invalid directory settings: " + String.join("; ", problems));
      }
      if (props.directoriesConfigured()) log.info("Directories from portal.directories: the LDAP_* connection settings are not used");
      for (int i = 0; i < configured.size(); i++) {
        DirectoryProperties d = configured.get(i);
        list.add(new Directories.Directory(i, d, new LdapDirectory(settings(d), registry), null));
      }
    }
    for (Directories.Directory d : list) {
      log.info("Directory {} \"{}\": {} ({}){}", d.id(), d.name(), String.join(", ", d.config().urlList()),
          d.config().urlList().size() > 1 ? (d.config().roundRobin() ? "round-robin" : "failover in this order") : "one server",
          d.primary() ? ", primary" : "");
      // (The LDAP_* settings' own warning is StartupChecks'.)
      if (props.directoriesConfigured() && !props.isMemoryMode() && !d.config().isTlsRejectUnauthorized()) {
        log.warn("Directory {}: tls-reject-unauthorized is false: the directory server's certificate is NOT verified (trust-all). This is"
            + " not FIPS 140-3 or DISA STIG appropriate; use it only in test setups and give the API a trust store instead.", d.id());
      }
    }
    return new Directories(list, licensing.getIfAvailable());
  }

  static LdapDirectory.Settings settings(DirectoryProperties d) {
    return new LdapDirectory.Settings(d.getId(), d.urlList(), d.roundRobin(), d.getBindDn(), d.resolvedBindPassword(), d.isStartTls(),
        d.isTlsRejectUnauthorized(), d.getTimeoutMs(), d.getPoolSize());
  }

  /** The embedded demo directory (memory mode): the primary directory's server. */
  @Bean(destroyMethod = "")
  EmbeddedDirectory embeddedDirectory(Directories directories) {
    EmbeddedDirectory demo = directories.primary().embedded();
    // A placeholder bean is not possible; outside memory mode nothing asks for it (ObjectProvider).
    return demo;
  }

  /**
   * The primary directory's gateway, for code and tests written before there were several
   * directories. Services reach a directory through {@link Directories} (a definition's, a user's).
   */
  @Bean(destroyMethod = "")
  LdapDirectory ldapDirectory(Directories directories) {
    return directories.primary().ldap();
  }
}
