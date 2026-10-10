package com.winllc.dsp.web;

import com.winllc.dsp.analysis.AnalysisDefinition;
import com.winllc.dsp.analysis.AnalysisResult;
import com.winllc.dsp.analysis.AnalysisResultStore;
import com.winllc.dsp.analysis.AnalysisService;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.jobs.Job;
import com.winllc.dsp.jobs.JobService;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.store.ConfigStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved comparisons and data-quality checks, and running them (saved or ad hoc). Runs are
 * background jobs: the request returns at once (202 with the job) and the job reports when the
 * result is ready. The latest result of each saved analysis is kept on disk.
 */
@RestController
@RequestMapping("/api/admin/analysis")
public class AdminAnalysisController {
  private final ConfigStore store;
  private final AnalysisService analysis;
  private final AuditLog audit;
  private final JobService jobs;
  private final AnalysisResultStore results;

  public AdminAnalysisController(ConfigStore store, AnalysisService analysis, AuditLog audit, JobService jobs, AnalysisResultStore results) {
    this.store = store;
    this.analysis = analysis;
    this.audit = audit;
    this.jobs = jobs;
    this.results = results;
  }

  /** The latest stored result of a saved analysis; {@code stale} when its settings changed since. */
  public record LatestResult(AnalysisResult result, String ranBy, boolean stale) {}

  public record AnalysisInput(
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      @NotNull @Pattern(regexp = "compare|quality") String type,
      @NotNull @Size(min = 1, max = AnalysisDefinition.MAX_SOURCES) List<AnalysisDefinition.@Valid Source> sources,
      @Valid AnalysisDefinition.CompareOptions compare,
      @Valid AnalysisDefinition.QualityOptions quality) {}

  private AnalysisDefinition build(AnalysisInput in, String id, String createdAt, String updatedAt, AnalysisDefinition.LastRun lastRun) {
    Set<String> known = new HashSet<>();
    store.snapshot().definitions.forEach(d -> known.add(d.id()));
    for (int i = 0; i < in.sources().size(); i++) {
      if (!known.contains(in.sources().get(i).definitionId())) {
        throw ApiException.badRequest("Unknown directory", Map.of("sources[" + i + "].definitionId", "Unknown directory"));
      }
    }
    AnalysisDefinition.QualityOptions quality = null;
    if ("quality".equals(in.type())) {
      List<AnalysisDefinition.Rule> rules = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      List<AnalysisDefinition.Rule> given = in.quality() == null || in.quality().rules() == null ? List.of() : in.quality().rules();
      for (AnalysisDefinition.Rule r : given) {
        String rid = r.id() == null || r.id().isBlank() || !ids.add(r.id()) ? "r-" + UUID.randomUUID().toString().substring(0, 8) : r.id();
        ids.add(rid);
        rules.add(r.withId(rid));
      }
      quality = new AnalysisDefinition.QualityOptions(in.quality() != null && in.quality().formRules(), rules);
    }
    AnalysisDefinition a = new AnalysisDefinition(id, in.name().trim(), blank(in.description()), in.type(), in.sources(),
        "compare".equals(in.type()) ? in.compare() : null, quality, createdAt, updatedAt, lastRun);
    AnalysisService.check(a);
    return a;
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private AuditLog.Draft event(String action, AnalysisDefinition before, AnalysisDefinition after) {
    AnalysisDefinition a = after != null ? after : before;
    return audit.event(action).target("analysis", a.id(), a.name())
        .changes(audit.diff(before, after, Set.of("id", "createdAt", "updatedAt", "lastRun")));
  }

  private AnalysisDefinition find(String id) {
    return store.snapshot().analyses.stream().filter(a -> a.id().equals(id)).findFirst()
        .orElseThrow(() -> ApiException.notFound("Analysis not found"));
  }

  @GetMapping
  public List<AnalysisDefinition> list() {
    return store.snapshot().analyses.stream().sorted(Comparator.comparing(AnalysisDefinition::name, String.CASE_INSENSITIVE_ORDER)).toList();
  }

  @GetMapping("/{id}")
  public AnalysisDefinition get(@PathVariable String id) {
    return find(id);
  }

  @PostMapping
  public ResponseEntity<AnalysisDefinition> create(@Valid @RequestBody AnalysisInput in) {
    String now = Instant.now().toString();
    AnalysisDefinition a = build(in, "an-" + UUID.randomUUID(), now, now, null);
    store.update(s -> s.analyses.add(a));
    event("analysis.create", null, a).success();
    return ResponseEntity.status(HttpStatus.CREATED).body(a);
  }

  @PutMapping("/{id}")
  public AnalysisDefinition update(@PathVariable String id, @Valid @RequestBody AnalysisInput in) {
    AnalysisDefinition current = find(id);
    AnalysisDefinition next = build(in, id, current.createdAt(), Instant.now().toString(), current.lastRun());
    AtomicReference<AnalysisDefinition> previous = new AtomicReference<>();
    store.update(s -> {
      for (int i = 0; i < s.analyses.size(); i++) {
        if (s.analyses.get(i).id().equals(id)) {
          previous.set(s.analyses.get(i));
          s.analyses.set(i, next);
          return null;
        }
      }
      throw ApiException.notFound("Analysis not found");
    });
    event("analysis.update", previous.get(), next).success();
    return next;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    AnalysisDefinition removed = store.update(s -> {
      AnalysisDefinition a = s.analyses.stream().filter(x -> x.id().equals(id)).findFirst()
          .orElseThrow(() -> ApiException.notFound("Analysis not found"));
      s.analyses.remove(a);
      return a;
    });
    results.delete(id);
    event("analysis.delete", removed, null).success();
    return ResponseEntity.noContent().build();
  }

  /** Run an unsaved analysis (the editor's "Run" before saving). Its result is kept with the job. */
  @PostMapping("/run")
  public ResponseEntity<Object> runDraft(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody AnalysisInput in) {
    AnalysisDefinition draft = build(in, null, null, null, null);
    JobService.Spec spec = new JobService.Spec("analysis", draft.name(), new Job.Target("analysis-draft", null), "/admin/analysis/results/{job}",
        null, JobService.Pool.ANALYSIS, null);
    return JobsController.accepted(jobs.submit(user, spec, () -> analysis.run(user, draft)));
  }

  /**
   * Run a saved analysis in the background. A run already in progress for it is joined rather
   * than started twice. When it finishes, the result is stored and the analysis remembers when it
   * ran and what it found.
   */
  @PostMapping("/{id}/run")
  public ResponseEntity<Object> run(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    AnalysisDefinition a = find(id);
    AnalysisService.check(a);
    JobService.Spec spec = new JobService.Spec("analysis", a.name(), new Job.Target("analysis", id), "/admin/analysis/" + id,
        "analysis:" + id, JobService.Pool.ANALYSIS, null);
    return JobsController.accepted(jobs.submit(user, spec, () -> {
      AnalysisResult r = analysis.run(user, a);
      results.save(id, new AnalysisResultStore.Stored(a.updatedAt(), user.username(), r));
      AnalysisDefinition.LastRun last = lastRun(user, r);
      store.update(s -> {
        s.analyses.replaceAll(x -> x.id().equals(id) ? x.withLastRun(last) : x);
        return null;
      });
      return r;
    }));
  }

  /** The latest stored result, or 204 when the analysis has not been run yet. */
  @GetMapping("/{id}/result")
  public ResponseEntity<LatestResult> latest(@PathVariable String id) {
    AnalysisDefinition a = find(id);
    AnalysisResultStore.Stored stored = results.load(id);
    if (stored == null) return ResponseEntity.noContent().build();
    return ResponseEntity.ok(new LatestResult(stored.result(), stored.ranBy(), !java.util.Objects.equals(stored.analysisUpdatedAt(), a.updatedAt())));
  }

  private static AnalysisDefinition.LastRun lastRun(SessionUser user, AnalysisResult r) {
    if (r.compare() != null) {
      AnalysisResult.CompareSummary c = r.compare().summary();
      long entries = r.compare().sources().stream().mapToLong(AnalysisResult.SourceStats::entries).sum();
      return new AnalysisDefinition.LastRun(r.ranAt(), user.username(), entries, r.compare().rowCount(),
          c.keys() + " keys: " + c.identical() + " identical, " + c.different() + " different, " + c.partial() + " missing from a source");
    }
    AnalysisResult.QualitySummary q = r.quality().summary();
    return new AnalysisDefinition.LastRun(r.ranAt(), user.username(), q.entries(), q.issues(),
        q.issues() + (q.issues() == 1 ? " finding in " : " findings in ") + q.entriesWithIssues() + " of " + q.entries() + " entries");
  }
}
