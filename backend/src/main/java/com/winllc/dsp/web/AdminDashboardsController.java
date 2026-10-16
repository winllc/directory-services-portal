package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.RequiresFeature;
import com.winllc.dsp.metrics.Dashboard;
import com.winllc.dsp.metrics.MetricWidget;
import com.winllc.dsp.store.ConfigStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Create, change and delete metric dashboards (viewing is in {@link MetricsController}). */
@RestController
@RequiresFeature(LicensedFeature.METRICS)
@RequestMapping("/api/admin/dashboards")
public class AdminDashboardsController {
  private final ConfigStore store;
  private final AuditLog audit;

  public AdminDashboardsController(ConfigStore store, AuditLog audit) {
    this.store = store;
    this.audit = audit;
  }

  public record DashboardInput(
      @NotBlank @Size(max = 128) String name,
      @Size(max = 1000) String description,
      Boolean shared,
      @NotNull @Size(max = MetricsController.MAX_WIDGETS) List<@Valid MetricWidget> widgets) {}

  private List<MetricWidget> widgets(DashboardInput in) {
    Set<String> known = new HashSet<>();
    store.snapshot().definitions.forEach(d -> known.add(d.id()));
    Set<String> ids = new HashSet<>();
    List<MetricWidget> out = new ArrayList<>();
    for (int i = 0; i < in.widgets().size(); i++) {
      MetricWidget w = in.widgets().get(i);
      if (!known.contains(w.definitionId())) {
        throw ApiException.badRequest("Chart \"" + w.title() + "\" uses an unknown directory", Map.of("widgets[" + i + "].definitionId", "Unknown directory"));
      }
      String id = w.id() == null || w.id().isBlank() || !ids.add(w.id()) ? "w-" + UUID.randomUUID().toString().substring(0, 8) : w.id();
      ids.add(id);
      out.add(w.withId(id));
    }
    return out;
  }

  private AuditLog.Draft event(String action, Dashboard before, Dashboard after) {
    Dashboard d = after != null ? after : before;
    return audit.event(action).target("dashboard", d.id(), d.name()).changes(audit.diff(before, after, Set.of("id", "createdAt", "updatedAt")));
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  @PostMapping
  public ResponseEntity<Dashboard> create(@Valid @RequestBody DashboardInput in) {
    String now = Instant.now().toString();
    Dashboard d = new Dashboard("dash-" + UUID.randomUUID(), in.name().trim(), blank(in.description()), Boolean.TRUE.equals(in.shared()), widgets(in), now, now);
    store.update(s -> s.dashboards.add(d));
    event("dashboard.create", null, d).success();
    return ResponseEntity.status(HttpStatus.CREATED).body(d);
  }

  @PutMapping("/{id}")
  public Dashboard update(@PathVariable String id, @Valid @RequestBody DashboardInput in) {
    List<MetricWidget> widgets = widgets(in);
    AtomicReference<Dashboard> previous = new AtomicReference<>();
    Dashboard saved = store.update(s -> {
      for (int i = 0; i < s.dashboards.size(); i++) {
        Dashboard cur = s.dashboards.get(i);
        if (cur.id().equals(id)) {
          previous.set(cur);
          Dashboard next = new Dashboard(id, in.name().trim(), blank(in.description()), Boolean.TRUE.equals(in.shared()), widgets, cur.createdAt(), Instant.now().toString());
          s.dashboards.set(i, next);
          return next;
        }
      }
      throw ApiException.notFound("Dashboard not found");
    });
    event("dashboard.update", previous.get(), saved).success();
    return saved;
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    Dashboard removed = store.update(s -> {
      Dashboard d = s.dashboards.stream().filter(x -> x.id().equals(id)).findFirst()
          .orElseThrow(() -> ApiException.notFound("Dashboard not found"));
      s.dashboards.remove(d);
      return d;
    });
    event("dashboard.delete", removed, null).success();
    return ResponseEntity.noContent().build();
  }
}
