package com.winllc.dsp.audit;

import com.winllc.dsp.SharedDatabaseSupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.cluster.DatabaseProperties;
import com.winllc.dsp.cluster.SharedDocuments;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.store.ConfigStore;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upgrading a single instance to the shared database: on the first start its configuration file
 * and audit files are imported, and the audit chain carries on from the same head.
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedImportTest {
  static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path data;

  @Test
  void importsTheConfigurationAndTheAuditChainOnce() throws Exception {
    try (HikariDataSource ds = new HikariDataSource()) {
      ds.setJdbcUrl(SharedDatabaseSupport.newDatabase());
      ds.setUsername(SharedDatabaseSupport.postgres().getUsername());
      ds.setPassword(SharedDatabaseSupport.postgres().getPassword());
      Flyway.configure().dataSource(ds).load().migrate();
      JdbcTemplate jdbc = new JdbcTemplate(ds);
      TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
      StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("jdbc", jdbc, "tx", tx, "docs", new SharedDocuments(jdbc, tx)));
      Cluster cluster = new Cluster(beans.getBeanProvider(JdbcTemplate.class), beans.getBeanProvider(TransactionTemplate.class),
          beans.getBeanProvider(SharedDocuments.class), beans.getBeanProvider(net.javacrumbs.shedlock.core.LockProvider.class));

      PortalProperties props = new PortalProperties();
      props.setDataDir(data.toString());
      Files.writeString(data.resolve("portal-config.json"), "{\"forms\":[],\"definitions\":[],\"grants\":[{\"id\":\"g1\",\"definitionId\":\"d\","
          + "\"subjectType\":\"user\",\"subject\":\"alice\",\"access\":\"read\"}]}");
      ConfigStore config = new ConfigStore(MAPPER, props, cluster, new DatabaseProperties());
      assertThat(config.snapshot().grants).hasSize(1);

      // Another instance with a different file of its own starts (with a warning): the database copy wins.
      PortalProperties otherProps = new PortalProperties();
      Path otherData = Files.createTempDirectory("dsp-other-config");
      otherProps.setDataDir(otherData.toString());
      Files.writeString(otherData.resolve("portal-config.json"), "{\"forms\":[],\"definitions\":[],\"grants\":[{\"id\":\"g2\","
          + "\"definitionId\":\"d\",\"subjectType\":\"user\",\"subject\":\"bob\",\"access\":\"read\"}]}");
      assertThat(new ConfigStore(MAPPER, otherProps, cluster, new DatabaseProperties()).snapshot().grants)
          .extracting(g -> g.id()).containsExactly("g1");

      // Audit: three events in the files of the single instance.
      FileAuditStore files = new FileAuditStore(data.resolve("audit"));
      var log = AuditChainTest.newLog(data.resolve("audit"));
      for (int i = 0; i < 3; i++) log.event("test.event").system().success();
      files = new FileAuditStore(data.resolve("audit"));
      JdbcAuditStore shared = new JdbcAuditStore(cluster, data.resolve("audit"));
      assertThat(shared.importFrom(files)).isEqualTo(3);
      assertThat(shared.importFrom(files)).as("only once").isZero();
      assertThat(shared.headHash()).isEqualTo(files.headHash());
      assertThat(shared.verify().intact()).isTrue();
      // The same files again (a restart): nothing to do.
      assertThat(new JdbcAuditStore(cluster, data.resolve("audit")).importFrom(new FileAuditStore(data.resolve("audit")))).isZero();

      // Another instance with audit files of its own that were never imported: it refuses to
      // start rather than drop them.
      Path otherDir = Files.createTempDirectory("dsp-other");
      var otherLog = AuditChainTest.newLog(otherDir.resolve("audit"));
      otherLog.event("test.other").system().success();
      FileAuditStore otherFiles = new FileAuditStore(otherDir.resolve("audit"));
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JdbcAuditStore(cluster, otherDir.resolve("audit")).importFrom(otherFiles))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to start").hasMessageContaining("not the ones imported");
      // A configuration created empty first, then an instance with a file of its own: refused.
      cluster.documents().delete("portal-config");
      cluster.documents().delete("import/portal-config");
      PortalProperties empty = new PortalProperties();
      empty.setDataDir(Files.createTempDirectory("dsp-empty").toString());
      new ConfigStore(MAPPER, empty, cluster, new DatabaseProperties());
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ConfigStore(MAPPER, props, cluster, new DatabaseProperties()))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("without importing");

      // Forwarder cursors only move forward, whoever writes them.
      AuditState leader = new AuditState(data.resolve("audit"), MAPPER, cluster.documents());
      AuditState stale = new AuditState(data.resolve("audit"), MAPPER, cluster.documents());
      leader.setCursor("splunk", 10, "t1");
      stale.setCursor("splunk", 5, "t0");
      AuditState fresh = new AuditState(data.resolve("audit"), MAPPER, cluster.documents());
      assertThat(fresh.cursor("splunk").seq()).isEqualTo(10);
    }
  }
}
