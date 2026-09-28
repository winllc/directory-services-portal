package com.winllc.dsp.config;

import com.winllc.dsp.ldap.EmbeddedDirectory;
import com.winllc.dsp.ldap.LdapDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the directory gateway to either the embedded demo server or a real LDAP server. */
@Configuration(proxyBeanMethods = false)
public class DirectoryConfig {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "portal.directory-mode", havingValue = "memory", matchIfMissing = true)
  EmbeddedDirectory embeddedDirectory(PortalProperties props) {
    // The demo organisation lives under a fixed suffix.
    props.setBaseDn(EmbeddedDirectory.BASE_DN);
    if (props.adminUserList().isEmpty()) props.setAdminUsers("admin");
    if (props.adminGroupList().isEmpty()) props.setAdminGroups("cn=directory-admins,ou=groups," + EmbeddedDirectory.BASE_DN);
    if (props.getLoginHint() == null || props.getLoginHint().isBlank()) props.setLoginHint(EmbeddedDirectory.LOGIN_HINT);
    return new EmbeddedDirectory();
  }

  @Bean(destroyMethod = "close")
  LdapDirectory ldapDirectory(PortalProperties props, ObjectProvider<EmbeddedDirectory> embedded) {
    PortalProperties.Ldap l = props.getLdap();
    EmbeddedDirectory demo = embedded.getIfAvailable();
    LdapDirectory.Settings settings = demo != null
        ? new LdapDirectory.Settings(demo.url(), EmbeddedDirectory.SERVICE_DN, EmbeddedDirectory.SERVICE_PASSWORD, false, true, l.getTimeoutMs(), l.getPoolSize())
        : new LdapDirectory.Settings(l.getUrl(), l.getBindDn(), l.getBindPassword(), l.isStartTls(), l.isTlsRejectUnauthorized(), l.getTimeoutMs(), l.getPoolSize());
    return new LdapDirectory(settings);
  }
}
