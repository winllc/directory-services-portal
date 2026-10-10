package com.winllc.dsp.web;

import com.winllc.dsp.jobs.Job;
import com.winllc.dsp.jobs.JobService;
import com.winllc.dsp.model.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's background computations: progress, results, cancelling, notifications. */
@RestController
@RequestMapping("/api/jobs")
public class JobsController {
  private final JobService jobs;

  public JobsController(JobService jobs) {
    this.jobs = jobs;
  }

  public record SeenRequest(@NotNull @Size(max = 500) List<@Size(max = 64) String> ids) {}

  public record JobResult(Job.View job, Object result) {}

  /**
   * How endpoints answer work that may outlast the request: 200 with the value, or 202 with the
   * job that is still computing it ({@code {"pending": true, "job": {...}}}).
   */
  static ResponseEntity<Object> respond(JobService.Outcome outcome) {
    if (outcome.pending()) return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("pending", true, "job", outcome.job().view()));
    return ResponseEntity.ok(outcome.value());
  }

  static ResponseEntity<Object> accepted(Job job) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("pending", true, "job", job.view()));
  }

  @GetMapping
  public List<Job.View> list(@AuthenticationPrincipal SessionUser user) {
    return jobs.list(user);
  }

  @GetMapping("/{id}")
  public Job.View get(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    return jobs.get(user, id).view();
  }

  /** The finished job's result (the same body the starting endpoint would have returned). */
  @GetMapping("/{id}/result")
  public JobResult result(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    Job job = jobs.get(user, id);
    switch (job.status()) {
      case SUCCEEDED -> {
        return new JobResult(job.view(), job.result());
      }
      case FAILED -> throw new ApiException(HttpStatus.CONFLICT, "The computation failed: " + job.error());
      case CANCELLED -> throw new ApiException(HttpStatus.CONFLICT, "The computation was cancelled");
      default -> throw new ApiException(HttpStatus.CONFLICT, "The computation is still running");
    }
  }

  @PostMapping("/{id}/cancel")
  public Job.View cancel(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    return jobs.cancel(user, id);
  }

  /** Mark finished jobs as seen (their "ready" notification has been shown). */
  @PostMapping("/seen")
  public ResponseEntity<Void> seen(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody SeenRequest body) {
    jobs.markSeen(user, body.ids());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> dismiss(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    jobs.dismiss(user, id);
    return ResponseEntity.noContent().build();
  }
}
