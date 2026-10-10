package com.winllc.dsp.metrics;

import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.service.EntryService;
import java.util.ArrayList;
import java.util.List;

/**
 * The out-of-the-box dashboard for a directory, derived from its definition and form: entry
 * count, growth over time, a breakdown for every drop-down field, e-mail domains, containers,
 * how completely each field is filled in and how many values multi-valued links hold.
 */
public final class OverviewGenerator {
  private static final int MAX_BREAKDOWNS = 8;

  private OverviewGenerator() {}

  public static List<MetricWidget> widgets(DirectoryDefinition def, FormDefinition form) {
    List<MetricWidget> out = new ArrayList<>();
    String d = def.id();
    out.add(widget("ovw-count", "Entries", "All entries in " + def.name(), d, "count", null, null, null, null, null, "stat", "third"));
    out.add(widget("ovw-created", "New entries per month", "Entries by creation month (createTimestamp)", d, "timeline",
        "createTimestamp", null, null, "month", null, "column", "two-thirds"));

    int breakdowns = 0;
    for (FormField f : form.fields()) {
      if (breakdowns >= MAX_BREAKDOWNS) break;
      if (EntryService.isHidden(f.attribute()) || LdapDirectory.isBinary(f.attribute())) continue;
      if (f.dropdownWidget()) {
        boolean few = f.dropdown() instanceof DropdownSource.Static s && s.options().size() <= 6 && !f.multiValued();
        out.add(widget("ovw-dist-" + f.id(), f.label(), "Entries per " + f.label().toLowerCase(), d, "distribution", f.attribute(),
            null, 10, null, null, few ? "donut" : "bar", "third"));
        breakdowns++;
      } else if ("email".equals(f.format())) {
        out.add(widget("ovw-domain-" + f.id(), f.label() + " domains", "Entries per e-mail domain", d, "distribution", f.attribute(),
            "emailDomain", 10, null, null, "bar", "third"));
        breakdowns++;
      }
    }
    if (!"one".equals(def.scope())) {
      out.add(widget("ovw-containers", "Containers", "Entries per parent entry", d, "distribution", "dn", "dnParent", 10, null, null,
          "bar", "half"));
    }
    for (FormField f : form.fields()) {
      boolean link = "dn".equals(f.format()) || f.dropdown() instanceof DropdownSource.Ldap;
      if (f.multiValued() && link) {
        out.add(widget("ovw-count-" + f.id(), f.label() + " per entry", "How many " + f.label().toLowerCase() + " each entry has", d,
            "numeric", f.attribute(), null, null, null, "valueCount", "column", "half"));
        break;
      }
    }
    out.add(widget("ovw-complete", "Field completeness", "Share of entries with a value in each form field", d, "completeness", null,
        null, null, null, null, "bar", "full"));
    return out;
  }

  private static MetricWidget widget(String id, String title, String description, String definitionId, String kind, String attribute,
      String transform, Integer limit, String bucket, String measure, String chart, String width) {
    return new MetricWidget(id, title, description, definitionId, null, kind, attribute, transform, null, null, null, null, limit, null,
        null, bucket, null, measure, null, chart, width);
  }
}
