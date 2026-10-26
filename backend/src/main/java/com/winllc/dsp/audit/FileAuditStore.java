package com.winllc.dsp.audit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The audit store of a single portal instance: append-only JSONL files, one per month ({@code audit-YYYY-MM.jsonl}), each line
 * one {@code ens-audit/1} event with its hash. The files are the source of truth.
 *
 * <p><b>Writers are serialized</b> by one synchronized writer per store (this object): it assigns
 * {@code seq} and {@code prevHash} from the chain head held in memory, appends the line with
 * {@code DSYNC} and only then advances the head. This is correct for a single portal instance per
 * data directory; two instances sharing the directory would fork the chain (see README).
 *
 * <p>Events go to the month of their time, but never to a file older than the head's, so the
 * files sorted by name are in seq order. Nothing here updates or deletes a line except
 * {@link #prune retention pruning}, which takes the exclusive lock.
 */
public class FileAuditStore extends AuditStore {
  private static final Logger log = LoggerFactory.getLogger(FileAuditStore.class);
  static final Pattern FILE = Pattern.compile("audit-(\\d{4})-(\\d{2})\\.jsonl");
  /** Recent lines kept in memory so forwarders and the pull API rarely read files. */
  static final int TAIL_CAPACITY = 20_000;

  private final Path dir;
  /** Shared by appends and reads; exclusive for pruning, which rewrites files. */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Deque<Stored> tail = new ArrayDeque<>();
  private long headSeq;
  private String headHash = AuditJson.GENESIS;
  private YearMonth headMonth;
  private volatile Instant lastWriteFailure;

  public FileAuditStore(Path dir) {
    this.dir = dir.toAbsolutePath();
    try {
      Files.createDirectories(this.dir);
    } catch (IOException e) {
      log.error("Could not create the audit directory {}: {}", this.dir, e.getMessage());
    }
    reopen();
  }

  @Override
  public Path dir() {
    return dir;
  }

  @Override
  public synchronized long headSeq() {
    return headSeq;
  }

  @Override
  public synchronized String headHash() {
    return headHash;
  }

  /** Whether the last append failed (cleared by the next successful one). */
  @Override
  public Instant lastWriteFailure() {
    return lastWriteFailure;
  }

  /**
   * True when an append is expected to work: the directory exists, is writable and its file system
   * has at least {@link #MIN_FREE_BYTES} free.
   */
  @Override
  public boolean writable() {
    if (!Files.isDirectory(dir) || !Files.isWritable(dir)) return false;
    try {
      return enoughSpace(Files.getFileStore(dir).getUsableSpace());
    } catch (IOException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------------------
  // Opening
  // ---------------------------------------------------------------------------

  /** Re-reads the chain head and the in-memory tail from the files. */
  final synchronized void reopen() {
    headSeq = 0;
    headHash = AuditJson.GENESIS;
    headMonth = null;
    tail.clear();
    List<Path> files = storeFiles();
    for (int i = files.size() - 1; i >= 0; i--) {
      Path f = files.get(i);
      List<String> lines = readLines(f);
      repairTrailingNewline(f);
      List<Stored> stored = new ArrayList<>();
      for (String l : lines) {
        Stored s = parse(l);
        if (s != null) stored.add(s);
      }
      if (stored.isEmpty()) continue;
      Stored last = stored.get(stored.size() - 1);
      headSeq = last.seq();
      headHash = AuditJson.hashOf(last.line());
      headMonth = monthOf(f);
      int from = Math.max(0, stored.size() - TAIL_CAPACITY);
      tail.addAll(stored.subList(from, stored.size()));
      break;
    }
  }

  /** A crash mid-append can leave a partial last line: end it so the next event starts a new line. */
  private void repairTrailingNewline(Path f) {
    try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "rw")) {
      long len = raf.length();
      if (len == 0) return;
      raf.seek(len - 1);
      if (raf.read() != '\n') {
        raf.seek(len);
        raf.write('\n');
        log.warn("Audit file {} ended in a partial line (interrupted write); it is ignored", f);
      }
    } catch (IOException e) {
      log.warn("Could not check {}: {}", f, e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Appending
  // ---------------------------------------------------------------------------

  /**
   * Chains and appends one event, synced to disk before returning. {@code afterCommit} runs with
   * the writer still held, so outputs see events in seq order.
   *
   * @throws IOException when the line could not be written; the head is not advanced
   */
  @Override
  public Stored append(AuditEvent event, Runnable alongside, Consumer<Stored> afterCommit) throws IOException {
    Stored stored = write(event, afterCommit);
    // Outside the store's locks: what goes with the event (queuing webhooks) doesn't hold up other events.
    runAlongside(alongside);
    return stored;
  }

  private Stored write(AuditEvent event, Consumer<Stored> afterCommit) throws IOException {
    lock.readLock().lock();
    try {
      synchronized (this) {
        long seq = headSeq + 1;
        AuditEvent chained = event.chained(seq, headHash);
        String body = AuditJson.body(chained);
        String hash = AuditJson.hash(headHash, body);
        String line = AuditJson.line(body, hash);
        YearMonth month = monthFor(event.time());
        Path file = fileFor(month);
        try {
          Files.createDirectories(dir);
          Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
              StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);
        } catch (IOException | RuntimeException e) {
          lastWriteFailure = Instant.now();
          throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        lastWriteFailure = null;
        headSeq = seq;
        headHash = hash;
        headMonth = month;
        Stored stored = new Stored(seq, line);
        tail.addLast(stored);
        while (tail.size() > TAIL_CAPACITY) tail.removeFirst();
        if (afterCommit != null) afterCommit.accept(stored);
        notifyAll();
        return stored;
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /** The event is written already: whatever goes with it can't undo that. */
  private static void runAlongside(Runnable alongside) {
    if (alongside == null) return;
    try {
      alongside.run();
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(FileAuditStore.class).error("Failed after storing an audit event: {}",
          com.winllc.dsp.util.LogSafe.of(e.getMessage()));
    }
  }

  /**
   * Appends many events at once (history migration): written per month file and synced once at
   * the end, then the head is re-read.
   */
  synchronized void appendAll(List<AuditEvent> events) throws IOException {
    Map<YearMonth, BufferedWriter> writers = new TreeMap<>();
    try {
      for (AuditEvent event : events) {
        long seq = headSeq + 1;
        AuditEvent chained = event.chained(seq, headHash);
        String body = AuditJson.body(chained);
        String hash = AuditJson.hash(headHash, body);
        YearMonth month = monthFor(event.time());
        BufferedWriter w = writers.get(month);
        if (w == null) {
          w = Files.newBufferedWriter(fileFor(month), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
          writers.put(month, w);
        }
        w.write(AuditJson.line(body, hash));
        w.write('\n');
        headSeq = seq;
        headHash = hash;
        headMonth = month;
      }
    } finally {
      for (BufferedWriter w : writers.values()) w.close();
    }
    for (YearMonth m : writers.keySet()) {
      try (FileChannel ch = FileChannel.open(fileFor(m), StandardOpenOption.WRITE)) {
        ch.force(true);
      }
    }
    reopen();
  }

  /** Blocks until the head passes {@code seq} or the timeout elapses (forwarders idling). */
  @Override
  public synchronized void awaitAfter(long seq, long timeoutMillis) throws InterruptedException {
    if (headSeq <= seq && timeoutMillis > 0) wait(timeoutMillis);
  }

  private YearMonth monthFor(String time) {
    YearMonth m;
    try {
      m = YearMonth.from(Instant.parse(time).atZone(ZoneOffset.UTC));
    } catch (RuntimeException e) {
      m = YearMonth.now(ZoneOffset.UTC);
    }
    return headMonth != null && m.isBefore(headMonth) ? headMonth : m;
  }

  private Path fileFor(YearMonth month) {
    return dir.resolve(String.format("audit-%04d-%02d.jsonl", month.getYear(), month.getMonthValue()));
  }

  static YearMonth monthOf(Path file) {
    Matcher m = FILE.matcher(file.getFileName().toString());
    return m.matches() ? YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : null;
  }

  // ---------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------

  /** Up to {@code limit} events with seq above {@code afterSeq}, ascending. */
  @Override
  public List<Stored> readAfter(long afterSeq, int limit) {
    lock.readLock().lock();
    try {
      synchronized (this) {
        if (afterSeq >= headSeq) return List.of();
        if (!tail.isEmpty() && tail.peekFirst().seq() <= afterSeq + 1) {
          List<Stored> out = new ArrayList<>();
          for (Stored s : tail) {
            if (s.seq() <= afterSeq) continue;
            out.add(s);
            if (out.size() >= limit) break;
          }
          return out;
        }
      }
      List<Stored> out = new ArrayList<>();
      List<Path> files = storeFiles();
      // Start at the last file that begins at or before the cursor.
      int start = 0;
      for (int i = files.size() - 1; i >= 0; i--) {
        Stored first = firstOf(files.get(i));
        if (first != null && first.seq() <= afterSeq + 1) {
          start = i;
          break;
        }
      }
      for (int i = start; i < files.size() && out.size() < limit; i++) {
        for (String l : readLines(files.get(i))) {
          Stored s = parse(l);
          if (s == null || s.seq() <= afterSeq) continue;
          out.add(s);
          if (out.size() >= limit) break;
        }
      }
      return out;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Visits stored events newest first, file by file. {@code monthFilter} skips whole files (by
   * their month) that cannot match.
   */
  @Override
  public void scanNewestFirst(Predicate<YearMonth> monthFilter, Visitor visitor) {
    lock.readLock().lock();
    try {
      List<Path> files = storeFiles();
      for (int i = files.size() - 1; i >= 0; i--) {
        Path f = files.get(i);
        if (monthFilter != null && !monthFilter.test(monthOf(f))) continue;
        List<String> lines = readLines(f);
        for (int j = lines.size() - 1; j >= 0; j--) {
          Stored s = parse(lines.get(j));
          if (s != null && !visitor.visit(s)) return;
        }
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Visits every stored line oldest first; unreadable lines are passed with seq -1. */
  @Override
  public void scanOldestFirst(Visitor visitor) {
    lock.readLock().lock();
    try {
      for (Path f : storeFiles()) {
        for (String l : readLines(f)) {
          if (l.isBlank()) continue;
          Stored s = parse(l);
          if (!visitor.visit(s != null ? s : new Stored(-1, l))) return;
        }
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /** The oldest retained event, or null when the store is empty. */
  @Override
  public Stored first() {
    lock.readLock().lock();
    try {
      for (Path f : storeFiles()) {
        Stored s = firstOf(f);
        if (s != null) return s;
      }
      return null;
    } finally {
      lock.readLock().unlock();
    }
  }

  private Stored firstOf(Path f) {
    try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
      String l;
      while ((l = r.readLine()) != null) {
        Stored s = parse(l);
        if (s != null) return s;
      }
    } catch (IOException e) {
      log.warn("Could not read audit file {}: {}", f, e.getMessage());
    }
    return null;
  }

  /** The store's monthly files, oldest first. */
  List<Path> storeFiles() {
    if (!Files.isDirectory(dir)) return List.of();
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().toList();
    } catch (IOException e) {
      log.warn("Could not list audit directory {}: {}", dir, e.getMessage());
      return List.of();
    }
  }

  static List<String> readLines(Path f) {
    try {
      return Files.readAllLines(f, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("Could not read audit file {}: {}", f, e.getMessage());
      return List.of();
    }
  }

  // ---------------------------------------------------------------------------
  // Retention
  // ---------------------------------------------------------------------------

  /**
   * Deletes the oldest events while {@code prunable} holds, never the newest (the chain head).
   * The only path that removes events.
   */
  @Override
  public Pruned prune(Predicate<Stored> prunable) throws IOException {
    lock.writeLock().lock();
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
      for (Path f : storeFiles()) {
        List<String> lines = readLines(f);
        List<String> keep = new ArrayList<>();
        boolean changed = false;
        for (String l : lines) {
          Stored s = parse(l);
          if (s != null && s.seq() <= upTo) {
            changed = true;
          } else if (!l.isBlank()) {
            keep.add(l);
          }
        }
        if (!changed) continue;
        if (keep.isEmpty()) {
          Files.delete(f);
        } else {
          Path tmp = f.resolveSibling(f.getFileName() + ".prune.tmp");
          Files.write(tmp, keep, StandardCharsets.UTF_8);
          try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
          }
          Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
      }
      synchronized (this) {
        tail.removeIf(s -> s.seq() <= upTo);
      }
      Stored a = doomed.get(0);
      Stored b = doomed.get(doomed.size() - 1);
      return new Pruned(doomed.size(), a.seq(), b.seq(), a.time(), b.time());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Every legacy (pre ens-audit/1) file in the store directory. */
  List<Path> legacyFiles() {
    List<Path> out = new ArrayList<>();
    for (Path f : storeFiles()) {
      for (String l : readLines(f)) {
        if (l.isBlank()) continue;
        if (!l.startsWith(ENS_PREFIX)) out.add(f);
        break;
      }
    }
    return Collections.unmodifiableList(out);
  }
}
