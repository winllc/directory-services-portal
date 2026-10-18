package com.winllc.dsp.jobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.concurrent.Future;

/**
 * Work running on the server independently of the request that started it (an analysis run, an
 * expensive set of metrics). State changes are synchronized; {@link #view()} is a consistent
 * snapshot for the API.
 */
public final class Job {
  public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean finished() {
      return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
  }

  /** What a job is about, so pages can find their own jobs (e.g. analysis "an-…"). */
  public record Target(String type, String id) {}

  /** The API representation. {@code link} is the portal page that shows the result. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record View(String id, String kind, String title, String status, Target target, String link, long entries, Long expectedEntries,
      String phase, String createdAt, String startedAt, String finishedAt, String error, boolean seen) {}

  final String id;
  final String kind;
  final String title;
  final String owner;
  final Target target;
  final String link;
  final String dedupeKey;
  final Instant createdAt = Instant.now();
  private Status status = Status.QUEUED;
  private Instant startedAt;
  private Instant finishedAt;
  private long entries;
  private Long expectedEntries;
  private String phase;
  private String error;
  private Object result;
  private boolean seen;
  private Throwable failure;
  /** Shown in the user's job list (it outlived the request that started it, or was started as one). */
  private boolean background;
  /** Counts changes, so the shared copy is written only when something changed. */
  private long revision;
  /** A job running on another replica, read from the shared database (nothing runs here). */
  private final boolean remote;
  private volatile boolean cancelRequested;
  volatile Future<?> future;
  final java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();

  Job(String id, String kind, String title, String owner, Target target, String link, String dedupeKey) {
    this(id, kind, title, owner, target, link, dedupeKey, false);
  }

  private Job(String id, String kind, String title, String owner, Target target, String link, String dedupeKey, boolean remote) {
    this.remote = remote;
    this.id = id;
    this.kind = kind;
    this.title = title;
    this.owner = owner;
    this.target = target;
    this.link = link;
    this.dedupeKey = dedupeKey;
  }

  /**
   * A job as another replica published it: its view (the link as stored, with the id already in
   * it) and, once it succeeded, its result.
   */
  static Job remote(View v, String owner, Object result, boolean background) {
    Job j = new Job(v.id(), v.kind(), v.title(), owner, v.target(), v.link(), null, true);
    j.status = Status.valueOf(v.status().toUpperCase(java.util.Locale.ROOT));
    j.startedAt = v.startedAt() == null ? null : Instant.parse(v.startedAt());
    j.finishedAt = v.finishedAt() == null ? null : Instant.parse(v.finishedAt());
    j.remoteCreatedAt = v.createdAt();
    j.entries = v.entries();
    j.expectedEntries = v.expectedEntries();
    j.phase = v.phase();
    j.error = v.error();
    j.seen = v.seen();
    j.result = result;
    j.background = background;
    j.done.complete(null);
    return j;
  }

  private String remoteCreatedAt;
  /** Set once the shared copy holds the result (it is written once). */
  volatile boolean resultPublished;

  boolean isRemote() {
    return remote;
  }

  synchronized long revision() {
    return revision;
  }

  public String id() {
    return id;
  }

  public String owner() {
    return owner;
  }

  public Target target() {
    return target;
  }

  public synchronized Status status() {
    return status;
  }

  public synchronized Object result() {
    return result;
  }

  public synchronized String error() {
    return error;
  }

  synchronized Instant finishedAt() {
    return finishedAt;
  }

  synchronized boolean start() {
    if (status != Status.QUEUED) return false;
    revision++;
    status = Status.RUNNING;
    startedAt = Instant.now();
    phase = "Starting";
    return true;
  }

  synchronized void progress(long entriesRead, Long expected, String newPhase) {
    revision++;
    entries = Math.max(entries, entriesRead);
    // -1: unknown from here on.
    if (expected != null) expectedEntries = expected < 0 ? null : expected;
    if (newPhase != null) phase = newPhase;
  }

  synchronized void succeed(Object value, long entriesRead) {
    if (status.finished()) return;
    revision++;
    result = value;
    entries = Math.max(entries, entriesRead);
    status = Status.SUCCEEDED;
    phase = null;
    finishedAt = Instant.now();
  }

  synchronized void fail(String message, Throwable cause) {
    if (status.finished()) return;
    revision++;
    error = message;
    failure = cause;
    status = Status.FAILED;
    phase = null;
    finishedAt = Instant.now();
  }

  synchronized boolean cancel() {
    if (status.finished()) return false;
    revision++;
    cancelRequested = true;
    if (status == Status.QUEUED) {
      status = Status.CANCELLED;
      finishedAt = Instant.now();
    }
    return true;
  }

  synchronized void cancelled() {
    if (status.finished()) return;
    revision++;
    status = Status.CANCELLED;
    phase = null;
    finishedAt = Instant.now();
  }

  boolean cancelRequested() {
    return cancelRequested;
  }

  synchronized void markSeen() {
    revision++;
    seen = true;
  }

  synchronized Throwable failure() {
    return failure;
  }

  synchronized void makeBackground() {
    revision++;
    background = true;
  }

  synchronized boolean background() {
    return background;
  }

  public synchronized View view() {
    // "{job}" in a link stands for the job's own id (pages that show one job's result).
    return new View(id, kind, title, status.name().toLowerCase(), target, link == null ? null : link.replace("{job}", id), entries, expectedEntries, phase,
        remoteCreatedAt != null ? remoteCreatedAt : createdAt.toString(),
        startedAt == null ? null : startedAt.toString(), finishedAt == null ? null : finishedAt.toString(), error, seen);
  }
}
