package com.winllc.dsp.audit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
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
import java.util.HashMap;
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
  /** Where retention records how far it pruned ({@link #prunedThrough}): {@code seq:hash}. */
  static final String PRUNED_FILE = "audit-pruned";
  /** Recent lines kept in memory so forwarders and the pull API rarely read files. */
  static final int TAIL_CAPACITY = 20_000;
  /** Every event whose seq is a multiple of this has its byte offset remembered, so older reads seek. */
  static final int INDEX_EVERY = 1_000;

  private final Path dir;
  /** Shared by appends and reads; exclusive for pruning, which rewrites files. */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final Deque<Stored> tail = new ArrayDeque<>();
  /** Per file, seq to the byte offset of its line, filled in as files are read; cleared when files are rewritten. */
  private final Map<Path, TreeMap<Long, Long>> offsets = new HashMap<>();
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
    synchronized (offsets) {
      offsets.clear();
    }
    List<Path> files = storeFiles();
    for (int i = files.size() - 1; i >= 0; i--) {
      Path f = files.get(i);
      Deque<Stored> stored = new ArrayDeque<>();
      forEachLine(f, 0, (l, offset) -> {
        Stored s = parse(l);
        if (s != null) {
          stored.addLast(s);
          if (stored.size() > TAIL_CAPACITY) stored.removeFirst();
        }
        return true;
      });
      repairTrailingNewline(f);
      if (stored.isEmpty()) continue;
      Stored last = stored.peekLast();
      headSeq = last.seq();
      headHash = AuditJson.hashOf(last.line());
      headMonth = monthOf(f);
      tail.addAll(stored);
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
        long before = -1;
        try {
          Files.createDirectories(dir);
          // A failed append may have left part of its line in a file: end it before writing on.
          if (lastWriteFailure != null && Files.exists(file)) repairTrailingNewline(file);
          before = Files.exists(file) ? Files.size(file) : 0;
          writeLine(file, line + "\n");
        } catch (IOException | RuntimeException e) {
          lastWriteFailure = Instant.now();
          if (before >= 0) truncate(file, before);
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

  /** Appends one line, synced to disk. */
  void writeLine(Path file, String line) throws IOException {
    Files.writeString(file, line, StandardCharsets.UTF_8,
        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);
  }

  /**
   * Cuts what a failed append left (part of a line, the disk being full) so the next event does not
   * share its line; when that fails too, the next append ends the partial line first.
   */
  private static void truncate(Path file, long size) {
    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
      if (ch.size() > size) {
        ch.truncate(size);
        ch.force(true);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Could not remove the partial line a failed append left in {}: {}", file, e.getMessage());
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
        Path f = files.get(i);
        forEachLine(f, offsetBefore(f, afterSeq + 1), (l, offset) -> {
          Stored s = parse(l);
          if (s == null) return true;
          if (s.seq() % INDEX_EVERY == 0) remember(f, s.seq(), offset);
          if (s.seq() <= afterSeq) return true;
          out.add(s);
          return out.size() < limit;
        });
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
        try (ReverseLines lines = new ReverseLines(f)) {
          String l;
          while ((l = lines.previous()) != null) {
            Stored s = parse(l);
            if (s != null && !visitor.visit(s)) return;
          }
        } catch (IOException e) {
          log.warn("Could not read audit file {}: {}", f, e.getMessage());
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
        boolean[] more = {true};
        forEachLine(f, 0, (l, offset) -> {
          if (l.isBlank()) return true;
          Stored s = parse(l);
          more[0] = visitor.visit(s != null ? s : new Stored(-1, l));
          return more[0];
        });
        if (!more[0]) return;
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

  /** Called for each line read and the byte offset it starts at; return false to stop. */
  @FunctionalInterface
  interface LineVisitor {
    boolean visit(String line, long offset);
  }

  /** Streams a file's lines from a byte offset (a line start), logging a file it cannot read. */
  static void forEachLine(Path f, long from, LineVisitor visitor) {
    try {
      readLines(f, from, visitor);
    } catch (IOException e) {
      log.warn("Could not read audit file {}: {}", f, e.getMessage());
    }
  }

  /** Streams a file's lines from a byte offset (a line start), never holding more than one. */
  static void readLines(Path f, long from, LineVisitor visitor) throws IOException {
    try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
      ch.position(from);
      InputStream in = Channels.newInputStream(ch);
      byte[] buf = new byte[64 * 1024];
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      long offset = from;
      long start = from;
      int n;
      while ((n = in.read(buf)) > 0) {
        int s = 0;
        for (int i = 0; i < n; i++) {
          if (buf[i] != '\n') continue;
          line.write(buf, s, i - s);
          if (!visitor.visit(text(line), start)) return;
          line.reset();
          s = i + 1;
          start = offset + s;
        }
        line.write(buf, s, n - s);
        offset += n;
      }
      if (line.size() > 0) visitor.visit(text(line), start);
    }
  }

  private static String text(ByteArrayOutputStream line) {
    String s = line.toString(StandardCharsets.UTF_8);
    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
  }

  /** Where in {@code f} to start reading for {@code seq}: the remembered line at or before it, else the start. */
  long offsetBefore(Path f, long seq) {
    synchronized (offsets) {
      TreeMap<Long, Long> known = offsets.get(f);
      Map.Entry<Long, Long> at = known == null ? null : known.floorEntry(seq);
      return at == null ? 0 : at.getValue();
    }
  }

  private void remember(Path f, long seq, long offset) {
    synchronized (offsets) {
      offsets.computeIfAbsent(f, k -> new TreeMap<>()).put(seq, offset);
    }
  }

  /** A file's lines from the last to the first, read backwards in blocks. */
  static final class ReverseLines implements Closeable {
    private final RandomAccessFile file;
    private final byte[] block = new byte[64 * 1024];
    private final ByteArrayOutputStream reversed = new ByteArrayOutputStream();
    private long position;
    private int index;

    ReverseLines(Path f) throws IOException {
      file = new RandomAccessFile(f.toFile(), "r");
      position = file.length();
    }

    /** The line before the last one returned, or null at the start of the file. */
    String previous() throws IOException {
      reversed.reset();
      boolean any = false;
      while (true) {
        if (index == 0) {
          if (position == 0) return any ? line() : null;
          int n = (int) Math.min(block.length, position);
          position -= n;
          file.seek(position);
          file.readFully(block, 0, n);
          index = n;
        }
        byte b = block[--index];
        if (b == '\n') {
          if (any || position + index + 1 < file.length()) return line();
          continue; // the file's final newline
        }
        reversed.write(b);
        any = true;
      }
    }

    private String line() {
      byte[] bytes = reversed.toByteArray();
      for (int i = 0, j = bytes.length - 1; i < j; i++, j--) {
        byte t = bytes[i];
        bytes[i] = bytes[j];
        bytes[j] = t;
      }
      String s = new String(bytes, StandardCharsets.UTF_8);
      return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public void close() throws IOException {
      file.close();
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
      // Only the first and last prunable events and how many: never the whole range in memory.
      Stored[] first = {null};
      Stored[] last = {null};
      long[] count = {0};
      scanOldestFirst(s -> {
        if (s.seq() < 0) return true;
        if (s.seq() >= head || !prunable.test(s)) return false;
        if (first[0] == null) first[0] = s;
        last[0] = s;
        count[0]++;
        return true;
      });
      if (count[0] == 0) return new Pruned(0, null, null, null, null);
      long upTo = last[0].seq();
      // Recorded first: an interrupted prune leaves events retention chose to delete, never a gap
      // verification cannot account for.
      recordPrunedThrough(new PrunedThrough(upTo, AuditJson.hashOf(last[0].line())));
      synchronized (offsets) {
        offsets.clear();
      }
      for (Path f : storeFiles()) {
        Stored firstInFile = firstOf(f);
        if (firstInFile == null || firstInFile.seq() > upTo) continue; // nothing to remove here
        Path tmp = f.resolveSibling(f.getFileName() + ".prune.tmp");
        boolean[] changed = {false};
        long[] kept = {0};
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
          readLines(f, 0, (l, offset) -> {
            Stored s = parse(l);
            if (s != null && s.seq() <= upTo) {
              changed[0] = true;
            } else if (!l.isBlank()) {
              try {
                w.write(l);
                w.write('\n');
              } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
              }
              kept[0]++;
            }
            return true;
          });
        } catch (IOException | java.io.UncheckedIOException e) {
          Files.deleteIfExists(tmp);
          throw e instanceof java.io.UncheckedIOException u ? u.getCause() : (IOException) e;
        }
        if (!changed[0]) {
          Files.delete(tmp);
        } else if (kept[0] == 0) {
          Files.delete(tmp);
          Files.delete(f);
        } else {
          try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
          }
          Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
      }
      synchronized (this) {
        tail.removeIf(s -> s.seq() <= upTo);
      }
      return new Pruned(count[0], first[0].seq(), last[0].seq(), first[0].time(), last[0].time());
    } finally {
      lock.writeLock().unlock();
    }
  }

  @Override
  public PrunedThrough prunedThrough() {
    Path f = dir.resolve(PRUNED_FILE);
    if (!Files.exists(f)) return null;
    try {
      return PrunedThrough.parse(Files.readString(f, StandardCharsets.UTF_8));
    } catch (IOException e) {
      log.warn("Could not read {}: {}", f, e.getMessage());
      return null;
    }
  }

  private void recordPrunedThrough(PrunedThrough pruned) throws IOException {
    PrunedThrough before = prunedThrough();
    if (before != null && before.seq() >= pruned.seq()) return;
    Path f = dir.resolve(PRUNED_FILE);
    Path tmp = f.resolveSibling(PRUNED_FILE + ".tmp");
    Files.writeString(tmp, pruned.text() + "\n", StandardCharsets.UTF_8);
    try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
      ch.force(true);
    }
    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Every legacy (pre ens-audit/1) file in the store directory. */
  List<Path> legacyFiles() {
    List<Path> out = new ArrayList<>();
    for (Path f : storeFiles()) {
      forEachLine(f, 0, (l, offset) -> {
        if (l.isBlank()) return true;
        if (!l.startsWith(ENS_PREFIX)) out.add(f);
        return false;
      });
    }
    return Collections.unmodifiableList(out);
  }
}
