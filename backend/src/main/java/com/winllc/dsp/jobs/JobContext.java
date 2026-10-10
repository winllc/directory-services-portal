package com.winllc.dsp.jobs;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The running job, as seen by the code doing its work: report progress and check for
 * cancellation. Work that runs outside a job (tests, synchronous calls) sees {@link #NONE},
 * whose methods do nothing.
 *
 * <p>The context is bound to the job's thread. Code that hands work to other threads (the LDAP
 * SDK may deliver search results on its reader thread) must capture {@link #current()} first.
 */
public class JobContext {
  public static final JobContext NONE = new JobContext(null);
  private static final ThreadLocal<JobContext> CURRENT = new ThreadLocal<>();

  private final Job job;
  private final AtomicLong entries = new AtomicLong();

  JobContext(Job job) {
    this.job = job;
  }

  public static JobContext current() {
    JobContext c = CURRENT.get();
    return c == null ? NONE : c;
  }

  static void bind(JobContext c) {
    CURRENT.set(c);
  }

  static void unbind() {
    CURRENT.remove();
  }

  /**
   * What the job is doing now, e.g. "Reading Staff Directory", and how many entries this step is
   * expected to read (null when unknown). Progress counts across steps.
   */
  public void phase(String phase, Long expectedEntries) {
    if (job == null) return;
    long done = entries.get();
    job.progress(done, expectedEntries == null ? -1L : done + expectedEntries, phase);
  }

  /** Count entries read; checks for cancellation every few hundred entries. */
  public void entryRead() {
    if (job == null) return;
    long n = entries.incrementAndGet();
    if ((n & 0xff) == 0) {
      job.progress(n, null, null);
      checkCancelled();
    }
  }

  public void checkCancelled() {
    if (job != null && job.cancelRequested()) throw new JobCancelledException();
  }

  public long entries() {
    return entries.get();
  }
}
