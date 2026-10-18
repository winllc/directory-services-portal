package com.winllc.dsp.jobs;

import com.winllc.dsp.cluster.Cluster;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs analyses and expensive metrics on server threads, independent of the request (and the
 * browser) that started them. Each job belongs to the user who started it; their job list shows
 * its progress, and the result stays available after it finishes.
 *
 * <p>Requests that want an answer quickly use {@link #runOrWait}: the work starts as a job and the
 * request waits a short while for it. Cheap work finishes in time and is returned directly;
 * anything slower carries on in the background and the caller gets the job instead.
 *
 * <p>Jobs and their results live in memory: they don't survive a restart (saved analyses keep
 * their latest result on disk, see {@code AnalysisResultStore}). With the shared database a job
 * still runs on the replica that started it, but its progress and result are also kept in
 * {@code dsp_job} ({@link JobMirror}), so the job list, results, cancelling and dismissing work
 * from any replica.
 */
@Service
public class JobService implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(JobService.class);

  /** Separate thread pools, so a long analysis never holds up dashboards. */
  public enum Pool { METRICS, ANALYSIS }

  /**
   * What to run. {@code dedupeKey}: a second request with the same key (from the same user) joins
   * the job already running, and within {@code reuseFor} after it finished gets its result.
   */
  public record Spec(String kind, String title, Job.Target target, String link, String dedupeKey, Pool pool, Duration reuseFor) {}

  /** A finished value, or the job still working on it. */
  public record Outcome(Object value, Job job) {
    public boolean pending() {
      return job != null;
    }
  }

  private final Map<String, Job> jobs = new LinkedHashMap<>();
  private final ExecutorService metrics;
  private final ExecutorService analysis;
  private final int maxActivePerUser;
  private final Duration retain;
  private final int maxJobs;
  /** The shared copy (null alone). */
  private final JobMirror mirror;

  public JobService(PortalProperties props, Cluster cluster, JsonMapper mapper) {
    this.mirror = cluster.shared() ? new JobMirror(cluster, mapper) : null;
    PortalProperties.Jobs cfg = props.getJobs();
    this.metrics = pool("metrics", cfg.getMetricsThreads());
    this.analysis = pool("analysis", cfg.getAnalysisThreads());
    this.maxActivePerUser = Math.max(1, cfg.getMaxActivePerUser());
    this.retain = Duration.ofMinutes(Math.max(1, cfg.getRetainMinutes()));
    this.maxJobs = Math.max(50, cfg.getMaxJobs());
  }

  private static ExecutorService pool(String name, int threads) {
    AtomicInteger n = new AtomicInteger();
    return Executors.newFixedThreadPool(Math.max(1, threads), r -> {
      Thread t = new Thread(r, "jobs-" + name + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    });
  }

  /** Start work in the background (or join the same work already running); it shows in the user's job list. */
  public Job submit(SessionUser user, Spec spec, Supplier<Object> work) {
    Job job = start(user, spec, work, false);
    job.makeBackground();
    publish(job);
    return job;
  }

  /**
   * Start (or join, or reuse) the work and wait up to {@code wait} for it. Returns the value when
   * it finished in time; otherwise the job, which then shows in the user's job list. Failures
   * that happen within the wait are rethrown as they are (e.g. a 400 for invalid input).
   */
  public Outcome runOrWait(SessionUser user, Spec spec, Supplier<Object> work, Duration wait, boolean refresh) {
    if (!refresh && spec.dedupeKey() != null) {
      Job recent = recent(user, spec);
      if (recent != null) return new Outcome(recent.result(), null);
    }
    Job job = start(user, spec, work, refresh);
    try {
      job.done.get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      job.makeBackground();
      publish(job);
      return new Outcome(null, job);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      job.makeBackground();
      publish(job);
      return new Outcome(null, job);
    } catch (Exception e) {
      // done never completes exceptionally; the job records its own outcome.
    }
    return switch (job.status()) {
      case SUCCEEDED -> {
        job.markSeen();
        yield new Outcome(job.result(), null);
      }
      case FAILED -> throw job.failure() instanceof RuntimeException re ? re : new IllegalStateException(job.error(), job.failure());
      default -> throw new ApiException(HttpStatus.CONFLICT, "The computation was cancelled");
    };
  }

  /**
   * A result from the background is kept at least this long for the same request, so following
   * the "ready" notification shows it instead of starting over.
   */
  static final Duration BACKGROUND_REUSE = Duration.ofMinutes(15);

  private synchronized Job recent(SessionUser user, Spec spec) {
    Duration reuse = spec.reuseFor() == null ? Duration.ZERO : spec.reuseFor();
    Instant now = Instant.now();
    Job best = null;
    for (Job j : jobs.values()) {
      if (!Objects.equals(j.dedupeKey, spec.dedupeKey()) || !j.owner.equals(user.username()) || j.status() != Job.Status.SUCCEEDED) continue;
      Duration window = j.background() && BACKGROUND_REUSE.compareTo(reuse) > 0 ? BACKGROUND_REUSE : reuse;
      if (j.finishedAt() != null && j.finishedAt().isAfter(now.minus(window)) && (best == null || j.finishedAt().isAfter(best.finishedAt()))) {
        best = j;
      }
    }
    if (best != null) best.markSeen();
    return best;
  }

  private Job start(SessionUser user, Spec spec, Supplier<Object> work, boolean forceNew) {
    Job job;
    synchronized (this) {
      housekeeping();
      if (spec.dedupeKey() != null) {
        for (Job j : jobs.values()) {
          // Join the same work already running for this user, unless a fresh run was asked for.
          if (!j.status().finished() && j.owner.equals(user.username()) && spec.dedupeKey().equals(j.dedupeKey) && !forceNew) return j;
        }
      }
      long active = jobs.values().stream().filter(j -> j.owner.equals(user.username()) && !j.status().finished()).count();
      if (mirror != null) active = Math.max(active, activeShared(user));
      if (active >= maxActivePerUser) {
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
            "You already have " + active + " computations running. Wait for one to finish, or cancel one.");
      }
      job = new Job("job-" + UUID.randomUUID(), spec.kind(), spec.title(), user.username(), spec.target(), spec.link(), spec.dedupeKey());
      jobs.put(job.id, job);
    }
    publish(job);
    Job running = job;
    running.future = (spec.pool() == Pool.ANALYSIS ? analysis : metrics).submit(() -> execute(running, work));
    return running;
  }

  private void execute(Job job, Supplier<Object> work) {
    if (!job.start()) {
      publish(job);
      job.done.complete(null);
      return;
    }
    publish(job);
    JobContext ctx = new JobContext(job);
    JobContext.bind(ctx);
    try {
      Object value = work.get();
      if (job.cancelRequested()) job.cancelled();
      else job.succeed(value, ctx.entries());
    } catch (JobCancelledException e) {
      job.cancelled();
    } catch (Throwable t) {
      if (job.cancelRequested()) {
        job.cancelled();
      } else {
        if (!(t instanceof ApiException)) log.warn("Job {} ({}) failed", job.id, job.title, t);
        job.fail(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
      }
    } finally {
      JobContext.unbind();
      publish(job);
      job.done.complete(null);
    }
  }

  /** Drop finished jobs past their retention, and the oldest finished ones beyond the cap. */
  private void housekeeping() {
    Instant cutoff = Instant.now().minus(retain);
    jobs.values().removeIf(j -> {
      boolean old = j.status().finished() && j.finishedAt() != null && j.finishedAt().isBefore(cutoff);
      if (old && mirror != null) mirror.forget(j.id);
      return old;
    });
    if (jobs.size() <= maxJobs) return;
    List<Job> finished = jobs.values().stream().filter(j -> j.status().finished()).sorted(Comparator.comparing(Job::finishedAt)).toList();
    for (Job j : finished) {
      if (jobs.size() <= maxJobs) break;
      jobs.remove(j.id);
    }
  }

  // ---------------------------------------------------------------------------
  // The user's jobs
  // ---------------------------------------------------------------------------

  public synchronized List<Job.View> list(SessionUser user) {
    housekeeping();
    if (mirror != null) return shared(user, null, null);
    List<Job.View> out = new ArrayList<>();
    for (Job j : jobs.values()) {
      if (j.owner.equals(user.username()) && j.background()) out.add(j.view());
    }
    out.sort(Comparator.comparing(Job.View::createdAt).reversed());
    return out;
  }

  public synchronized Job get(SessionUser user, String id) {
    Job j = jobs.get(id);
    if (mirror != null) {
      try {
        if (j != null && j.background() && j.status().finished() && !mirror.exists(id)) {
          // Dismissed on another replica.
          jobs.remove(id);
          mirror.forget(id);
          j = null;
        } else if (j == null) {
          j = mirror.find(id, true);
        }
      } catch (RuntimeException e) {
        log.warn("Could not read shared job {}: {}", id, e.getMessage());
      }
    }
    if (j == null || !j.owner.equals(user.username())) throw ApiException.notFound("Job not found (finished jobs are kept for a while)");
    return j;
  }

  /** Active or recent jobs of the user about one target (e.g. a saved analysis). */
  public synchronized List<Job.View> forTarget(SessionUser user, String type, String id) {
    if (mirror != null) return shared(user, type, id);
    return jobs.values().stream()
        .filter(j -> j.owner.equals(user.username()) && j.background() && j.target != null && j.target.type().equals(type) && j.target.id().equals(id))
        .map(Job::view)
        .sorted(Comparator.comparing(Job.View::createdAt).reversed())
        .toList();
  }

  /** The user's background jobs from the shared table (this replica's own as they are now). */
  private List<Job.View> shared(SessionUser user, String type, String id) {
    List<Job.View> out = new ArrayList<>();
    try {
      for (Job j : mirror.backgroundOf(user.username())) {
        Job local = jobs.get(j.id);
        Job.View v = (local != null ? local : j).view();
        if (type != null && (v.target() == null || !v.target().type().equals(type) || !Objects.equals(v.target().id(), id))) continue;
        out.add(v);
      }
    } catch (RuntimeException e) {
      log.warn("Could not read the shared jobs of {}: {}", user.username(), e.getMessage());
      for (Job j : jobs.values()) {
        if (!j.owner.equals(user.username()) || !j.background()) continue;
        if (type != null && (j.target == null || !j.target.type().equals(type) || !Objects.equals(j.target.id(), id))) continue;
        out.add(j.view());
      }
    }
    out.sort(Comparator.comparing(Job.View::createdAt).reversed());
    return out;
  }

  private long activeShared(SessionUser user) {
    try {
      return mirror.activeOf(user.username());
    } catch (RuntimeException e) {
      return 0;
    }
  }

  public Job.View cancel(SessionUser user, String id) {
    Job j = get(user, id);
    if (j.isRemote()) {
      if (!j.status().finished()) mirror.requestCancel(id);
      return j.view();
    }
    j.cancel();
    publish(j);
    return j.view();
  }

  public void markSeen(SessionUser user, List<String> ids) {
    for (String id : ids) {
      Job j;
      synchronized (this) {
        j = jobs.get(id);
        if (j != null && j.owner.equals(user.username())) j.markSeen();
      }
      if (mirror == null) continue;
      if (j != null) {
        publish(j);
      } else {
        try {
          Job remote = mirror.find(id, false);
          if (remote != null && remote.owner.equals(user.username())) mirror.markSeen(id);
        } catch (RuntimeException e) {
          log.warn("Could not mark shared job {} seen: {}", id, e.getMessage());
        }
      }
    }
  }

  /** Remove a finished job from the list (a running one is cancelled first). */
  public void dismiss(SessionUser user, String id) {
    Job j = get(user, id);
    if (j.isRemote()) {
      if (j.status().finished()) mirror.delete(id);
      else mirror.requestCancel(id);
      return;
    }
    j.cancel();
    synchronized (this) {
      if (j.status().finished()) {
        jobs.remove(id);
        if (mirror != null) mirror.delete(id);
      }
    }
  }

  private void publish(Job job) {
    if (mirror != null && !job.isRemote()) mirror.publish(job);
  }

  /**
   * With the shared database, every 2 seconds: writes the progress of this replica's jobs, acts on
   * cancel requests made on other replicas, and drops expired jobs.
   */
  @Scheduled(fixedDelay = 2_000, initialDelay = 2_000)
  void syncShared() {
    if (mirror == null) return;
    try {
      List<Job> local;
      synchronized (this) {
        local = new ArrayList<>(jobs.values());
      }
      for (Job j : local) publish(j);
      for (String id : mirror.cancelRequests()) {
        Job j;
        synchronized (this) {
          j = jobs.get(id);
        }
        if (j != null) {
          j.cancel();
          publish(j);
        }
      }
      mirror.expire(retain);
    } catch (RuntimeException e) {
      log.warn("Could not synchronize the shared jobs: {}", e.getMessage());
    }
  }

  /** Wait for a job (tests). */
  public void await(Job job, Duration timeout) throws Exception {
    job.done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public void destroy() {
    metrics.shutdownNow();
    analysis.shutdownNow();
  }
}
