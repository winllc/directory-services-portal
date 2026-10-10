package com.winllc.dsp.jobs;

/** Thrown inside a job once it has been cancelled, to stop its work at the next checkpoint. */
public class JobCancelledException extends RuntimeException {
  public JobCancelledException() {
    super("Cancelled", null, false, false);
  }
}
