package com.winllc.dsp.audit;

import com.winllc.dsp.cluster.Cluster;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit store shared by replicas: {@code dsp_audit_event}, one row per event holding exactly
 * the line the file store would write. Appends from every replica form one chain: each takes a
 * transaction-level advisory lock, reads the head, writes the next seq and commits, so seq and
 * prevHash are assigned one at a time cluster-wide. Retention deletes the oldest rows, never the
 * head.
 *
 * <p>On the first start with the database the file store's events (after any legacy migration)
 * are copied in unchanged, so the chain carries on from the same head; the files are left as they
 * were.
 */
public class JdbcAuditStore extends AuditStore {
  private static final Logger log = LoggerFactory.getLogger(JdbcAuditStore.class);
  static final String LOCK = "dsp-audit-append";
  private static final int PAGE = 1_000;
  private static final RowMapper<Stored> ROW = (rs, i) -> new Stored(rs.getLong(1), rs.getString(2));

  private final Cluster cluster;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Path dir;
  private volatile Instant lastWriteFailure;
  private final Object appended = new Object();

  public JdbcAuditStore(Cluster cluster, Path dir) {
    this.cluster = cluster;
    this.jdbc = cluster.jdbc();
    this.tx = cluster.tx();
    this.dir = dir.toAbsolutePath();
  }

  /** The {@code dsp_document} row recording which local audit chain was imported (its head). */
  static final String IMPORT_MARKER = "import/audit";

  /**
   * Copies the file store's events in when the table is still empty (first start with the
   * database) and records which chain it was (the import marker). Returns how many were imported.
   *
   * @throws IllegalStateException when this instance has local audit events the shared store
   *     doesn't hold (another instance started on the database first, or these files were written
   *     after the import): starting would silently drop them
   */
  public long importFrom(FileAuditStore files) {
    if (files.headSeq() == 0) return 0;
    String localHead = files.headSeq() + ":" + files.headHash();
    Long imported = tx.execute(status -> {
      cluster.lockForTransaction(LOCK);
      Long existing = jdbc.queryForObject("SELECT count(*) FROM dsp_audit_event", Long.class);
      List<String> marker = jdbc.queryForList("SELECT content FROM dsp_document WHERE name = ?", String.class, IMPORT_MARKER);
      if (existing != null && existing > 0) {
        if (!marker.isEmpty() && marker.get(0).equals(localHead)) return 0L;
        throw new IllegalStateException("Refusing to start: the shared database's audit store already holds events, but the "
            + files.headSeq() + " audit events in " + files.dir() + " were " + (marker.isEmpty() ? "never imported" : "not the ones imported")
            + ". Upgrade a single instance to the database first, with its data volume, so it imports them; or, if these files"
            + " are an outdated copy, move them out of " + files.dir() + " (keep them as an archive) and start again.");
      }
      List<Object[]> batch = new ArrayList<>();
      long[] count = {0};
      files.scanOldestFirst(s -> {
        if (s.seq() < 0) return true;
        batch.add(new Object[] {s.seq(), monthOf(s.time()), s.line()});
        count[0]++;
        if (batch.size() >= PAGE) {
          jdbc.batchUpdate("INSERT INTO dsp_audit_event (seq, event_month, line) VALUES (?, ?, ?)", batch);
          batch.clear();
        }
        return true;
      });
      if (!batch.isEmpty()) jdbc.batchUpdate("INSERT INTO dsp_audit_event (seq, event_month, line) VALUES (?, ?, ?)", batch);
      jdbc.update("INSERT INTO dsp_document (name, version, content) VALUES (?, 1, ?) ON CONFLICT (name) DO UPDATE SET content = EXCLUDED.content,"
          + " version = dsp_document.version + 1, updated_at = now()", IMPORT_MARKER, localHead);
      return count[0];
    });
    long n = imported == null ? 0 : imported;
    if (n > 0) log.info("Imported {} audit events (up to seq {}) from {} into the shared database", n, files.headSeq(), files.dir());
    return n;
  }

  @Override
  public Path dir() {
    return dir;
  }

  @Override
  public long headSeq() {
    Long head = jdbc.queryForObject("SELECT coalesce(max(seq), 0) FROM dsp_audit_event", Long.class);
    return head == null ? 0 : head;
  }

  @Override
  public String headHash() {
    Stored head = head();
    return head == null ? AuditJson.GENESIS : AuditJson.hashOf(head.line());
  }

  private Stored head() {
    List<Stored> rows = jdbc.query("SELECT seq, line FROM dsp_audit_event ORDER BY seq DESC LIMIT 1", ROW);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public Instant lastWriteFailure() {
    return lastWriteFailure;
  }

  @Override
  public boolean writable() {
    try {
      jdbc.queryForObject("SELECT 1", Integer.class);
      return true;
    } catch (DataAccessException e) {
      return false;
    }
  }

  @Override
  public Stored append(AuditEvent event, Runnable alongside, Consumer<Stored> afterCommit) throws IOException {
    Stored stored;
    try {
      stored = tx.execute(status -> {
        cluster.lockForTransaction(LOCK);
        Stored head = head();
        long seq = head == null ? 1 : head.seq() + 1;
        String prev = head == null ? AuditJson.GENESIS : AuditJson.hashOf(head.line());
        AuditEvent chained = event.chained(seq, prev);
        String body = AuditJson.body(chained);
        String line = AuditJson.line(body, AuditJson.hash(prev, body));
        jdbc.update("INSERT INTO dsp_audit_event (seq, event_month, line) VALUES (?, ?, ?)", seq, monthOf(event.time()), line);
        // What goes with the event (the webhook outbox rows of an entry change) commits with it.
        if (alongside != null) alongside.run();
        return new Stored(seq, line);
      });
    } catch (RuntimeException e) {
      lastWriteFailure = Instant.now();
      throw new IOException(e.getMessage(), e);
    }
    lastWriteFailure = null;
    if (afterCommit != null) afterCommit.accept(stored);
    synchronized (appended) {
      appended.notifyAll();
    }
    return stored;
  }

  /** Waits for an event of this replica, or polls the head (other replicas' events) once a second. */
  @Override
  public void awaitAfter(long seq, long timeoutMillis) throws InterruptedException {
    if (timeoutMillis <= 0 || headSeq() > seq) return;
    synchronized (appended) {
      appended.wait(Math.min(timeoutMillis, 1_000));
    }
  }

  @Override
  public List<Stored> readAfter(long afterSeq, int limit) {
    return jdbc.query("SELECT seq, line FROM dsp_audit_event WHERE seq > ? ORDER BY seq LIMIT ?", ROW, afterSeq, limit);
  }

  @Override
  public void scanNewestFirst(Predicate<YearMonth> monthFilter, Visitor visitor) {
    long before = Long.MAX_VALUE;
    while (true) {
      List<Object[]> rows = jdbc.query("SELECT seq, line, event_month FROM dsp_audit_event WHERE seq < ? ORDER BY seq DESC LIMIT ?",
          (rs, i) -> new Object[] {new Stored(rs.getLong(1), rs.getString(2)), rs.getString(3)}, before, PAGE);
      if (rows.isEmpty()) return;
      for (Object[] r : rows) {
        Stored s = (Stored) r[0];
        before = s.seq();
        if (monthFilter != null && !monthFilter.test(YearMonth.parse((String) r[1]))) continue;
        if (!visitor.visit(s)) return;
      }
    }
  }

  @Override
  public void scanOldestFirst(Visitor visitor) {
    long after = Long.MIN_VALUE;
    while (true) {
      List<Stored> rows = jdbc.query("SELECT seq, line FROM dsp_audit_event WHERE seq > ? ORDER BY seq LIMIT ?", ROW, after, PAGE);
      if (rows.isEmpty()) return;
      for (Stored row : rows) {
        after = row.seq();
        Stored s = parse(row.line());
        if (!visitor.visit(s != null && s.seq() == row.seq() ? s : new Stored(-1, row.line()))) return;
      }
    }
  }

  @Override
  public Stored first() {
    List<Stored> rows = jdbc.query("SELECT seq, line FROM dsp_audit_event ORDER BY seq LIMIT 1", ROW);
    return rows.isEmpty() ? null : rows.get(0);
  }

  @Override
  public Pruned prune(Predicate<Stored> prunable) throws IOException {
    try {
      long head = headSeq();
      List<Stored> doomed = new ArrayList<>();
      scanOldestFirst(s -> {
        if (s.seq() < 0) return true;
        if (s.seq() >= head || !prunable.test(s)) return false;
        doomed.add(s);
        return true;
      });
      if (doomed.isEmpty()) return new Pruned(0, null, null, null, null);
      long upTo = doomed.get(doomed.size() - 1).seq();
      jdbc.update("DELETE FROM dsp_audit_event WHERE seq <= ? AND seq < ?", upTo, head);
      Stored a = doomed.get(0);
      Stored b = doomed.get(doomed.size() - 1);
      return new Pruned(doomed.size(), a.seq(), b.seq(), a.time(), b.time());
    } catch (DataAccessException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** The month an event is filed under (for month filters), from its time. */
  static String monthOf(String time) {
    YearMonth m;
    try {
      m = YearMonth.from(Instant.parse(time).atZone(ZoneOffset.UTC));
    } catch (RuntimeException e) {
      m = YearMonth.now(ZoneOffset.UTC);
    }
    return m.toString();
  }
}
