package com.winllc.dsp.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Elects the one replica that runs continuous background work (the audit forwarders to Splunk and
 * syslog): the holder of a PostgreSQL session-level advisory lock on a dedicated connection. The
 * lock goes with the connection, so a replica that stops or loses the database hands over to
 * another within {@link #CHECK_SECONDS}. Alone (no shared database) this API is always the leader.
 */
@Component
public class ClusterLeader implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(ClusterLeader.class);
  static final long CHECK_SECONDS = 5;
  private static final String LOCK_KEY = "dsp-leader";

  private final DataSource dataSource;
  private final String replicaId;
  private final List<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
  private ScheduledExecutorService timer;
  private Connection connection;
  private volatile boolean leader;
  private volatile boolean running;

  public ClusterLeader(ObjectProvider<DataSource> dataSource, Cluster cluster) {
    this.dataSource = dataSource.getIfAvailable();
    this.replicaId = cluster.replicaId();
  }

  /** Whether this replica is the leader now. */
  public boolean isLeader() {
    return leader;
  }

  /**
   * Calls {@code listener} with true when this replica becomes the leader and false when it stops
   * being one (at once with true when it already is).
   */
  public void watch(Consumer<Boolean> listener) {
    listeners.add(listener);
    if (leader) listener.accept(true);
  }

  @Override
  public synchronized void start() {
    running = true;
    if (dataSource == null) {
      changed(true);
      return;
    }
    timer = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "cluster-leader");
      t.setDaemon(true);
      return t;
    });
    timer.scheduleWithFixedDelay(this::check, 0, CHECK_SECONDS, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    running = false;
    if (timer != null) {
      timer.shutdownNow();
      try {
        timer.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      timer = null;
    }
    if (leader) changed(false);
    closeConnection();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Late: listeners (the audit forwarders) are ready, and are stopped before the database closes. */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  synchronized void check() {
    try {
      if (leader) {
        if (connection == null || !connection.isValid(3)) {
          log.warn("Lost the connection holding cluster leadership; {} steps down", replicaId);
          closeConnection();
          changed(false);
        }
        return;
      }
      if (connection == null || !connection.isValid(3)) {
        closeConnection();
        connection = dataSource.getConnection();
        connection.setAutoCommit(true);
      }
      try (PreparedStatement ps = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
        ps.setString(1, LOCK_KEY);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next() && rs.getBoolean(1)) {
            log.info("{} is now the cluster leader (runs the audit forwarders)", replicaId);
            changed(true);
          }
        }
      }
    } catch (SQLException | RuntimeException e) {
      log.warn("Cluster leadership check failed: {}", e.getMessage());
      closeConnection();
      if (leader) changed(false);
    }
  }

  private void changed(boolean now) {
    leader = now;
    for (Consumer<Boolean> l : listeners) {
      try {
        l.accept(now);
      } catch (RuntimeException e) {
        log.error("Leadership listener failed", e);
      }
    }
  }

  private void closeConnection() {
    if (connection == null) return;
    try {
      // Returned to the pool: the lock (session-level) must not stay with the pooled connection.
      try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock_all()")) {
        ps.execute();
      } catch (SQLException e) {
        // The connection is gone, and with it the lock.
      }
      connection.close();
    } catch (SQLException e) {
      // Ignored: closing.
    }
    connection = null;
  }
}
