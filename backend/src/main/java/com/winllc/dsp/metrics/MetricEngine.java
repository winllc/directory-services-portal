package com.winllc.dsp.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.service.DataSources;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.EntryValues;
import com.winllc.dsp.util.SafeRegex;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Computes metric widgets. Widgets that read the same directory with the same filter share one
 * paged scan of it, each folding the stream into its own result, so a dashboard costs one pass
 * per directory rather than one per chart. Results are cached briefly (keyed by what determines
 * them, including the definition's and form's last change), after the access checks have passed.
 */
@Service
public class MetricEngine {
  private final DataSources sources;
  private final EntryService entries;
  private final SchemaService schema;
  private final int maxEntries;
  private final long cacheMillis;
  private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
      return size() > 500;
    }
  };

  private record Cached(long at, MetricResult result) {}

  /** A widget's result, or why it could not be computed. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Outcome(MetricResult result, String error, Map<String, String> details, Boolean forbidden) {
    static Outcome ok(MetricResult r) {
      return new Outcome(r, null, null, null);
    }

    static Outcome failed(RuntimeException e) {
      if (e instanceof ApiException a) {
        return new Outcome(null, a.getMessage(), a.details(), a.status().value() == 403 ? true : null);
      }
      return new Outcome(null, e.getMessage(), null, null);
    }
  }

  public MetricEngine(DataSources sources, EntryService entries, SchemaService schema, PortalProperties props) {
    this.sources = sources;
    this.entries = entries;
    this.schema = schema;
    this.maxEntries = Math.max(1, props.getMetrics().getMaxEntries());
    this.cacheMillis = Math.max(0, props.getMetrics().getCacheSeconds()) * 1000L;
  }

  public int maxEntries() {
    return maxEntries;
  }

  /** Results keyed by widget id (widgets without one are keyed by position: "0", "1", ...). */
  public Map<String, Outcome> evaluate(SessionUser user, List<MetricWidget> widgets, boolean refresh) {
    Map<String, Outcome> out = new LinkedHashMap<>();
    Map<String, List<Integer>> groups = new LinkedHashMap<>();
    for (int i = 0; i < widgets.size(); i++) {
      MetricWidget w = widgets.get(i);
      String filter = w.filter() == null ? "" : w.filter().trim();
      groups.computeIfAbsent(w.definitionId() + "\u0000" + filter, k -> new ArrayList<>()).add(i);
    }
    String[] ids = new String[widgets.size()];
    for (int i = 0; i < widgets.size(); i++) ids[i] = widgets.get(i).id() == null ? String.valueOf(i) : widgets.get(i).id();
    for (int i = 0; i < widgets.size(); i++) out.put(ids[i], null);

    for (List<Integer> group : groups.values()) {
      MetricWidget first = widgets.get(group.get(0));
      DataSources.Source source;
      try {
        source = sources.resolve(user, first.definitionId(), first.filter(), "filter");
      } catch (RuntimeException e) {
        for (int i : group) out.put(ids[i], Outcome.failed(e));
        continue;
      }
      String version = source.definition().updatedAt() + "/" + source.form().updatedAt();
      EntryValues values = new EntryValues(schema.mergedIndex());
      Map<Integer, Aggregator> pending = new LinkedHashMap<>();
      Map<Integer, String> keys = new HashMap<>();
      for (int i : group) {
        MetricWidget w = widgets.get(i);
        try {
          Aggregator agg = build(source, w, values);
          String key = w.computeKey() + "@" + version;
          Cached hit = refresh ? null : cached(key);
          if (hit != null) {
            out.put(ids[i], Outcome.ok(hit.result()));
          } else {
            pending.put(i, agg);
            keys.put(i, key);
          }
        } catch (RuntimeException e) {
          out.put(ids[i], Outcome.failed(e));
        }
      }
      if (pending.isEmpty()) continue;
      Set<String> attrs = new LinkedHashSet<>();
      pending.values().forEach(a -> attrs.addAll(a.attributes()));
      // A widget that fails mid-scan (e.g. a regex over its time budget) drops out; the others carry on.
      Map<Integer, RuntimeException> failed = new HashMap<>();
      try {
        LdapDirectory.ScanResult scan = sources.scan(source, attrs, maxEntries, e -> {
          for (Map.Entry<Integer, Aggregator> p : pending.entrySet()) {
            if (failed.containsKey(p.getKey())) continue;
            try {
              p.getValue().accept(e);
            } catch (RuntimeException ex) {
              failed.put(p.getKey(), ex);
            }
          }
        });
        for (Map.Entry<Integer, Aggregator> p : pending.entrySet()) {
          RuntimeException ex = failed.get(p.getKey());
          if (ex != null) {
            out.put(ids[p.getKey()], ex instanceof SafeRegex.TimeoutException
                ? new Outcome(null, ex.getMessage(), Map.of("transformArg", ex.getMessage()), null)
                : Outcome.failed(ex));
            continue;
          }
          MetricResult r = p.getValue().finish(scan.truncated());
          if (scan.truncated()) {
            List<String> warnings = new ArrayList<>(r.warnings() == null ? List.of() : r.warnings());
            warnings.add(0, "Only the first " + maxEntries + " entries were analysed");
            r = new MetricResult(r.kind(), r.total(), true, r.computedAt(), r.rows(), r.series(), r.matrix(), r.distinctValues(), r.stats(), warnings);
          }
          store(keys.get(p.getKey()), r);
          out.put(ids[p.getKey()], Outcome.ok(r));
        }
      } catch (DirectoryException | ApiException e) {
        for (int i : pending.keySet()) out.put(ids[i], Outcome.failed(e));
      }
    }
    return out;
  }

  /** Checks the widget against the source and builds its aggregator; throws a 400 naming the bad field. */
  Aggregator build(DataSources.Source source, MetricWidget w, EntryValues values) {
    switch (w.kind()) {
      case "count" -> {
        return new Aggregator.Count(w, values);
      }
      case "distribution" -> {
        sources.requireAttribute(source, w.attribute(), "attribute");
        ValueTransform t = transform(w.transform(), w.transformArg(), "transform");
        ValueTransform split = null;
        if (w.splitBy() != null && !w.splitBy().isBlank()) {
          sources.requireAttribute(source, w.splitBy(), "splitBy");
          split = transform(w.splitTransform(), null, "splitTransform");
        }
        Map<String, String> labels = "none".equals(w.transformOrNone()) ? optionLabels(source, w.attribute()) : Map.of();
        Map<String, String> splitLabels = split != null && (w.splitTransform() == null || "none".equals(w.splitTransform()))
            ? optionLabels(source, w.splitBy()) : Map.of();
        return new Aggregator.Distribution(w, values, t, split, labels, splitLabels);
      }
      case "completeness" -> {
        List<String> attrs = w.attributes() == null || w.attributes().isEmpty()
            ? source.form().fields().stream().map(FormField::attribute).filter(a -> !EntryService.isHidden(a) && !LdapDirectory.isBinary(a)).toList()
            : w.attributes();
        if (attrs.isEmpty()) throw ApiException.badRequest("Choose at least one attribute", Map.of("attributes", "Required"));
        for (String a : attrs) sources.requireAttribute(source, a, "attributes");
        Map<String, String> labels = new HashMap<>();
        source.form().fields().forEach(f -> labels.putIfAbsent(f.attribute().toLowerCase(), f.label()));
        return new Aggregator.Completeness(w, values, attrs, labels);
      }
      case "timeline" -> {
        sources.requireAttribute(source, w.attribute(), "attribute");
        return new Aggregator.Timeline(w, values);
      }
      case "numeric" -> {
        sources.requireAttribute(source, w.attribute(), "attribute");
        return new Aggregator.Numeric(w, values);
      }
      default -> throw ApiException.badRequest("Unknown metric kind", Map.of("kind", "Unknown metric kind"));
    }
  }

  private static ValueTransform transform(String type, String arg, String field) {
    try {
      return ValueTransform.of(type, arg);
    } catch (IllegalArgumentException e) {
      throw ApiException.badRequest(e.getMessage(), Map.of("transform".equals(field) ? "transformArg" : field, e.getMessage()));
    }
  }

  /** Display labels of drop-down options for the attribute (value -> label), keyed like distribution buckets. */
  private Map<String, String> optionLabels(DataSources.Source source, String attribute) {
    for (FormField f : source.form().fields()) {
      if (!f.attribute().equalsIgnoreCase(attribute) || !f.dropdownWidget() || f.dropdown() == null) continue;
      try {
        List<OptionItem> options = f.dropdown() instanceof DropdownSource.Static s ? s.options() : entries.fieldOptions(f);
        Map<String, String> labels = new HashMap<>();
        for (OptionItem o : options) {
          if (o.label() != null && !o.label().isBlank()) labels.putIfAbsent(o.value().trim().toLowerCase(), o.label());
        }
        return labels;
      } catch (RuntimeException e) {
        return Map.of();
      }
    }
    return Map.of();
  }

  private synchronized Cached cached(String key) {
    Cached c = cache.get(key);
    if (c == null || System.currentTimeMillis() - c.at() > cacheMillis) return null;
    return c;
  }

  private synchronized void store(String key, MetricResult result) {
    if (cacheMillis > 0) cache.put(key, new Cached(System.currentTimeMillis(), result));
  }

  /** Forget cached results (after configuration changes that alter what a widget means). */
  public synchronized void clearCache() {
    cache.clear();
  }
}
