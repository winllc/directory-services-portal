package com.winllc.dsp.audit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The audit store: every {@code ens-audit/1} event with its hash, chained in seq order. A single
 * portal instance keeps it in monthly files ({@link FileAuditStore}); replicas sharing the database
 * keep it in {@code dsp_audit_event} ({@link JdbcAuditStore}), with the same lines and the same
 * chain. The lines are the source of truth; nothing updates or deletes one except
 * {@link #prune retention pruning}.
 */
public abstract class AuditStore {
  static final String ENS_PREFIX = "{\"schema\":\"" + AuditEvent.SCHEMA + "\"";
  private static final Pattern SEQ = Pattern.compile(",\"seq\":(\\d+),");

  /** Room for a few hundred events: below this the disk counts as full. */
  static final long MIN_FREE_BYTES = 1024 * 1024;

  /** One stored event: its seq and the full line (with hash). */
  public record Stored(long seq, String line) {
    public String time() {
      return timeOf(line);
    }
  }

  /** Called for each line of a scan; return false to stop. */
  @FunctionalInterface
  public interface Visitor {
    boolean visit(Stored stored);
  }

  public record Pruned(long count, Long fromSeq, Long toSeq, String fromTime, String toTime) {}

  /**
   * The result of walking the chain: {@code brokenSeq} is the first event whose hash, seq or link
   * doesn't check out (null when intact). The anchor is the first retained event: when it is not
   * seq 1 (older events were pruned) its prevHash is accepted as given.
   */
  public record Verification(boolean intact, Long brokenSeq, String problem, Long anchorSeq, boolean anchorIsGenesis,
      Long lastSeq, long checked, long unreadableLines) {}

  /** The local audit directory (the file store's, or where the shared store imported files from). */
  public abstract Path dir();

  public abstract long headSeq();

  public abstract String headHash();

  /** Whether the last append failed (cleared by the next successful one). */
  public abstract Instant lastWriteFailure();

  /** True when an append is expected to work. */
  public abstract boolean writable();

  /**
   * Chains and stores one event before returning. {@code afterCommit} runs once it is stored, in
   * seq order for this process's events.
   *
   * @throws IOException when the event could not be stored; nothing was written
   */
  public abstract Stored append(AuditEvent event, Consumer<Stored> afterCommit) throws IOException;

  /** Blocks until the head passes {@code seq} or the timeout elapses (forwarders idling). */
  public abstract void awaitAfter(long seq, long timeoutMillis) throws InterruptedException;

  /** Up to {@code limit} events with seq above {@code afterSeq}, ascending. */
  public abstract List<Stored> readAfter(long afterSeq, int limit);

  /** Visits stored events newest first; {@code monthFilter} skips months that cannot match. */
  public abstract void scanNewestFirst(Predicate<YearMonth> monthFilter, Visitor visitor);

  /** Visits every stored line oldest first; unreadable lines are passed with seq -1. */
  public abstract void scanOldestFirst(Visitor visitor);

  /** The oldest retained event, or null when the store is empty. */
  public abstract Stored first();

  /**
   * Deletes the oldest events while {@code prunable} holds, never the newest (the chain head).
   * The only path that removes events.
   */
  public abstract Pruned prune(Predicate<Stored> prunable) throws IOException;

  static boolean enoughSpace(long usableBytes) {
    return usableBytes >= MIN_FREE_BYTES;
  }

  /** An {@code ens-audit/1} line with its seq, or null for anything else (legacy or partial lines). */
  static Stored parse(String line) {
    if (line == null || !line.startsWith(ENS_PREFIX) || AuditJson.bodyOf(line) == null) return null;
    Matcher m = SEQ.matcher(line);
    if (!m.find()) return null;
    try {
      return new Stored(Long.parseLong(m.group(1)), line);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static String timeOf(String line) {
    int at = line.indexOf(",\"time\":\"");
    if (at < 0) return null;
    int start = at + 9;
    int end = line.indexOf('"', start);
    return end < 0 ? null : line.substring(start, end);
  }

  public Verification verify() {
    long[] last = {0};
    String[] lastHash = {null};
    Long[] anchor = {null};
    boolean[] genesis = {false};
    long[] checked = {0};
    long[] unreadable = {0};
    Long[] broken = {null};
    String[] problem = {null};
    scanOldestFirst(s -> {
      if (s.seq() < 0) {
        unreadable[0]++;
        return true;
      }
      String body = AuditJson.bodyOf(s.line());
      String prev = body.substring(body.length() - 66, body.length() - 2);
      String recorded = AuditJson.hashOf(s.line());
      if (anchor[0] == null) {
        anchor[0] = s.seq();
        genesis[0] = s.seq() == 1;
        if (genesis[0] && !AuditJson.GENESIS.equals(prev)) {
          broken[0] = s.seq();
          problem[0] = "seq 1 does not start from the genesis hash";
          return false;
        }
      } else if (s.seq() != last[0] + 1) {
        broken[0] = last[0] + 1;
        problem[0] = s.seq() <= last[0] ? "seq " + s.seq() + " is out of order" : "events " + (last[0] + 1) + " to " + (s.seq() - 1) + " are missing";
        return false;
      } else if (!prev.equals(lastHash[0])) {
        broken[0] = s.seq();
        problem[0] = "prevHash does not match the hash of seq " + last[0];
        return false;
      }
      if (!AuditJson.hash(prev, body).equals(recorded)) {
        broken[0] = s.seq();
        problem[0] = "the event's content does not match its hash";
        return false;
      }
      checked[0]++;
      last[0] = s.seq();
      lastHash[0] = recorded;
      return true;
    });
    long head = headSeq();
    if (broken[0] == null && anchor[0] != null && last[0] != head) {
      broken[0] = last[0] + 1;
      problem[0] = "the chain ends at seq " + last[0] + " but the head is seq " + head;
    }
    return new Verification(broken[0] == null, broken[0], problem[0], anchor[0], genesis[0],
        anchor[0] == null ? null : last[0], checked[0], unreadable[0]);
  }
}
