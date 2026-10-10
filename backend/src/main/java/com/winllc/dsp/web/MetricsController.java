package com.winllc.dsp.web;

import com.winllc.dsp.metrics.Dashboard;
import com.winllc.dsp.metrics.MetricEngine;
import com.winllc.dsp.metrics.MetricResult;
import com.winllc.dsp.metrics.MetricWidget;
import com.winllc.dsp.metrics.OverviewGenerator;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.DataSources;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.store.ConfigStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metrics for every signed-in user: the out-of-the-box overview of a directory, ad-hoc metrics
 * (the explorer) and saved dashboards. Every widget is computed only from directories the user
 * can read (see {@link DataSources}).
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
  static final int MAX_WIDGETS = 48;

  private final MetricEngine engine;
  private final DataSources sources;
  private final EntryService entries;
  private final ConfigStore store;

  public MetricsController(MetricEngine engine, DataSources sources, EntryService entries, ConfigStore store) {
    this.engine = engine;
    this.sources = sources;
    this.entries = entries;
    this.store = store;
  }

  public record SourceInfo(String id, String name, String slug, String icon, String description, List<DataSources.AttributeChoice> attributes) {}

  public record EvaluateRequest(@NotNull @Size(min = 1, max = MAX_WIDGETS) List<@Valid MetricWidget> widgets, Boolean refresh) {}

  public record DashboardSummary(String id, String name, String description, boolean shared, int widgets, String updatedAt) {}

  public record SummaryRow(String id, String name, String slug, String icon, Long count, boolean truncated, String error) {}

  public record OverviewResponse(Map<String, Object> definition, List<MetricWidget> widgets, Map<String, MetricEngine.Outcome> results) {}

  public record DashboardResults(Map<String, MetricEngine.Outcome> results) {}

  private List<DirectoryDefinition> readable(SessionUser user) {
    return store.snapshot().definitions.stream()
        .filter(d -> entries.access(user, d).atLeast(AccessLevel.READ))
        .sorted(Comparator.comparing(DirectoryDefinition::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /** Directories the user can measure, with the attributes they may aggregate on. */
  @GetMapping("/sources")
  public List<SourceInfo> sources(@AuthenticationPrincipal SessionUser user) {
    List<SourceInfo> out = new ArrayList<>();
    for (DirectoryDefinition d : readable(user)) {
      try {
        out.add(new SourceInfo(d.id(), d.name(), d.slug(), d.icon(), d.description(), sources.attributeChoices(user, d)));
      } catch (ApiException e) {
        // A definition whose form is missing can't be measured; leave it out.
      }
    }
    return out;
  }

  /** Entry counts of every directory the user can read. */
  @GetMapping("/summary")
  public List<SummaryRow> summary(@AuthenticationPrincipal SessionUser user, @RequestParam(defaultValue = "false") boolean refresh) {
    List<DirectoryDefinition> defs = readable(user);
    List<MetricWidget> widgets = defs.stream()
        .map(d -> new MetricWidget(d.id(), d.name(), null, d.id(), null, "count", null, null, null, null, null, null, null, null, null,
            null, null, null, null, "stat", null))
        .toList();
    Map<String, MetricEngine.Outcome> results = widgets.isEmpty() ? Map.of() : engine.evaluate(user, widgets, refresh);
    return defs.stream().map(d -> {
      MetricEngine.Outcome o = results.get(d.id());
      MetricResult r = o == null ? null : o.result();
      return new SummaryRow(d.id(), d.name(), d.slug(), d.icon(), r == null ? null : r.total(), r != null && r.truncated(),
          o == null ? "Not computed" : o.error());
    }).toList();
  }

  /** The out-of-the-box dashboard of one directory. Charts with nothing to show are left out. */
  @GetMapping("/overview/{id}")
  public OverviewResponse overview(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean refresh) {
    DirectoryDefinition def = entries.definition(id);
    entries.require(user, def, AccessLevel.READ);
    List<MetricWidget> all = OverviewGenerator.widgets(def, entries.form(def));
    Map<String, MetricEngine.Outcome> results = engine.evaluate(user, all, refresh);
    List<MetricWidget> shown = all.stream().filter(w -> {
      MetricEngine.Outcome o = results.get(w.id());
      if (o == null || o.result() == null) return o != null && o.forbidden() == null;
      if ("count".equals(w.kind()) || "completeness".equals(w.kind())) return true;
      MetricResult r = o.result();
      if (r.rows() == null || r.rows().isEmpty()) return false;
      // A breakdown where nothing has a value says nothing beyond "(none)".
      return r.rows().stream().anyMatch(row -> !Boolean.TRUE.equals(row.missing()) && row.value() > 0);
    }).toList();
    Map<String, MetricEngine.Outcome> shownResults = new LinkedHashMap<>();
    shown.forEach(w -> shownResults.put(w.id(), results.get(w.id())));
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("id", def.id());
    summary.put("name", def.name());
    summary.put("slug", def.slug());
    if (def.icon() != null) summary.put("icon", def.icon());
    if (def.description() != null) summary.put("description", def.description());
    return new OverviewResponse(summary, shown, shownResults);
  }

  /** Compute ad-hoc widgets (the explorer and the dashboard editor's preview). */
  @PostMapping("/evaluate")
  public Map<String, MetricEngine.Outcome> evaluate(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody EvaluateRequest body) {
    return engine.evaluate(user, body.widgets(), Boolean.TRUE.equals(body.refresh()));
  }

  private static boolean visible(SessionUser user, Dashboard d) {
    return d.shared() || user.isAdmin();
  }

  private Dashboard dashboard(SessionUser user, String id) {
    return store.snapshot().dashboards.stream()
        .filter(d -> d.id().equals(id) && visible(user, d))
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Dashboard not found"));
  }

  @GetMapping("/dashboards")
  public List<DashboardSummary> dashboards(@AuthenticationPrincipal SessionUser user) {
    return store.snapshot().dashboards.stream()
        .filter(d -> visible(user, d))
        .sorted(Comparator.comparing(Dashboard::name, String.CASE_INSENSITIVE_ORDER))
        .map(d -> new DashboardSummary(d.id(), d.name(), d.description(), d.shared(), d.widgets().size(), d.updatedAt()))
        .toList();
  }

  @GetMapping("/dashboards/{id}")
  public Dashboard dashboardById(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    return dashboard(user, id);
  }

  @GetMapping("/dashboards/{id}/results")
  public DashboardResults dashboardResults(@AuthenticationPrincipal SessionUser user, @PathVariable String id,
      @RequestParam(defaultValue = "false") boolean refresh) {
    Dashboard d = dashboard(user, id);
    return new DashboardResults(d.widgets().isEmpty() ? Map.of() : engine.evaluate(user, d.widgets(), refresh));
  }
}
