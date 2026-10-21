package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditDetails;
import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.audit.AuditUnavailableException;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DefinitionSummary;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.DropdownSource;
import com.winllc.dsp.model.EntryListResponse;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.model.SelfEntryResult;
import com.winllc.dsp.model.SelfMatch;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.schema.SchemaIndex;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Directory entry operations through a definition. Every operation is confined to the
 * definition's namespace (base DN + scope + filter), checked against the user's effective
 * access, and validated against the definition's form.
 */
@Service
public class EntryService {
  /** Attributes that are never returned to clients. */
  private static final Set<String> HIDDEN = Set.of("userpassword", "unicodepwd", "sambantpassword", "sambalmpassword", "krbprincipalkey");
  private static final int LIST_LIMIT = 1000;
  /** Group member attributes: changed value by value, never replaced as a whole (see {@link #changeValues}). */
  public static final Set<String> MEMBER_ATTRIBUTES = Set.of("member", "uniquemember", "memberuid");
  private static final String REDACTED = AuditDetails.REDACTED;

  private final LdapDirectory directory;
  private final ConfigStore store;
  private final SchemaService schema;
  private final AuditLog audit;
  private final DirectoryOptions options;
  private final Licensing licensing;
  private final MeterRegistry meters;
  private final ApplicationEventPublisher events;

  public record ListQuery(String q, Integer page, Integer pageSize, String sort, String order) {}

  public EntryService(LdapDirectory directory, ConfigStore store, SchemaService schema, AuditLog audit, DirectoryOptions options,
      Licensing licensing, MeterRegistry meters, ApplicationEventPublisher events) {
    this.directory = directory;
    this.events = events;
    this.meters = meters;
    this.licensing = licensing;
    this.options = options;
    this.store = store;
    this.schema = schema;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // Lookups & access
  // ---------------------------------------------------------------------------

  /** Password-like attributes that are never returned, aggregated or searched on. */
  public static boolean isHidden(String attribute) {
    String a = attribute.toLowerCase();
    int semi = a.indexOf(';');
    return HIDDEN.contains(a) || (semi > 0 && HIDDEN.contains(a.substring(0, semi)));
  }

  public static SearchScope searchScope(DirectoryDefinition def) {
    return scope(def.scope());
  }

  /** The attributes a reader of the definition can see on its entries (form fields, list columns, title). */
  public static List<String> readableAttributes(DirectoryDefinition def, FormDefinition form) {
    return returnAttributes(def, form);
  }

  public DirectoryDefinition definition(String idOrSlug) {
    return store.snapshot().definitions.stream()
        .filter(d -> d.id().equals(idOrSlug) || d.slug().equals(idOrSlug))
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Directory definition not found"));
  }

  public FormDefinition form(DirectoryDefinition def) {
    return store.snapshot().forms.stream()
        .filter(f -> f.id().equals(def.formId()))
        .findFirst()
        .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Definition \"" + def.name() + "\" references a missing form"));
  }

  /**
   * The user's effective access. Read-only access for someone who is not an administrator
   * (everyone-can-read definitions, read grants, white-pages definitions) is the licensed
   * "white pages" feature: without it they keep only the directories they may edit.
   */
  public AccessLevel access(SessionUser user, DirectoryDefinition def) {
    AccessLevel access = PermissionService.effectiveAccess(user, def, store.snapshot().grants);
    if (access == AccessLevel.READ && !user.isAdmin() && !licensing.allows(LicensedFeature.WHITE_PAGES)) return AccessLevel.NONE;
    return access;
  }

  public DefinitionSummary summarize(SessionUser user, DirectoryDefinition def) {
    String formName = store.snapshot().forms.stream().filter(f -> f.id().equals(def.formId())).map(FormDefinition::name).findFirst().orElse(null);
    return new DefinitionSummary(def, access(user, def), formName);
  }

  public void require(SessionUser user, DirectoryDefinition def, AccessLevel level) {
    if (access(user, def).atLeast(level)) return;
    if (level == AccessLevel.WRITE && def.readOnly()) throw ApiException.forbidden("This directory is read only");
    throw ApiException.forbidden(level == AccessLevel.WRITE
        ? "You do not have write access to this directory"
        : "You do not have access to this directory");
  }

  private static SearchScope scope(String s) {
    return "one".equals(s) ? SearchScope.ONE : SearchScope.SUB;
  }

  /** The filter selecting entries that belong to a definition. */
  public Filter baseFilter(DirectoryDefinition def, FormDefinition form) {
    try {
      if (def.filter() != null && !def.filter().isBlank()) return Filter.create(def.filter().trim());
    } catch (LDAPException e) {
      throw ApiException.badRequest("The directory definition has an invalid filter");
    }
    SchemaIndex index = schema.mergedIndex();
    // Auxiliary classes are optional on existing entries; select by structural/abstract classes.
    List<Filter> parts = form.objectClasses().stream()
        .filter(c -> !c.equalsIgnoreCase("top"))
        .filter(c -> {
          ObjectClassDef oc = index.objectClass(c);
          return oc == null || !"AUXILIARY".equals(oc.kind());
        })
        .map(c -> Filter.createEqualityFilter("objectClass", c))
        .toList();
    if (parts.isEmpty()) return Filter.createPresenceFilter("objectClass");
    return parts.size() == 1 ? parts.get(0) : Filter.createANDFilter(parts);
  }

  private void assertInNamespace(DirectoryDefinition def, String dn) {
    if (!Dns.isValid(dn)) throw ApiException.badRequest("Invalid DN");
    int depth = Dns.depthBelow(dn, def.baseDn());
    if (depth < 1 || ("one".equals(def.scope()) && depth != 1)) throw ApiException.notFound("Entry is outside of this directory");
  }

  private static List<String> returnAttributes(DirectoryDefinition def, FormDefinition form) {
    Map<String, String> set = new LinkedHashMap<>();
    List<String> all = new ArrayList<>(List.of("objectClass", def.titleAttribute()));
    all.addAll(def.listAttributes());
    form.fields().forEach(f -> all.add(f.attribute()));
    for (String a : all) if (a != null) set.putIfAbsent(a.toLowerCase(), a);
    return new ArrayList<>(set.values());
  }

  private static DirectoryEntry sanitize(DirectoryEntry e) {
    Map<String, List<String>> attrs = new LinkedHashMap<>();
    e.attributes().forEach((k, v) -> {
      if (!HIDDEN.contains(k.toLowerCase())) attrs.put(k.toLowerCase(), v);
    });
    return new DirectoryEntry(e.dn(), attrs);
  }

  /** Read an entry that belongs to the definition (namespace + filter), without access checks. */
  private DirectoryEntry loadEntry(DirectoryDefinition def, FormDefinition form, String dn) {
    assertInNamespace(def, dn);
    try {
      List<DirectoryEntry> found = directory.search(dn, SearchScope.BASE, baseFilter(def, form), 1, returnAttributes(def, form)).entries();
      if (found.isEmpty()) throw ApiException.notFound("Entry not found in this directory");
      return sanitize(found.get(0));
    } catch (DirectoryException e) {
      if (e.code() == DirectoryException.Code.NO_SUCH_OBJECT) throw ApiException.notFound("Entry not found");
      throw e;
    }
  }

  // ---------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------

  /** The definition's entries matching the search text {@code q} (as the directory page searches). */
  public Filter searchFilter(DirectoryDefinition def, FormDefinition form, String query) {
    Filter filter = baseFilter(def, form);
    String q = query == null ? "" : query.trim();
    if (q.isEmpty()) return filter;
    List<String> attrs = def.searchAttributes().isEmpty() ? List.of(def.titleAttribute()) : def.searchAttributes();
    List<Filter> terms = new ArrayList<>(List.of(filter));
    // Every term must match at least one searchable attribute (substring match).
    for (String term : Arrays.stream(q.split("\\s+")).limit(5).toList()) {
      terms.add(Filter.createORFilter(attrs.stream()
          .map(a -> Filter.createSubstringFilter(a, null, new String[] {term}, null))
          .toList()));
    }
    return Filter.createANDFilter(terms);
  }

  /**
   * Streams the entries a reader of the definition may see that match {@code q}, with the
   * attributes they may see (never secrets), up to {@code max}; for exports. Returns true when more
   * entries matched than {@code max}.
   */
  public boolean scan(SessionUser user, DirectoryDefinition def, String q, int max, java.util.function.Consumer<DirectoryEntry> consumer) {
    require(user, def, AccessLevel.READ);
    FormDefinition form = form(def);
    List<String> attrs = returnAttributes(def, form).stream().filter(a -> !isHidden(a)).toList();
    return directory.scan(def.baseDn(), scope(def.scope()), searchFilter(def, form, q), attrs, max, e -> {
      if (!Dns.equal(e.dn(), def.baseDn())) consumer.accept(sanitize(e));
    }).truncated();
  }

  public EntryListResponse list(SessionUser user, DirectoryDefinition def, ListQuery query) {
    require(user, def, AccessLevel.READ);
    FormDefinition form = form(def);
    Filter filter = searchFilter(def, form, query.q());
    Set<String> attrs = new LinkedHashSet<>(List.of("objectClass", def.titleAttribute()));
    attrs.addAll(def.listAttributes());
    LdapDirectory.Result result = directory.search(def.baseDn(), scope(def.scope()), filter, LIST_LIMIT, new ArrayList<>(attrs));

    String sortAttr = (query.sort() == null || query.sort().isBlank() ? def.titleAttribute() : query.sort()).toLowerCase();
    Function<DirectoryEntry, String> key = e -> ("dn".equals(sortAttr) ? e.dn() : e.values(sortAttr).stream().findFirst().orElse("")).toLowerCase();
    Comparator<DirectoryEntry> cmp = Comparator.comparing(key);
    if ("desc".equals(query.order())) cmp = cmp.reversed();
    List<DirectoryEntry> sorted = result.entries().stream()
        .filter(e -> !Dns.equal(e.dn(), def.baseDn()))
        .map(EntryService::sanitize)
        .sorted(cmp)
        .toList();

    int pageSize = Math.min(Math.max(query.pageSize() == null ? 25 : query.pageSize(), 1), 200);
    int page = Math.max(query.page() == null ? 1 : query.page(), 1);
    int from = Math.min((page - 1) * pageSize, sorted.size());
    int to = Math.min(from + pageSize, sorted.size());
    return new EntryListResponse(sorted.subList(from, to), sorted.size(), page, pageSize, result.truncated());
  }

  public DirectoryEntry get(SessionUser user, DirectoryDefinition def, String dn) {
    require(user, def, AccessLevel.READ);
    return loadEntry(def, form(def), dn);
  }

  // ---------------------------------------------------------------------------
  // Drop-down options
  // ---------------------------------------------------------------------------

  /** All options of a drop down (directory searches: up to their limit, see {@link #optionPage}). */
  public List<OptionItem> fieldOptions(FormField field) {
    if (field.dropdown() == null) return List.of();
    if (field.dropdown() instanceof DropdownSource.Static s) return s.options();
    return options.list((DropdownSource.Ldap) field.dropdown()).options();
  }

  /** The full option list, or null when a directory search has more options than its limit. */
  public List<OptionItem> completeFieldOptions(FormField field) {
    if (field.dropdown() instanceof DropdownSource.Ldap src) {
      DirectoryOptions.Page page = options.list(src);
      return page.truncated() ? null : page.options();
    }
    return fieldOptions(field);
  }

  /** Options for a user: typed text {@code q} searches, {@code values} resolves stored values. */
  public DirectoryOptions.Page optionPage(SessionUser user, DirectoryDefinition def, String fieldId, String q, List<String> values) {
    boolean canRead = access(user, def).atLeast(AccessLevel.READ) || !(def.selfMatch() instanceof SelfMatch.None);
    if (!canRead) throw ApiException.forbidden("You do not have access to this directory");
    FormField field = form(def).fields().stream()
        .filter(f -> f.id().equals(fieldId))
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Field not found"));
    if (field.dropdown() instanceof DropdownSource.Static s) return new DirectoryOptions.Page(s.options(), false, null);
    if (!(field.dropdown() instanceof DropdownSource.Ldap src)) return new DirectoryOptions.Page(List.of(), false, null);
    if (values != null && !values.isEmpty()) return new DirectoryOptions.Page(options.lookup(src, values), false, null);
    DirectoryOptions.Page page = q == null || q.isBlank() ? (src.searchMode() ? options.search(src, "") : options.list(src)) : options.search(src, q);
    // The effective filter is for administrators testing the settings, not for every user.
    return new DirectoryOptions.Page(page.options(), page.truncated(), null);
  }

  /**
   * For each directory-backed drop down among the submitted values: the options matching those
   * values (looked up directly, so validation works however many options there are).
   */
  private Map<String, List<OptionItem>> resolveOptions(FormDefinition form, Map<String, Object> input) {
    Map<String, List<OptionItem>> out = new HashMap<>();
    Map<String, Object> byAttr = new HashMap<>();
    if (input != null) input.forEach((k, v) -> byAttr.put(k.toLowerCase(), v));
    for (FormField f : form.fields()) {
      if (!f.dropdownWidget() || !(f.dropdown() instanceof DropdownSource.Ldap src) || f.allowsCustomValues()) continue;
      List<String> submitted = FormValidator.normalize(byAttr.get(f.attribute().toLowerCase()));
      out.put(f.id(), submitted.isEmpty() ? List.of() : options.lookup(src, submitted));
    }
    return out;
  }

  private Map<String, List<String>> validate(FormDefinition form, Map<String, Object> values, FormValidator.Mode mode) {
    FormValidator.Result result = FormValidator.validate(form, values, mode, resolveOptions(form, values));
    if (!result.errors().isEmpty()) throw ApiException.badRequest("Some fields are invalid", result.errors());
    return result.values();
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  /** What a write would do, without doing it (an import's dry run). */
  public record Preview(String operation, String dn, List<String> attributes) {}

  /** Filled in by a write run as a preview: it then checks everything and stops before the directory. */
  private static final class PreviewHolder {
    Preview result;
  }

  public DirectoryEntry create(SessionUser user, DirectoryDefinition def, String parentDn, Map<String, Object> input) {
    AuditLog.Draft event = audit.event("entry.create").by(user).definition(def.id()).target("entry", parentDn, null);
    return audited("create", event, () -> doCreate(event, user, def, parentDn, input, null));
  }

  /** Checks a create as {@link #create} would (permission, container, validation, existing entry), writing nothing. */
  public Preview previewCreate(SessionUser user, DirectoryDefinition def, String parentDn, Map<String, Object> input) {
    PreviewHolder p = new PreviewHolder();
    doCreate(audit.event("entry.create"), user, def, parentDn, input, p);
    return p.result;
  }

  /** Checks an update as {@link #update} would, writing nothing; operation "unchanged" when it would change nothing. */
  public Preview previewUpdate(SessionUser user, DirectoryDefinition def, String dn, Map<String, Object> input) {
    PreviewHolder p = new PreviewHolder();
    doUpdate(audit.event("entry.update"), user, def, dn, input, false, p);
    return p.result;
  }

  /** True when the entry exists in the definition (namespace and filter); no permission check. */
  public boolean exists(DirectoryDefinition def, String dn) {
    try {
      loadEntry(def, form(def), dn);
      return true;
    } catch (ApiException e) {
      if (e.status() == HttpStatus.NOT_FOUND) return false;
      throw e;
    }
  }

  private DirectoryEntry doCreate(AuditLog.Draft event, SessionUser user, DirectoryDefinition def, String parentDn, Map<String, Object> input,
      PreviewHolder preview) {
    require(user, def, AccessLevel.WRITE);
    FormDefinition form = form(def);
    List<String> allowed = def.createContainers().isEmpty() ? List.of(def.baseDn()) : def.createContainers();
    String parent = parentDn != null && !parentDn.isBlank() ? parentDn.trim() : allowed.get(0);
    if (allowed.stream().noneMatch(c -> Dns.equal(c, parent))) throw ApiException.badRequest("New entries cannot be created in that container");
    if (!Dns.isUnder(parent, def.baseDn(), false)) throw ApiException.badRequest("Container is outside of this directory");
    if ("one".equals(def.scope()) && !Dns.equal(parent, def.baseDn())) {
      throw ApiException.badRequest("This directory only contains direct children of its base DN");
    }

    Map<String, List<String>> values = validate(form, input, FormValidator.Mode.CREATE);
    String rdnKey = form.rdnAttribute().toLowerCase();
    List<String> rdnValues = values.getOrDefault(rdnKey, List.of());
    if (rdnValues.isEmpty()) throw ApiException.badRequest("Missing naming attribute", Map.of(rdnKey, "Required"));

    String dn = Dns.build(form.rdnAttribute(), rdnValues.get(0), parent);
    Map<String, List<String>> attributes = new LinkedHashMap<>();
    attributes.put("objectClass", form.objectClasses());
    for (FormField f : form.fields()) {
      List<String> v = values.get(f.attribute().toLowerCase());
      if (v != null && !v.isEmpty()) attributes.put(f.attribute(), v);
    }
    List<String> titles = values.getOrDefault(def.titleAttribute().toLowerCase(), List.of());
    event.target("entry", dn, titles.isEmpty() ? rdnValues.get(0) : titles.get(0));
    event.changes(attributes.entrySet().stream().map(e -> change(e.getKey(), List.of(), e.getValue())).toList());
    if (preview != null) {
      if (directory.get(dn, List.of("1.1")) != null) throw ApiException.conflict("An entry named " + dn + " already exists");
      preview.result = new Preview(EntryChanged.CREATE, dn, attributes.keySet().stream().filter(a -> !a.equalsIgnoreCase("objectClass")).toList());
      return null;
    }
    // Never change the directory unaudited: a full or unwritable audit disk refuses the write (503).
    audit.requireWritable();
    directory.add(dn, attributes);
    // Recorded as soon as the directory accepts it: reading the entry back can still fail.
    event.success();
    List<AuditEvent.Change> created = attributes.entrySet().stream().map(e -> change(e.getKey(), List.of(), e.getValue())).toList();
    publish(user, EntryChanged.CREATE, def, form, dn, null, titles.isEmpty() ? rdnValues.get(0) : titles.get(0), created, false);
    return loadEntry(def, form, dn);
  }

  private static boolean sameSet(List<String> a, List<String> b) {
    return a.size() == b.size() && a.containsAll(b);
  }

  public DirectoryEntry update(SessionUser user, DirectoryDefinition def, String dn, Map<String, Object> input, boolean self) {
    AuditLog.Draft event = audit.event(self ? "entry.self_update" : "entry.update").by(user).definition(def.id()).target("entry", dn, null);
    return audited("update", event, () -> doUpdate(event, user, def, dn, input, self, null));
  }

  private DirectoryEntry doUpdate(AuditLog.Draft event, SessionUser user, DirectoryDefinition def, String dn, Map<String, Object> input, boolean self,
      PreviewHolder preview) {
    if (def.readOnly()) throw ApiException.forbidden("This directory is read only");
    if (!self) require(user, def, AccessLevel.WRITE);
    FormDefinition form = form(def);
    DirectoryEntry current = loadEntry(def, form, dn);
    event.target("entry", current.dn(), title(def, current));
    if (self) assertSelf(user, def, form, current.dn());

    Map<String, List<String>> values = validate(form, input, self ? FormValidator.Mode.SELF : FormValidator.Mode.UPDATE);
    SchemaIndex index = schema.mergedIndex();

    // Rename first if the naming attribute's value changed.
    String targetDn = current.dn();
    String rdnKey = form.rdnAttribute().toLowerCase();
    RDN currentRdn = Dns.parse(current.dn()).getRDN();
    String rdnPrimary = index.primaryAttributeName(form.rdnAttribute());
    String[] rdnNames = currentRdn.getAttributeNames();
    int rdnPos = -1;
    for (int i = 0; i < rdnNames.length; i++) {
      if (index.primaryAttributeName(rdnNames[i]).equalsIgnoreCase(rdnPrimary)) rdnPos = i;
    }
    List<String> newRdnValues = values.get(rdnKey);
    if (rdnPos >= 0 && newRdnValues != null) {
      String currentValue = currentRdn.getAttributeValues()[rdnPos];
      if (newRdnValues.stream().noneMatch(v -> v.equalsIgnoreCase(currentValue))) {
        if (rdnNames.length > 1) throw ApiException.badRequest("Entries with multi-valued RDNs cannot be renamed here");
        if (preview != null) {
          targetDn = Dns.build(rdnNames[0], newRdnValues.get(0), Dns.parse(current.dn()).getParentString());
        } else {
          audit.requireWritable();
          targetDn = directory.rename(current.dn(), new RDN(rdnNames[0], newRdnValues.get(0)));
        }
        event.detail("previousDn", current.dn()).target("entry", targetDn, title(def, current));
      }
    }

    List<Modification> changes = new ArrayList<>();
    List<AuditEvent.Change> recorded = new ArrayList<>();
    // Ensure the entry carries the form's object classes (e.g. auxiliary extensions).
    Set<String> currentClasses = new LinkedHashSet<>();
    current.values("objectClass").forEach(c -> currentClasses.add(c.toLowerCase()));
    List<String> missingClasses = form.objectClasses().stream().filter(c -> !currentClasses.contains(c.toLowerCase())).toList();
    boolean writesSomething = values.entrySet().stream().anyMatch(e -> !sameSet(e.getValue(), current.values(e.getKey())));
    if (!missingClasses.isEmpty() && writesSomething) {
      changes.add(new Modification(ModificationType.ADD, "objectClass", missingClasses.toArray(String[]::new)));
      List<String> after = new ArrayList<>(current.values("objectClass"));
      after.addAll(missingClasses);
      recorded.add(change("objectClass", current.values("objectClass"), after));
    }
    for (Map.Entry<String, List<String>> e : values.entrySet()) {
      String key = e.getKey();
      List<String> next = e.getValue();
      List<String> prev = current.values(key);
      FormField field = form.fields().stream().filter(f -> f.attribute().equalsIgnoreCase(key)).findFirst().orElseThrow();
      String attr = field.attribute();
      if (MEMBER_ATTRIBUTES.contains(key)) {
        // Members: only the difference, as value-level ADD and DELETE, so a concurrent change to
        // other members is kept. A group the directory returned in ranges (Active Directory, over
        // 1,500 members) can't be edited as a whole list: its full list was never read.
        if (current.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range="))) {
          throw new ApiException(HttpStatus.CONFLICT, field.label() + " has too many values to edit as a list (the directory returns them in "
              + "parts); add and remove them with \"Change members in bulk\" or the API", "ranged_values");
        }
        boolean dnValued = !key.equals("memberuid");
        java.util.function.BiPredicate<String, String> same = (a, b) -> dnValued ? Dns.equal(a, b) || a.equalsIgnoreCase(b) : a.equalsIgnoreCase(b);
        List<String> added = next.stream().filter(v -> prev.stream().noneMatch(p -> same.test(p, v))).toList();
        List<String> removed = prev.stream().filter(p -> next.stream().noneMatch(v -> same.test(p, v))).toList();
        if (added.isEmpty() && removed.isEmpty()) continue;
        recorded.add(change(attr, prev, next));
        if (!added.isEmpty()) changes.add(new Modification(ModificationType.ADD, attr, added.toArray(String[]::new)));
        if (!removed.isEmpty()) changes.add(new Modification(ModificationType.DELETE, attr, removed.toArray(String[]::new)));
        continue;
      }
      if (sameSet(prev, next)) continue;
      recorded.add(change(attr, prev, next));
      if (next.isEmpty()) {
        if (!prev.isEmpty()) changes.add(new Modification(ModificationType.DELETE, attr));
      } else {
        changes.add(new Modification(ModificationType.REPLACE, attr, next.toArray(String[]::new)));
      }
    }
    event.changes(recorded);
    if (preview != null) {
      List<String> attrs = recorded.stream().map(AuditEvent.Change::attribute).toList();
      preview.result = new Preview(attrs.isEmpty() && Dns.equal(targetDn, current.dn()) ? "unchanged" : EntryChanged.UPDATE, targetDn, attrs);
      return null;
    }
    if (!changes.isEmpty()) audit.requireWritable();
    // Permissive: value-level member changes skip values another change already added or removed.
    directory.modifyValues(targetDn, changes);
    // A save that changed nothing is not worth a line in the audit log.
    if (!recorded.isEmpty()) {
      List<String> titles = values.getOrDefault(def.titleAttribute().toLowerCase(), current.values(def.titleAttribute()));
      String name = titles.isEmpty() ? Dns.rdnValue(targetDn) : titles.get(0);
      event.target("entry", targetDn, name).success();
      publish(user, EntryChanged.UPDATE, def, form, targetDn, Dns.equal(targetDn, current.dn()) ? null : current.dn(), name, recorded, self);
      // A password set through a form: the user's own change, or an administrator's reset.
      List<String> secrets = recorded.stream().map(AuditEvent.Change::attribute).filter(a -> HIDDEN.contains(a.toLowerCase())).toList();
      if (!secrets.isEmpty()) {
        audit.event(self ? "auth.password-changed" : "auth.password-reset").by(user).definition(def.id()).target("user", targetDn, name)
            .detail("attributes", String.join(",", secrets)).success();
      }
    }
    return loadEntry(def, form, targetDn);
  }

  /** The outcome of {@link #changeValues}; {@code partial}: the server returned the values in ranges. */
  public record ValueChange(String dn, String attribute, List<String> added, List<String> removed, boolean partial) {}

  /**
   * Adds and removes values of one multi-valued field (group members) as value-level ADD and DELETE
   * modifications, never a REPLACE of the whole attribute: a concurrent change to other values is
   * kept, and so are the values of a group the server returned only in part (Active Directory's
   * ranged retrieval of more than 1,500 members), which a read-modify-replace would wipe. Same
   * permission, namespace, validation (of the added values) and audit as {@link #update}; the audit
   * event records the removed values as {@code <attribute>.old} and the added ones as
   * {@code <attribute>.new}, with {@code details.change = values}.
   */
  public ValueChange changeValues(SessionUser user, DirectoryDefinition def, String dn, FormField field, List<String> add, List<String> remove,
      boolean dnValued) {
    AuditLog.Draft event = audit.event("entry.update").by(user).definition(def.id()).target("entry", dn, null);
    return audited("update", event, () -> {
      if (def.readOnly()) throw ApiException.forbidden("This directory is read only");
      require(user, def, AccessLevel.WRITE);
      FormDefinition form = form(def);
      if (!FormValidator.isWritable(form, field, FormValidator.Mode.UPDATE)) throw ApiException.forbidden(field.label() + " can't be changed");
      DirectoryEntry current = loadEntry(def, form, dn);
      String name = title(def, current);
      event.target("entry", current.dn(), name);
      String attr = field.attribute();
      String key = attr.toLowerCase();
      boolean partial = current.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range="));
      List<String> present = current.values(attr);
      java.util.function.BiPredicate<String, String> same = (a, b) -> dnValued ? Dns.equal(a, b) || a.equalsIgnoreCase(b) : a.equalsIgnoreCase(b);
      List<String> toAdd = partial ? List.copyOf(add) : add.stream().filter(v -> present.stream().noneMatch(p -> same.test(p, v))).toList();
      List<String> toRemove = partial ? List.copyOf(remove) : present.stream().filter(p -> remove.stream().anyMatch(r -> same.test(p, r))).toList();
      toRemove = toRemove.stream().filter(v -> toAdd.stream().noneMatch(a -> same.test(a, v))).toList();
      if (!toAdd.isEmpty()) {
        List<OptionItem> resolved = field.dropdownWidget() && field.dropdown() instanceof DropdownSource.Ldap src && !field.allowsCustomValues()
            ? options.lookup(src, toAdd) : null;
        String error = FormValidator.validateField(field, toAdd, resolved);
        if (error != null) throw ApiException.badRequest("Some fields are invalid", Map.of(key, error));
      }
      if (!partial && field.required() && present.size() - toRemove.size() + toAdd.size() <= 0) {
        throw ApiException.badRequest("Some fields are invalid", Map.of(key, field.label() + " is required"));
      }
      if (toAdd.isEmpty() && toRemove.isEmpty()) return new ValueChange(current.dn(), attr, List.of(), List.of(), partial);
      List<Modification> changes = new ArrayList<>();
      if (!toAdd.isEmpty()) changes.add(new Modification(ModificationType.ADD, attr, toAdd.toArray(String[]::new)));
      if (!toRemove.isEmpty()) changes.add(new Modification(ModificationType.DELETE, attr, toRemove.toArray(String[]::new)));
      AuditEvent.Change recorded = change(attr, toRemove, toAdd);
      event.changes(List.of(recorded)).detail("change", "values");
      audit.requireWritable();
      directory.modifyValues(current.dn(), changes);
      event.success();
      publish(user, EntryChanged.UPDATE, def, form, current.dn(), null, name, List.of(recorded), false);
      return new ValueChange(current.dn(), attr, toAdd, toRemove, partial);
    });
  }

  public void remove(SessionUser user, DirectoryDefinition def, String dn) {
    AuditLog.Draft event = audit.event("entry.delete").by(user).definition(def.id()).target("entry", dn, null);
    audited("delete", event, () -> {
      require(user, def, AccessLevel.WRITE);
      DirectoryEntry current = loadEntry(def, form(def), dn);
      // What the entry held, so a mistaken delete can be put back by hand.
      event.target("entry", current.dn(), title(def, current))
          .changes(current.attributes().entrySet().stream().map(e -> change(e.getKey(), e.getValue(), List.of())).toList());
      audit.requireWritable();
      directory.delete(current.dn());
      event.success();
      publish(user, EntryChanged.DELETE, def, form(def), current.dn(), null, title(def, current),
          current.attributes().entrySet().stream().map(e -> change(e.getKey(), e.getValue(), List.of())).toList(), false);
      return null;
    });
  }

  // ---------------------------------------------------------------------------
  // Audit
  // ---------------------------------------------------------------------------

  /**
   * Run a write, recording it as denied when the portal refuses it and failed when the
   * directory does (or the audit log can't be written). The operation records its own success.
   * Input that fails validation (400) or names an entry outside the directory (404) is not
   * recorded: nothing was attempted. Counted in {@code ens_dsp_entry_writes_total{operation,outcome}}.
   */
  private <T> T audited(String operation, AuditLog.Draft event, Supplier<T> write) {
    try {
      T result = write.get();
      count(operation, "success");
      return result;
    } catch (ApiException e) {
      if (e.status() == HttpStatus.FORBIDDEN) {
        event.denied(e.getMessage());
        count(operation, "denied");
      }
      throw e;
    } catch (DirectoryException e) {
      event.failed(e.getMessage());
      count(operation, "failure");
      throw e;
    } catch (AuditUnavailableException e) {
      count(operation, "failure");
      throw e;
    }
  }

  /** Tells listeners (webhooks) about a change; a listener's failure never fails the change. */
  private void publish(SessionUser user, String operation, DirectoryDefinition def, FormDefinition form, String dn, String previousDn,
      String name, List<AuditEvent.Change> changes, boolean self) {
    String apiClient = null;
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
        && a.getRequest().getAttribute(AuditLog.API_CLIENT) instanceof AuditLog.ApiClient c) {
      apiClient = c.id();
    }
    try {
      events.publishEvent(new EntryChanged(operation, def.id(), form.id(), dn, previousDn, name, List.copyOf(changes), self,
          user == null ? null : user.username(), user == null ? null : user.authMethod(), apiClient));
    } catch (RuntimeException e) {
      org.slf4j.LoggerFactory.getLogger(EntryService.class).error("Could not pass on the change of {}: {}",
          com.winllc.dsp.util.LogSafe.of(dn), com.winllc.dsp.util.LogSafe.of(e.getMessage()));
    }
  }

  private void count(String operation, String outcome) {
    Counter.builder("ens.dsp.entry.writes").description("Directory entry writes through the portal")
        .tag("operation", operation).tag("outcome", outcome).register(meters).increment();
  }

  private static AuditEvent.Change change(String attribute, List<String> before, List<String> after) {
    return new AuditEvent.Change(attribute, redact(attribute, before), redact(attribute, after));
  }

  private static List<String> redact(String attribute, List<String> values) {
    return values.isEmpty() || !HIDDEN.contains(attribute.toLowerCase()) ? List.copyOf(values) : List.of(REDACTED);
  }

  private static String title(DirectoryDefinition def, DirectoryEntry entry) {
    String t = entry.first(def.titleAttribute());
    return t != null ? t : Dns.rdnValue(entry.dn());
  }

  // ---------------------------------------------------------------------------
  // Self service
  // ---------------------------------------------------------------------------

  private List<String> userAttributeValues(SessionUser user, String attribute) {
    if ("dn".equalsIgnoreCase(attribute)) return List.of(user.dn());
    DirectoryEntry entry = directory.get(user.dn(), List.of(attribute));
    return entry == null ? List.of() : entry.values(attribute);
  }

  /** Entries in a definition that represent the given user. */
  public List<DirectoryEntry> findSelf(SessionUser user, DirectoryDefinition def, FormDefinition form) {
    SelfMatch match = def.selfMatch();
    if (match == null || match instanceof SelfMatch.None) return List.of();
    if (match instanceof SelfMatch.Dn) {
      if (Dns.depthBelow(user.dn(), def.baseDn()) < 1) return List.of();
      try {
        return List.of(loadEntry(def, form, user.dn()));
      } catch (ApiException e) {
        if (e.status() == HttpStatus.NOT_FOUND) return List.of();
        throw e;
      }
    }
    SelfMatch.Attribute am = (SelfMatch.Attribute) match;
    List<String> values = userAttributeValues(user, am.userAttribute());
    if (values.isEmpty()) return List.of();
    Filter filter = Filter.createANDFilter(
        baseFilter(def, form),
        Filter.createORFilter(values.stream().limit(20).map(v -> Filter.createEqualityFilter(am.entryAttribute(), v)).toList()));
    return directory.search(def.baseDn(), scope(def.scope()), filter, 50, returnAttributes(def, form)).entries().stream()
        .filter(e -> !Dns.equal(e.dn(), def.baseDn()))
        .map(EntryService::sanitize)
        .toList();
  }

  private void assertSelf(SessionUser user, DirectoryDefinition def, FormDefinition form, String dn) {
    if (findSelf(user, def, form).stream().noneMatch(e -> Dns.equal(e.dn(), dn))) {
      throw ApiException.forbidden("You can only edit your own entry");
    }
  }

  public List<SelfEntryResult> selfOverview(SessionUser user) {
    List<SelfEntryResult> results = new ArrayList<>();
    for (DirectoryDefinition def : store.snapshot().definitions) {
      if (def.selfMatch() == null || def.selfMatch() instanceof SelfMatch.None) continue;
      FormDefinition form = store.snapshot().forms.stream().filter(f -> f.id().equals(def.formId())).findFirst().orElse(null);
      if (form == null) continue;
      List<String> editable = def.readOnly() ? List.of()
          : form.fields().stream().filter(f -> f.selfEditable() && !f.readOnly()).map(FormField::attribute).toList();
      DefinitionSummary summary = summarize(user, def);
      try {
        results.add(new SelfEntryResult(summary, form, findSelf(user, def, form), editable, null));
      } catch (RuntimeException e) {
        results.add(new SelfEntryResult(summary, form, List.of(), editable, e.getMessage()));
      }
    }
    return results;
  }

  /** Validate a definition's DN-related fields (used by the admin API). */
  public static void assertDefinitionShape(String baseDn, String scopeValue, List<String> containers) {
    for (String c : containers) {
      if (!Dns.isValid(c)) throw ApiException.badRequest("Container \"" + c + "\" is not a valid DN", Map.of("createContainers", "Invalid DN"));
      if (!Dns.isUnder(c, baseDn, false)) {
        throw ApiException.badRequest("Container \"" + c + "\" is outside the base DN", Map.of("createContainers", "Must be within the base DN"));
      }
      if ("one".equals(scopeValue) && !Dns.equal(c, baseDn)) {
        throw ApiException.badRequest("With one-level scope, entries can only be created directly under the base DN",
            Map.of("createContainers", "Must equal the base DN"));
      }
    }
  }
}
