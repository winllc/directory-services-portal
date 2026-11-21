package com.winllc.dsp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the monthly files a line at a time: older cursors, newest-first scans, pruning. */
class FileAuditStoreTest {
  @TempDir Path dir;

  @Test
  void readsOlderEventsFromTheFilesAndSeeksToTheCursor() throws Exception {
    FileAuditStore store = new FileAuditStore(dir);
    List<AuditEvent> events = new ArrayList<>();
    for (int i = 0; i < 2_500; i++) events.add(AuditChainTest.event("system.started", "2026-01-01T00:00:00.000Z"));
    for (int i = 0; i < 10; i++) events.add(AuditChainTest.event("system.started", "2026-02-01T00:00:00.000Z"));
    store.appendAll(events);
    store = new FileAuditStore(dir); // the tail holds February only: January is read from its file

    assertThat(store.readAfter(0, 1_000)).extracting(AuditStore.Stored::seq)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 1_000).boxed().toList());
    Path january = dir.resolve("audit-2026-01.jsonl");
    assertThat(store.offsetBefore(january, 1_001)).as("the next read starts at seq 1000, not the top").isPositive();
    assertThat(store.readAfter(1_000, 1_000)).extracting(AuditStore.Stored::seq)
        .containsExactlyElementsOf(LongStream.rangeClosed(1_001, 2_000).boxed().toList());
    assertThat(store.readAfter(2_495, 10)).extracting(AuditStore.Stored::seq)
        .containsExactlyElementsOf(LongStream.rangeClosed(2_496, 2_505).boxed().toList());
    assertThat(store.verify().intact()).isTrue();
  }

  @Test
  void scansNewestFirstAcrossFilesAndLongLines() throws Exception {
    FileAuditStore store = new FileAuditStore(dir);
    store.append(AuditChainTest.event("system.started", "2026-01-01T00:00:00.000Z"), null);
    AuditEvent big = AuditChainTest.event("system.started", "2026-01-02T00:00:00.000Z");
    big.details().put("note", "x".repeat(200_000)); // longer than a read block
    store.append(big, null);
    store.append(AuditChainTest.event("system.started", "2026-02-01T00:00:00.000Z"), null);

    List<Long> seen = new ArrayList<>();
    store.scanNewestFirst(null, s -> seen.add(s.seq()));
    assertThat(seen).containsExactly(3L, 2L, 1L);
  }

  @Test
  void readsLinesBackwards() throws Exception {
    Path f = dir.resolve("lines");
    Files.writeString(f, "a\n" + "b".repeat(150_000) + "\n\nc\n");
    List<String> lines = new ArrayList<>();
    try (FileAuditStore.ReverseLines r = new FileAuditStore.ReverseLines(f)) {
      String l;
      while ((l = r.previous()) != null) lines.add(l);
    }
    assertThat(lines).containsExactly("c", "", "b".repeat(150_000), "a");
  }

  @Test
  void pruningRewritesTheFilesItTouchesOnly() throws Exception {
    FileAuditStore store = new FileAuditStore(dir);
    for (int i = 1; i <= 3; i++) store.append(AuditChainTest.event("system.started", "2026-01-0" + i + "T00:00:00.000Z"), null);
    for (int i = 1; i <= 3; i++) store.append(AuditChainTest.event("system.started", "2026-02-0" + i + "T00:00:00.000Z"), null);
    byte[] february = Files.readAllBytes(dir.resolve("audit-2026-02.jsonl"));

    AuditStore.Pruned p = store.prune(s -> s.seq() <= 2);

    assertThat(p.count()).isEqualTo(2);
    assertThat(p.fromSeq()).isEqualTo(1);
    assertThat(p.toSeq()).isEqualTo(2);
    assertThat(Files.readAllLines(dir.resolve("audit-2026-01.jsonl"))).hasSize(1);
    assertThat(Files.readAllBytes(dir.resolve("audit-2026-02.jsonl"))).isEqualTo(february);
    assertThat(store.readAfter(0, 10)).extracting(AuditStore.Stored::seq).containsExactly(3L, 4L, 5L, 6L);
    assertThat(new FileAuditStore(dir).verify().intact()).isTrue();
  }
}
