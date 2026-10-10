package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.schema.SchemaIndex;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * Bulk reads of a directory definition's entries for the metrics and analysis modules.
 *
 * <p>Every read goes through the same rules as browsing: the user needs read access to the
 * definition, the scan is confined to its namespace and filter, and password-like attributes are
 * never read. Administrators may aggregate on any other attribute; everyone else is limited to
 * the attributes the definition shows them (form fields, list columns, title) plus the entry
 * timestamps. Extra filters are held to the same attribute rules, so a filter can't be used to
 * probe values the user could not otherwise see.
 */
@Service
public class DataSources {
  /** Pseudo attribute: the entry's DN. */
  public static final String DN = "dn";
  /** Operational attributes any reader may aggregate on. */
  public static final List<String> TIMESTAMPS = List.of("createTimestamp", "modifyTimestamp");

  private final LdapDirectory directory;
  private final EntryService entries;
  private final SchemaService schema;

  public DataSources(LdapDirectory directory, EntryService entries, SchemaService schema) {
    this.directory = directory;
    this.entries = entries;
    this.schema = schema;
  }

  /** A definition (plus an optional extra filter) resolved for one user. */
  public record Source(DirectoryDefinition definition, FormDefinition form, Filter filter, SessionUser user) {
    public String label() {
      return definition.name();
    }
  }

  /** An attribute a user can pick, with where it comes from: form, list, operational, schema or dn. */
  public record AttributeChoice(String attribute, String label, String source, boolean multiValued, String widget, String format) {}

  public Source resolve(SessionUser user, String definitionId, String extraFilter, String field) {
    DirectoryDefinition def = entries.definition(definitionId);
    if (!entries.access(user, def).atLeast(AccessLevel.READ)) {
      throw ApiException.forbidden("You do not have access to the directory \"" + def.name() + "\"");
    }
    FormDefinition form = entries.form(def);
    Filter filter = entries.baseFilter(def, form);
    if (extraFilter != null && !extraFilter.isBlank()) {
      Filter extra;
      try {
        extra = Filter.create(extraFilter.trim());
      } catch (LDAPException e) {
        throw ApiException.badRequest("Invalid LDAP filter", Map.of(field, "Invalid LDAP filter: " + e.getMessage()));
      }
      for (String attr : filterAttributes(extra)) {
        String problem = attributeProblem(user, def, form, attr);
        if (problem != null) throw ApiException.badRequest("The filter uses an attribute you can't query", Map.of(field, problem));
      }
      filter = Filter.createANDFilter(filter, extra);
    }
    return new Source(def, form, filter, user);
  }

  /** Throws a 400 naming {@code field} unless the user may read {@code attribute} through the source. */
  public void requireAttribute(Source source, String attribute, String field) {
    String problem = attributeProblem(source.user(), source.definition(), source.form(), attribute);
    if (problem != null) throw ApiException.badRequest(problem, Map.of(field, problem));
  }

  /** Null when allowed, otherwise the reason. */
  public String attributeProblem(SessionUser user, DirectoryDefinition def, FormDefinition form, String attribute) {
    if (attribute == null || attribute.isBlank()) return "An attribute is required";
    if (DN.equalsIgnoreCase(attribute)) return null;
    if (!attribute.matches(Patterns.ATTRIBUTE)) return "\"" + attribute + "\" is not a valid attribute name";
    if (EntryService.isHidden(attribute)) return "\"" + attribute + "\" can't be used here";
    if (LdapDirectory.isBinary(attribute)) return "\"" + attribute + "\" holds binary data and can't be aggregated";
    if (user.isAdmin()) return null;
    if (TIMESTAMPS.stream().anyMatch(t -> t.equalsIgnoreCase(attribute))) return null;
    SchemaIndex index = schema.mergedIndex();
    String primary = index.primaryAttributeName(attribute);
    for (String a : EntryService.readableAttributes(def, form)) {
      if (a.equalsIgnoreCase(attribute) || index.primaryAttributeName(a).equalsIgnoreCase(primary)) return null;
    }
    return "\"" + attribute + "\" is not shown in \"" + def.name() + "\"";
  }

  private static Set<String> filterAttributes(Filter f) {
    Set<String> out = new LinkedHashSet<>();
    collect(f, out);
    return out;
  }

  private static void collect(Filter f, Set<String> out) {
    switch (f.getFilterType()) {
      case Filter.FILTER_TYPE_AND, Filter.FILTER_TYPE_OR -> {
        for (Filter c : f.getComponents()) collect(c, out);
      }
      case Filter.FILTER_TYPE_NOT -> collect(f.getNOTComponent(), out);
      default -> {
        String name = f.getAttributeName();
        // An extensible match without an attribute matches against every attribute (incl. hidden ones).
        out.add(name == null ? "*" : name);
      }
    }
  }

  /**
   * Stream the source's entries (excluding the base entry itself) with the given attributes.
   * {@code dn} in the list is ignored (the DN is always present).
   */
  public LdapDirectory.ScanResult scan(Source source, Collection<String> attributes, int max, Consumer<DirectoryEntry> consumer) {
    Map<String, String> attrs = new LinkedHashMap<>();
    for (String a : attributes) {
      if (a == null || DN.equalsIgnoreCase(a)) continue;
      attrs.putIfAbsent(a.toLowerCase(), a);
    }
    // "1.1" asks for no attributes at all (RFC 4511), for scans that only need DNs.
    List<String> requested = attrs.isEmpty() ? List.of("1.1") : new ArrayList<>(attrs.values());
    DirectoryDefinition def = source.definition();
    int[] skipped = {0};
    LdapDirectory.ScanResult r = directory.scan(def.baseDn(), EntryService.searchScope(def), source.filter(), requested, max + 1, e -> {
      if (Dns.equal(e.dn(), def.baseDn())) {
        skipped[0]++;
        return;
      }
      consumer.accept(e);
    });
    int delivered = r.entries() - skipped[0];
    // max + 1 was requested to leave room for the base entry; trim back to max.
    return new LdapDirectory.ScanResult(Math.min(delivered, max), r.truncated() || delivered > max);
  }

  /** Attributes the user can aggregate on in a definition, form fields first. */
  public List<AttributeChoice> attributeChoices(SessionUser user, DirectoryDefinition def) {
    FormDefinition form = entries.form(def);
    SchemaIndex index = schema.mergedIndex();
    Map<String, AttributeChoice> out = new LinkedHashMap<>();
    for (FormField f : form.fields()) {
      if (EntryService.isHidden(f.attribute()) || LdapDirectory.isBinary(f.attribute())) continue;
      out.putIfAbsent(f.attribute().toLowerCase(),
          new AttributeChoice(f.attribute(), f.label(), "form", f.multiValued(), f.widget(), f.format()));
    }
    for (String a : EntryService.readableAttributes(def, form)) {
      if (EntryService.isHidden(a) || LdapDirectory.isBinary(a)) continue;
      out.putIfAbsent(a.toLowerCase(), new AttributeChoice(a, a, "list", !index.isSingleValue(a), null, null));
    }
    for (String t : TIMESTAMPS) out.putIfAbsent(t.toLowerCase(), new AttributeChoice(t, label(t), "operational", false, null, null));
    out.putIfAbsent(DN, new AttributeChoice(DN, "Entry DN", "dn", false, null, "dn"));
    if (user.isAdmin()) {
      SchemaIndex.ClassAttributes ca = index.classAttributes(form.objectClasses());
      List<String> all = new ArrayList<>(ca.must());
      all.addAll(ca.may());
      for (String a : all) {
        if (EntryService.isHidden(a) || LdapDirectory.isBinary(a)) continue;
        out.putIfAbsent(a.toLowerCase(), new AttributeChoice(a, a, "schema", !index.isSingleValue(a), null, null));
      }
    }
    return new ArrayList<>(out.values());
  }

  private static String label(String timestamp) {
    return "createTimestamp".equals(timestamp) ? "Created" : "Last modified";
  }
}
