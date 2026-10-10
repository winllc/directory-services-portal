package com.winllc.dsp.analysis;

import com.winllc.dsp.analysis.AnalysisDefinition.AttributeMapping;
import com.winllc.dsp.analysis.AnalysisDefinition.CompareOptions;
import com.winllc.dsp.analysis.AnalysisDefinition.Rule;
import com.winllc.dsp.analysis.AnalysisDefinition.Source;
import com.winllc.dsp.analysis.AnalysisResult.AttributeStats;
import com.winllc.dsp.analysis.AnalysisResult.CompareRow;
import com.winllc.dsp.analysis.AnalysisResult.CompareSummary;
import com.winllc.dsp.analysis.AnalysisResult.Difference;
import com.winllc.dsp.analysis.AnalysisResult.Issue;
import com.winllc.dsp.analysis.AnalysisResult.QualitySummary;
import com.winllc.dsp.analysis.AnalysisResult.RuleStats;
import com.winllc.dsp.analysis.AnalysisResult.SourceStats;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.jobs.JobContext;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.metrics.ValueTransform;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.service.DataSources;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.EntryValues;
import com.winllc.dsp.service.FormValidator;
import com.winllc.dsp.util.SafeRegex;
import com.winllc.dsp.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Runs comparisons and data-quality checks. Each source is read once with a paged scan (see
 * {@link DataSources}); counts always cover every entry read, while the lists of individual
 * findings are capped at {@code portal.metrics.max-issues}.
 */
@Service
public class AnalysisService {
  /** Most distinct DNs looked up by one run's {@code dnExists} rules. */
  static final int MAX_DN_LOOKUPS = 5_000;
  private static final int MAX_VALUES_SHOWN = 10;
  private static final int MAX_VALUE_LENGTH = 200;

  private final DataSources sources;
  private final EntryService entries;
  private final SchemaService schema;
  private final LdapDirectory directory;
  private final int maxEntries;
  private final int maxIssues;

  public AnalysisService(DataSources sources, EntryService entries, SchemaService schema, LdapDirectory directory, PortalProperties props) {
    this.sources = sources;
    this.entries = entries;
    this.schema = schema;
    this.directory = directory;
    this.maxEntries = Math.max(1, props.getMetrics().getMaxEntries());
    this.maxIssues = Math.max(1, props.getMetrics().getMaxIssues());
  }

  /** Reject definitions that can't run; field names match the editor ({@code sources[1].keyAttribute}, ...). */
  public static void check(AnalysisDefinition a) {
    List<Source> src = a.sources() == null ? List.of() : a.sources();
    if ("compare".equals(a.type())) {
      if (src.size() < 2) throw ApiException.badRequest("A comparison needs at least two sources", Map.of("sources", "Add at least two sources"));
      for (int i = 0; i < src.size(); i++) {
        if (src.get(i).keyAttribute() == null || src.get(i).keyAttribute().isBlank()) {
          throw ApiException.badRequest("Every source needs a key attribute", Map.of("sources[" + i + "].keyAttribute", "Required"));
        }
      }
      List<AttributeMapping> maps = a.compare() == null || a.compare().attributes() == null ? List.of() : a.compare().attributes();
      for (int i = 0; i < maps.size(); i++) {
        int n = maps.get(i).attributes().size();
        if (n != 1 && n != src.size()) {
          throw ApiException.badRequest("Give one attribute for all sources, or one per source",
              Map.of("compare.attributes[" + i + "]", "Give one attribute, or one per source"));
        }
      }
    } else {
      if (src.isEmpty()) throw ApiException.badRequest("Add at least one source", Map.of("sources", "Add at least one source"));
      boolean formRules = a.quality() != null && a.quality().formRules();
      List<Rule> rules = a.quality() == null || a.quality().rules() == null ? List.of() : a.quality().rules();
      if (!formRules && rules.isEmpty()) {
        throw ApiException.badRequest("Add a rule or use the form rules", Map.of("quality.rules", "Add a rule or use the form rules"));
      }
      for (int i = 0; i < rules.size(); i++) checkRule(rules.get(i), "quality.rules[" + i + "]");
    }
  }

  private static void checkRule(Rule r, String field) {
    switch (r.type()) {
      case "pattern" -> {
        try {
          SafeRegex.compile(r.pattern(), true, r.caseInsensitive());
        } catch (IllegalArgumentException e) {
          throw ApiException.badRequest(e.getMessage(), Map.of(field + ".pattern", e.getMessage()));
        }
      }
      case "format" -> {
        if (r.format() == null) throw ApiException.badRequest("Choose a format", Map.of(field + ".format", "Required"));
      }
      case "allowedValues" -> {
        if (r.values() == null || r.values().isEmpty()) throw ApiException.badRequest("List the allowed values", Map.of(field + ".values", "Required"));
      }
      case "length" -> {
        if (r.min() == null && r.max() == null) throw ApiException.badRequest("Set a minimum or maximum length", Map.of(field + ".min", "Required"));
        if (r.min() != null && r.max() != null && r.min() > r.max()) {
          throw ApiException.badRequest("The minimum is above the maximum", Map.of(field + ".min", "Must not exceed the maximum"));
        }
      }
      default -> {}
    }
  }

  public AnalysisResult run(SessionUser user, AnalysisDefinition a) {
    check(a);
    long started = System.currentTimeMillis();
    List<DataSources.Source> resolved = new ArrayList<>();
    for (int i = 0; i < a.sources().size(); i++) {
      Source s = a.sources().get(i);
      resolved.add(sources.resolve(user, s.definitionId(), s.filter(), "sources[" + i + "].filter"));
    }
    List<String> labels = labels(a.sources(), resolved);
    EntryValues values = new EntryValues(schema.mergedIndex());
    if ("compare".equals(a.type())) {
      AnalysisResult.Compare c = compare(a, resolved, labels, values);
      return new AnalysisResult("compare", Instant.now().toString(), System.currentTimeMillis() - started, c, null, null);
    }
    List<String> warnings = new ArrayList<>();
    AnalysisResult.Quality q = quality(a, resolved, labels, values, warnings);
    return new AnalysisResult("quality", Instant.now().toString(), System.currentTimeMillis() - started, null, q,
        warnings.isEmpty() ? null : warnings);
  }

  /** Source labels, made distinct when the same directory appears twice. */
  private static List<String> labels(List<Source> in, List<DataSources.Source> resolved) {
    List<String> out = new ArrayList<>();
    Map<String, Integer> seen = new HashMap<>();
    for (int i = 0; i < in.size(); i++) {
      String l = in.get(i).label() != null && !in.get(i).label().isBlank() ? in.get(i).label().trim() : resolved.get(i).label();
      int n = seen.merge(l.toLowerCase(Locale.ROOT), 1, Integer::sum);
      out.add(n == 1 ? l : l + " (" + n + ")");
    }
    return out;
  }

  private static List<String> shown(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      if (out.size() == MAX_VALUES_SHOWN) {
        out.add("… " + (values.size() - MAX_VALUES_SHOWN) + " more");
        break;
      }
      out.add(v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) + "…" : v);
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Comparison
  // ---------------------------------------------------------------------------

  /** One entry as seen by a comparison: its DN and the raw values of each compared attribute. */
  private record Projection(String dn, List<List<String>> values) {}

  private static String normalize(String v, CompareOptions o) {
    String s = v.trim();
    if (o.normalizeWhitespace()) s = s.replaceAll("\\s+", " ");
    // DNs compare by their normalized form: "uid=Bob, ou=People" equals "uid=bob,ou=people".
    if (s.indexOf('=') > 0 && Dns.isValid(s)) return Dns.parse(s).toNormalizedString();
    return o.ignoreCase() ? s.toLowerCase(Locale.ROOT) : s;
  }

  private static List<String> normalized(List<String> values, CompareOptions o) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      String n = normalize(v, o);
      if (!n.isEmpty()) out.add(n);
    }
    if (o.ignoreOrder()) {
      out = new ArrayList<>(new java.util.TreeSet<>(out));
    }
    return out;
  }

  private AnalysisResult.Compare compare(AnalysisDefinition a, List<DataSources.Source> resolved, List<String> labels, EntryValues values) {
    CompareOptions o = a.compare() != null ? a.compare() : new CompareOptions(List.of(), null, null, null, null);
    List<AttributeMapping> maps = o.attributes() == null ? List.of() : o.attributes();
    int n = resolved.size();
    // attrs[m][s]: attribute compared by mapping m in source s.
    String[][] attrs = new String[maps.size()][n];
    for (int m = 0; m < maps.size(); m++) {
      for (int s = 0; s < n; s++) {
        List<String> names = maps.get(m).attributes();
        attrs[m][s] = names.size() == 1 ? names.get(0) : names.get(s);
        sources.requireAttribute(resolved.get(s), attrs[m][s], "compare.attributes[" + m + "]");
      }
    }
    for (int s = 0; s < n; s++) sources.requireAttribute(resolved.get(s), a.sources().get(s).keyAttribute(), "sources[" + s + "].keyAttribute");

    List<Map<String, List<Projection>>> byKey = new ArrayList<>();
    List<SourceStats> stats = new ArrayList<>();
    for (int s = 0; s < n; s++) {
      String keyAttr = a.sources().get(s).keyAttribute();
      Set<String> wanted = new LinkedHashSet<>();
      wanted.add(keyAttr);
      for (String[] row : attrs) wanted.add(row[s]);
      Map<String, List<Projection>> keyed = new HashMap<>();
      long[] missingKey = {0};
      final int src = s;
      LdapDirectory.ScanResult scan = sources.scan(resolved.get(s), wanted, maxEntries, e -> {
        List<String> keys = values.get(e, keyAttr);
        if (keys.isEmpty() || keys.get(0).isBlank()) {
          missingKey[0]++;
          return;
        }
        String key = keys.get(0).trim();
        if (key.indexOf('=') > 0 && Dns.isValid(key)) key = Dns.parse(key).toNormalizedString();
        else if (o.keyIgnoreCase()) key = key.toLowerCase(Locale.ROOT);
        List<List<String>> vals = new ArrayList<>();
        for (String[] row : attrs) vals.add(values.get(e, row[src]));
        keyed.computeIfAbsent(key, k -> new ArrayList<>(1)).add(new Projection(e.dn(), vals));
      });
      long dup = keyed.values().stream().filter(l -> l.size() > 1).count();
      stats.add(new SourceStats(labels.get(s), resolved.get(s).definition().id(), resolved.get(s).definition().name(), scan.entries(),
          scan.truncated(), (long) keyed.size(), missingKey[0], dup));
      byKey.add(keyed);
    }

    JobContext.current().phase("Comparing", null);
    TreeMap<String, Boolean> allKeys = new TreeMap<>();
    byKey.forEach(m -> m.keySet().forEach(k -> allKeys.put(k, true)));
    long inAll = 0;
    long identical = 0;
    long different = 0;
    long partial = 0;
    long duplicates = 0;
    long[] onlyIn = new long[n];
    long[] attrDiffs = new long[maps.size()];
    List<CompareRow> rows = new ArrayList<>();
    long rowCount = 0;
    for (String key : allKeys.keySet()) {
      List<Boolean> present = new ArrayList<>();
      List<List<String>> dns = new ArrayList<>();
      int count = 0;
      int last = -1;
      boolean dup = false;
      for (int s = 0; s < n; s++) {
        List<Projection> p = byKey.get(s).get(key);
        present.add(p != null);
        dns.add(p == null ? null : p.stream().map(Projection::dn).limit(MAX_VALUES_SHOWN).toList());
        if (p != null) {
          count++;
          last = s;
          if (p.size() > 1) dup = true;
        }
      }
      if (dup) duplicates++;
      String status;
      List<Difference> diffs = null;
      if (count < n) {
        partial++;
        if (count == 1) onlyIn[last]++;
        status = "missing";
      } else {
        inAll++;
        diffs = new ArrayList<>();
        for (int m = 0; m < maps.size(); m++) {
          List<String> first = null;
          boolean same = true;
          List<List<String>> raw = new ArrayList<>();
          for (int s = 0; s < n; s++) {
            List<String> v = byKey.get(s).get(key).get(0).values().get(m);
            raw.add(shown(v));
            List<String> norm = normalized(v, o);
            if (first == null) first = norm;
            else if (!first.equals(norm)) same = false;
          }
          if (!same) {
            attrDiffs[m]++;
            String label = maps.get(m).label() != null && !maps.get(m).label().isBlank() ? maps.get(m).label() : String.join(" / ", new LinkedHashSet<>(List.of(attrs[m])));
            diffs.add(new Difference(label, raw));
          }
        }
        if (diffs.isEmpty()) {
          identical++;
          status = dup ? "duplicate" : "identical";
        } else {
          different++;
          status = "different";
        }
      }
      if ("identical".equals(status)) continue;
      rowCount++;
      if (rows.size() < maxIssues) {
        // Values of every compared attribute, so the row shows what is (or isn't) there.
        if ("missing".equals(status) && !maps.isEmpty()) {
          diffs = new ArrayList<>();
          for (int m = 0; m < maps.size(); m++) {
            List<List<String>> raw = new ArrayList<>();
            for (int s = 0; s < n; s++) {
              List<Projection> p = byKey.get(s).get(key);
              raw.add(p == null ? null : shown(p.get(0).values().get(m)));
            }
            String label = maps.get(m).label() != null && !maps.get(m).label().isBlank() ? maps.get(m).label() : attrs[m][0];
            diffs.add(new Difference(label, raw));
          }
        }
        rows.add(new CompareRow(key, status, dup ? true : null, present, dns, diffs == null || diffs.isEmpty() ? null : diffs));
      }
    }
    List<AttributeStats> attributeStats = new ArrayList<>();
    for (int m = 0; m < maps.size(); m++) {
      String label = maps.get(m).label() != null && !maps.get(m).label().isBlank() ? maps.get(m).label() : String.join(" / ", new LinkedHashSet<>(List.of(attrs[m])));
      attributeStats.add(new AttributeStats(label, List.of(attrs[m]), attrDiffs[m]));
    }
    List<Long> only = new ArrayList<>();
    for (long v : onlyIn) only.add(v);
    return new AnalysisResult.Compare(stats, new CompareSummary(allKeys.size(), inAll, identical, different, partial, only, duplicates),
        attributeStats, rows, rowCount, rowCount > rows.size());
  }

  // ---------------------------------------------------------------------------
  // Quality
  // ---------------------------------------------------------------------------

  /** A rule ready to evaluate: either an explicit rule or one form field's rules in one source. */
  private record Check(String id, String label, String attribute, String type, String severity, Integer source, Rule rule,
      FormField field, List<OptionItem> options, SafeRegex regex, Set<String> allowed) {}

  /** An entry that holds a value, remembered for cross-entry rules (unique, dnExists). */
  private record Holder(int source, String dn, String title, String value) {}

  private List<Check> checks(AnalysisDefinition a, List<DataSources.Source> resolved, List<String> warnings) {
    List<Check> out = new ArrayList<>();
    List<Rule> rules = a.quality() == null || a.quality().rules() == null ? List.of() : a.quality().rules();
    for (int i = 0; i < rules.size(); i++) {
      Rule r = rules.get(i);
      String id = r.id() != null && !r.id().isBlank() ? r.id() : "rule-" + (i + 1);
      for (int s = 0; s < resolved.size(); s++) sources.requireAttribute(resolved.get(s), r.attribute(), "quality.rules[" + i + "].attribute");
      SafeRegex regex = "pattern".equals(r.type()) ? SafeRegex.compile(r.pattern(), true, r.caseInsensitive()) : null;
      Set<String> allowed = null;
      if ("allowedValues".equals(r.type())) {
        allowed = new LinkedHashSet<>();
        for (String v : r.values()) allowed.add(r.caseInsensitive() ? v.trim().toLowerCase(Locale.ROOT) : v.trim());
      }
      out.add(new Check(id, r.description() != null && !r.description().isBlank() ? r.description() : describe(r), r.attribute(), r.type(),
          r.severityOrDefault(), null, r, null, null, regex, allowed));
    }
    if (a.quality() != null && a.quality().formRules()) {
      for (int s = 0; s < resolved.size(); s++) {
        for (FormField f : resolved.get(s).form().fields()) {
          if (EntryService.isHidden(f.attribute()) || LdapDirectory.isBinary(f.attribute())) continue;
          List<OptionItem> options = null;
          if (f.dropdownWidget() && f.dropdown() instanceof DropdownSource.Ldap && !f.allowsCustomValues()) {
            try {
              options = entries.fieldOptions(f);
            } catch (RuntimeException e) {
              warnings.add("Could not load the options of \"" + f.label() + "\": " + e.getMessage());
              continue;
            }
          }
          out.add(new Check("form:" + resolved.get(s).definition().id() + ":" + f.id(), f.label() + " (form rules)", f.attribute(), "form",
              "error", s, null, f, options, null, null));
        }
      }
    }
    return out;
  }

  static String describe(Rule r) {
    return switch (r.type()) {
      case "required" -> r.attribute() + " is required";
      case "singleValue" -> r.attribute() + " has a single value";
      case "unique" -> r.attribute() + " is unique" + ("all".equals(r.scope()) ? " across all sources" : "");
      case "pattern" -> r.attribute() + " matches " + r.pattern();
      case "format" -> r.attribute() + " is a valid " + r.format();
      case "allowedValues" -> r.attribute() + " is one of " + String.join(", ", r.values().stream().limit(5).toList())
          + (r.values().size() > 5 ? ", …" : "");
      case "length" -> r.attribute() + " has " + (r.min() != null ? r.min() : 0) + "–" + (r.max() != null ? r.max() : "∞") + " characters";
      case "dnExists" -> r.attribute() + " refers to existing entries";
      default -> r.attribute();
    };
  }

  private AnalysisResult.Quality quality(AnalysisDefinition a, List<DataSources.Source> resolved, List<String> labels, EntryValues values,
      List<String> warnings) {
    List<Check> checks = checks(a, resolved, warnings);
    long[] violations = new long[checks.size()];
    List<Issue> issues = new ArrayList<>();
    long[] issueCount = {0};
    long[] severityCount = new long[2];
    // Entries with at least one finding, by "source\u0000dn".
    Set<String> flagged = new java.util.HashSet<>();
    // Cross-entry rules collect holders per normalized value and are decided after all scans.
    Map<Integer, Map<String, List<Holder>>> uniques = new HashMap<>();
    Map<Integer, Map<String, List<Holder>>> references = new HashMap<>();
    List<SourceStats> stats = new ArrayList<>();
    long entriesTotal = 0;

    IssueSink sink = (check, idx, source, dn, title, attribute, vals, message) -> {
      violations[idx]++;
      issueCount[0]++;
      severityCount["warning".equals(check.severity()) ? 1 : 0]++;
      flagged.add(source + "\u0000" + dn);
      if (issues.size() < maxIssues) issues.add(new Issue(check.id(), source, dn, title, attribute, shown(vals), message, check.severity()));
    };

    for (int s = 0; s < resolved.size(); s++) {
      DataSources.Source src = resolved.get(s);
      String titleAttr = src.definition().titleAttribute();
      Set<String> wanted = new LinkedHashSet<>();
      wanted.add(titleAttr);
      for (Check c : checks) if (c.source() == null || c.source() == s) wanted.add(c.attribute());
      final int sIdx = s;
      LdapDirectory.ScanResult scan = sources.scan(src, wanted, maxEntries, e -> {
        String title = values.get(e, titleAttr).stream().findFirst().orElse(Dns.rdnValue(e.dn()));
        for (int i = 0; i < checks.size(); i++) {
          Check c = checks.get(i);
          if (c.source() != null && c.source() != sIdx) continue;
          List<String> vals = values.get(e, c.attribute());
          evaluate(c, i, sIdx, e, title, vals, sink, uniques, references);
        }
      });
      entriesTotal += scan.entries();
      stats.add(new SourceStats(labels.get(s), src.definition().id(), src.definition().name(), scan.entries(), scan.truncated(), null, null, null));
      if (scan.truncated()) warnings.add("Only the first " + maxEntries + " entries of " + labels.get(s) + " were checked");
    }

    // unique: every holder of a value someone else also holds.
    for (Map.Entry<Integer, Map<String, List<Holder>>> u : uniques.entrySet()) {
      Check c = checks.get(u.getKey());
      boolean across = "all".equals(c.rule().scope());
      for (List<Holder> holders : u.getValue().values()) {
        if (holders.size() < 2) continue;
        for (Holder h : holders) {
          List<Holder> others = holders.stream().filter(o -> o != h && (across || o.source() == h.source())).toList();
          if (others.isEmpty()) continue;
          String names = String.join(", ", others.stream().limit(3).map(o -> across && o.source() != h.source() ? o.dn() + " (" + labels.get(o.source()) + ")" : o.dn()).toList());
          sink.add(c, u.getKey(), h.source(), h.dn(), h.title(), c.attribute(), List.of(h.value()),
              "\"" + h.value() + "\" is also used by " + names + (others.size() > 3 ? " and " + (others.size() - 3) + " more" : ""));
        }
      }
    }

    // dnExists: look each distinct DN up once.
    if (!references.isEmpty()) {
      JobContext job = JobContext.current();
      job.phase("Checking referenced entries", null);
      Map<String, Boolean> exists = new HashMap<>();
      boolean capped = false;
      for (Map<String, List<Holder>> m : references.values()) {
        for (String dn : m.keySet()) {
          if (exists.containsKey(dn)) continue;
          if (exists.size() >= MAX_DN_LOOKUPS) {
            capped = true;
            break;
          }
          exists.put(dn, directory.get(dn, List.of("1.1")) != null);
          job.checkCancelled();
        }
      }
      if (capped) warnings.add("Only the first " + MAX_DN_LOOKUPS + " referenced DNs were checked");
      for (Map.Entry<Integer, Map<String, List<Holder>>> r : references.entrySet()) {
        Check c = checks.get(r.getKey());
        for (Map.Entry<String, List<Holder>> e : r.getValue().entrySet()) {
          if (!Boolean.FALSE.equals(exists.get(e.getKey()))) continue;
          for (Holder h : e.getValue()) {
            sink.add(c, r.getKey(), h.source(), h.dn(), h.title(), c.attribute(), List.of(h.value()), "\"" + h.value() + "\" does not exist");
          }
        }
      }
    }

    List<RuleStats> ruleStats = new ArrayList<>();
    for (int i = 0; i < checks.size(); i++) {
      Check c = checks.get(i);
      ruleStats.add(new RuleStats(c.id(), c.label(), c.attribute(), c.type(), c.severity(), violations[i], c.source()));
    }
    issues.sort(java.util.Comparator.comparing(Issue::source).thenComparing(Issue::dn, String.CASE_INSENSITIVE_ORDER));
    long withIssues = flagged.size();
    QualitySummary summary = new QualitySummary(entriesTotal, withIssues, issueCount[0], severityCount[0], severityCount[1],
        entriesTotal == 0 ? 1.0 : (double) (entriesTotal - withIssues) / entriesTotal);
    return new AnalysisResult.Quality(stats, summary, ruleStats, issues, issueCount[0] > issues.size());
  }

  @FunctionalInterface
  private interface IssueSink {
    void add(Check check, int index, int source, String dn, String title, String attribute, List<String> values, String message);
  }

  private void evaluate(Check c, int idx, int source, DirectoryEntry e, String title, List<String> vals, IssueSink sink,
      Map<Integer, Map<String, List<Holder>>> uniques, Map<Integer, Map<String, List<Holder>>> references) {
    if (c.field() != null) {
      String message = FormValidator.validateField(c.field(), FormValidator.normalize(vals), c.options());
      if (message != null) sink.add(c, idx, source, e.dn(), title, c.attribute(), vals, message);
      return;
    }
    Rule r = c.rule();
    switch (r.type()) {
      case "required" -> {
        if (vals.isEmpty()) sink.add(c, idx, source, e.dn(), title, c.attribute(), vals, c.attribute() + " is missing");
      }
      case "singleValue" -> {
        if (vals.size() > 1) sink.add(c, idx, source, e.dn(), title, c.attribute(), vals, c.attribute() + " has " + vals.size() + " values");
      }
      case "unique" -> {
        for (String v : new LinkedHashSet<>(vals)) {
          String t = v.trim();
          if (t.isEmpty()) continue;
          String key = t.indexOf('=') > 0 && Dns.isValid(t) ? Dns.parse(t).toNormalizedString() : r.caseInsensitive() ? t.toLowerCase(Locale.ROOT) : t;
          uniques.computeIfAbsent(idx, k -> new HashMap<>()).computeIfAbsent(key, k -> new ArrayList<>(1)).add(new Holder(source, e.dn(), title, t));
        }
      }
      case "pattern" -> {
        List<String> bad = vals.stream().filter(v -> !c.regex().matches(v)).toList();
        if (!bad.isEmpty()) sink.add(c, idx, source, e.dn(), title, c.attribute(), bad, "Does not match " + r.pattern());
      }
      case "format" -> {
        List<String> bad = vals.stream().filter(v -> !validFormat(r.format(), v)).toList();
        if (!bad.isEmpty()) sink.add(c, idx, source, e.dn(), title, c.attribute(), bad, "Not a valid " + r.format());
      }
      case "allowedValues" -> {
        List<String> bad = vals.stream().filter(v -> !c.allowed().contains(r.caseInsensitive() ? v.trim().toLowerCase(Locale.ROOT) : v.trim())).toList();
        if (!bad.isEmpty()) sink.add(c, idx, source, e.dn(), title, c.attribute(), bad, "Not an allowed value");
      }
      case "length" -> {
        List<String> bad = vals.stream()
            .filter(v -> (r.min() != null && v.length() < r.min()) || (r.max() != null && v.length() > r.max()))
            .toList();
        if (!bad.isEmpty()) sink.add(c, idx, source, e.dn(), title, c.attribute(), bad, "Length outside " + (r.min() != null ? r.min() : 0)
            + "–" + (r.max() != null ? r.max() : "∞") + " characters");
      }
      case "dnExists" -> {
        for (String v : vals) {
          String t = v.trim();
          if (!Dns.isValid(t)) {
            sink.add(c, idx, source, e.dn(), title, c.attribute(), List.of(t), "\"" + t + "\" is not a valid DN");
            continue;
          }
          references.computeIfAbsent(idx, k -> new HashMap<>()).computeIfAbsent(Dns.parse(t).toNormalizedString(), k -> new ArrayList<>(1))
              .add(new Holder(source, e.dn(), title, t));
        }
      }
      default -> {}
    }
  }

  private static boolean validFormat(String format, String v) {
    return switch (format) {
      case "integer" -> v.trim().matches("^-?\\d+$");
      case "date" -> ValueTransform.parseDate(v) != null;
      default -> {
        FormField probe = new FormField("probe", "probe", "value", null, "text", true, false, false, false, format, null, null, null, null,
            null, null, null);
        yield FormValidator.validateField(probe, List.of(v.trim()), null) == null;
      }
    };
  }
}
