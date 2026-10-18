package com.winllc.dsp.cluster;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Whether this API runs alone or as one of several replicas sharing the database
 * ({@code DSP_DATABASE_URL}), and the shared pieces when it does: the database, the shared
 * documents and cluster-wide locks for scheduled work. Alone, locks simply run the task.
 */
@Component
public class Cluster {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final SharedDocuments documents;
  private final LockingTaskExecutor locks;
  private final String replicaId;

  public Cluster(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx, ObjectProvider<SharedDocuments> documents,
      ObjectProvider<LockProvider> locks) {
    this.jdbc = jdbc.getIfAvailable();
    this.tx = tx.getIfAvailable();
    this.documents = documents.getIfAvailable();
    LockProvider provider = locks.getIfAvailable();
    this.locks = provider == null ? null : new DefaultLockingTaskExecutor(provider);
    this.replicaId = hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** A cluster without a database, for tests of single components. */
  public static Cluster standalone() {
    return new Cluster(new Missing<>(), new Missing<>(), new Missing<>(), new Missing<>());
  }

  /** True when replicas share the database. */
  public boolean shared() {
    return jdbc != null;
  }

  public JdbcTemplate jdbc() {
    return require(jdbc);
  }

  public TransactionTemplate tx() {
    return require(tx);
  }

  public SharedDocuments documents() {
    return require(documents);
  }

  /** This process among the replicas: the host name and a random suffix (unique per start). */
  public String replicaId() {
    return replicaId;
  }

  /**
   * Runs scheduled work on one replica at a time (ShedLock): a replica that finds the lock held
   * skips this run. {@code atMost} frees the lock of a replica that died while holding it;
   * {@code atLeast} keeps it held that long, so replicas whose schedules fire at slightly different
   * times don't run the same work twice. Alone, the task just runs.
   */
  public void runLocked(String name, Duration atMost, Duration atLeast, Runnable task) {
    if (locks == null) {
      task.run();
      return;
    }
    locks.executeWithLock(task, new LockConfiguration(java.time.Instant.now(), name, atMost, atLeast));
  }

  /** Takes a transaction-level advisory lock on {@code key} (held until the transaction ends). */
  static void advisoryXactLock(JdbcTemplate jdbc, String key) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> {}, key);
  }

  /** {@link #advisoryXactLock} for callers outside this package. */
  public void lockForTransaction(String key) {
    advisoryXactLock(jdbc(), key);
  }

  private static <T> T require(T value) {
    if (value == null) throw new IllegalStateException("The shared database is not configured (DSP_DATABASE_URL)");
    return value;
  }

  private static String hostname() {
    String env = System.getenv("HOSTNAME");
    if (env != null && !env.isBlank()) return env;
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "api";
    }
  }

  /** An ObjectProvider with nothing in it. */
  private static final class Missing<T> implements ObjectProvider<T> {
    @Override
    public T getObject(Object... args) {
      throw new IllegalStateException("not available");
    }

    @Override
    public T getIfAvailable() {
      return null;
    }

    @Override
    public T getIfUnique() {
      return null;
    }

    @Override
    public T getObject() {
      throw new IllegalStateException("not available");
    }
  }
}
