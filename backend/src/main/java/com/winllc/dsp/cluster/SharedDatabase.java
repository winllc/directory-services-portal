package com.winllc.dsp.cluster;

import com.winllc.dsp.config.EnsProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The shared store, built only when {@code DSP_DATABASE_URL} is set: the connection pool (schema
 * migrated by Flyway before anything uses it), Spring Session JDBC in place of the servlet
 * container's sessions, and ShedLock's lock provider. The Spring Boot auto-configurations for these
 * are not on the class path, so without the URL nothing here exists and no connection is made.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(SharedDatabase.Configured.class)
public class SharedDatabase {
  private static final Logger log = LoggerFactory.getLogger(SharedDatabase.class);

  /** True when {@code portal.database.url} is set (not blank). */
  public static class Configured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String url = context.getEnvironment().getProperty("portal.database.url");
      return url != null && !url.isBlank();
    }
  }

  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(DatabaseProperties props) {
    String url = props.getUrl().trim();
    if (!url.startsWith("jdbc:postgresql:")) {
      throw new IllegalStateException("DSP_DATABASE_URL must be a PostgreSQL JDBC URL (jdbc:postgresql://host:5432/database)");
    }
    HikariConfig config = new HikariConfig();
    config.setPoolName("dsp-shared");
    config.setJdbcUrl(url);
    if (!props.getUsername().isBlank()) config.setUsername(props.getUsername());
    String password = props.resolvedPassword();
    if (password != null && !password.isEmpty()) config.setPassword(password);
    config.setMaximumPoolSize(Math.max(4, props.getPoolSize()));
    config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
    HikariDataSource ds = new HikariDataSource(config);
    try {
      var result = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
      log.info("Shared database ready ({} migration(s) applied, schema version {})", result.migrationsExecuted,
          result.targetSchemaVersion == null ? "unchanged" : result.targetSchemaVersion);
    } catch (RuntimeException e) {
      ds.close();
      throw e;
    }
    return ds;
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new DataSourceTransactionManager(dataSource);
  }

  @Bean
  TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
    return new TransactionTemplate(transactionManager);
  }

  /** ShedLock on {@code dsp_shedlock}, timed by the database's clock (replicas' clocks may differ). */
  @Bean
  LockProvider lockProvider(JdbcTemplate jdbc) {
    return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
        .withJdbcTemplate(jdbc)
        .withTableName("dsp_shedlock")
        .usingDbTime()
        .build());
  }

  @Bean
  SharedDocuments sharedDocuments(JdbcTemplate jdbc, TransactionTemplate tx) {
    return new SharedDocuments(jdbc, tx);
  }

  // ---------------------------------------------------------------------------
  // Sessions
  // ---------------------------------------------------------------------------

  /**
   * Sessions in {@code spring_session}. Attributes are Java-serialized with this application's
   * class loader and read back through {@link SessionAttributes}' allow-list; expired sessions are removed by {@code SharedSessionCleanup} (which audits idle
   * timeouts first), not by the repository's own job.
   */
  @Bean
  JdbcIndexedSessionRepository sessionRepository(JdbcTemplate jdbc, TransactionTemplate tx, EnsProperties ens) {
    JdbcIndexedSessionRepository repo = new JdbcIndexedSessionRepository(jdbc, tx);
    ClassLoader loader = SharedDatabase.class.getClassLoader();
    GenericConversionService conversion = new GenericConversionService();
    conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
    // Only the classes sessions hold are read back (an allow-list); anything else reads as absent.
    conversion.addConverter(byte[].class, Object.class, new SessionAttributes(loader));
    repo.setConversionService(conversion);
    Duration idle = ens.getSession().getIdleTimeout();
    if (idle != null && !idle.isZero()) repo.setDefaultMaxInactiveInterval(idle);
    repo.setFlushMode(FlushMode.ON_SAVE);
    repo.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
    repo.setCleanupCron(Scheduled.CRON_DISABLED);
    return repo;
  }

  /**
   * Spring Session's filter, ahead of Spring Security. Its cookie is the one the container's
   * sessions used (DSP_SESSION, HttpOnly, SameSite=Strict; Secure over TLS or with
   * ENS_COOKIE_SECURE), so signing in works the same with or without the shared store.
   */
  @Bean
  FilterRegistrationBean<SessionRepositoryFilter<?>> sessionRepositoryFilter(JdbcIndexedSessionRepository sessions, EnsProperties ens,
      @Value("${server.servlet.session.cookie.name:DSP_SESSION}") String cookieName) {
    DefaultCookieSerializer cookie = new DefaultCookieSerializer();
    cookie.setCookieName(cookieName);
    cookie.setUseHttpOnlyCookie(true);
    cookie.setSameSite("Strict");
    if (ens.getCookie().isSecure()) cookie.setUseSecureCookie(true);
    CookieHttpSessionIdResolver resolver = new CookieHttpSessionIdResolver();
    resolver.setCookieSerializer(cookie);
    SessionRepositoryFilter<?> filter = new SessionRepositoryFilter<>(sessions);
    filter.setHttpSessionIdResolver(resolver);
    FilterRegistrationBean<SessionRepositoryFilter<?>> registration = new FilterRegistrationBean<>(filter);
    registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER);
    registration.setName("springSessionRepositoryFilter");
    return registration;
  }
}
