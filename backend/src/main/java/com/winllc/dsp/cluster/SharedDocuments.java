package com.winllc.dsp.cluster;

import java.util.List;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versioned JSON documents in {@code dsp_document}, shared by every replica: the portal
 * configuration, saved analysis results, audit API tokens and the audit forwarders' state. A change
 * reads the current version under a row lock and writes the next one, so concurrent changes from
 * different replicas are applied one after the other, never lost.
 */
public class SharedDocuments {
  /** A document's content and version (version 0: it doesn't exist). */
  public record Doc(String content, long version) {
    public boolean exists() {
      return version > 0;
    }
  }

  private static final Doc NONE = new Doc(null, 0);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public SharedDocuments(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  public Doc get(String name) {
    List<Doc> rows = jdbc.query("SELECT content, version FROM dsp_document WHERE name = ?",
        (rs, i) -> new Doc(rs.getString(1), rs.getLong(2)), name);
    return rows.isEmpty() ? NONE : rows.get(0);
  }

  /** The current version, 0 when the document doesn't exist (a cheap check before re-reading). */
  public long version(String name) {
    List<Long> rows = jdbc.query("SELECT version FROM dsp_document WHERE name = ?", (rs, i) -> rs.getLong(1), name);
    return rows.isEmpty() ? 0 : rows.get(0);
  }

  /**
   * Creates the document when it doesn't exist yet (another replica may have just created it: then
   * nothing changes). Returns the document as it now is.
   */
  public Doc createIfAbsent(String name, String content) {
    jdbc.update("INSERT INTO dsp_document (name, version, content) VALUES (?, 1, ?) ON CONFLICT (name) DO NOTHING", name, content);
    return get(name);
  }

  /**
   * Changes a document: {@code change} gets the current content (null when there is none) under a
   * row lock and returns the new content (null deletes the document, the same content leaves it
   * unchanged). Returns the document as written. Exceptions from {@code change} roll back.
   */
  public Doc update(String name, UnaryOperator<String> change) {
    return tx.execute(status -> {
      // Locks the row, or (for a new document) serializes creators on its name.
      Cluster.advisoryXactLock(jdbc, "dsp_document:" + name);
      Doc current = get(name);
      String next = change.apply(current.content());
      if (next == null) {
        if (current.exists()) jdbc.update("DELETE FROM dsp_document WHERE name = ?", name);
        return NONE;
      }
      if (next.equals(current.content())) return current;
      if (current.exists()) {
        jdbc.update("UPDATE dsp_document SET content = ?, version = version + 1, updated_at = now() WHERE name = ?", next, name);
        return new Doc(next, current.version() + 1);
      }
      jdbc.update("INSERT INTO dsp_document (name, version, content) VALUES (?, 1, ?)", name, next);
      return new Doc(next, 1);
    });
  }

  /** Replaces a document whatever its version (for values with one writer, e.g. a result). */
  public void put(String name, String content) {
    update(name, current -> content);
  }

  public void delete(String name) {
    jdbc.update("DELETE FROM dsp_document WHERE name = ?", name);
  }
}
