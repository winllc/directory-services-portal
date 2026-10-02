package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
  private static final int OPTIONS_LIMIT = 500;
  private static final long OPTIONS_TTL_MS = 30_000;
  private static final String REDACTED = "(redacted)";

  private final LdapDirectory directory;
  private final ConfigStore store;
  private final SchemaService schema;
  private final AuditLog audit;
  private final Map<DropdownSource.Ldap, CachedOptions> optionCache = new ConcurrentHashMap<>();

  private record CachedOptions(long at, List<OptionItem> options) {}

  public record ListQuery(String q, Integer page, Integer pageSize, String sort, String order) {}

  public EntryService(LdapDirectory directory, ConfigStore store, SchemaService schema, AuditLog audit) {
    this.directory = directory;
    this.store = store;
    this.schema = schema;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // Lookups & access
  // ---------------------------------------------------------------------------

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

  public AccessLevel access(SessionUser user, DirectoryDefinition def) {
    return PermissionService.effectiveAccess(user, def, store.snapshot().grants);
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

  public EntryListResponse list(SessionUser user, DirectoryDefinition def, ListQuery query) {
    require(user, def, AccessLevel.READ);
    FormDefinition form = form(def);
    Filter filter = baseFilter(def, form);
    String q = query.q() == null ? "" : query.q().trim();
    if (!q.isEmpty()) {
      List<String> attrs = def.searchAttributes().isEmpty() ? List.of(def.titleAttribute()) : def.searchAttributes();
      List<Filter> terms = new ArrayList<>(List.of(filter));
      // Every term must match at least one searchable attribute (substring match).
      for (String term : Arrays.stream(q.split("\\s+")).limit(5).toList()) {
        terms.add(Filter.createORFilter(attrs.stream()
            .map(a -> Filter.createSubstringFilter(a, null, new String[] {term}, null))
            .toList()));
      }
      filter = Filter.createANDFilter(terms);
    }
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

  public List<OptionItem> fieldOptions(FormField field) {
    if (field.dropdown() == null) return List.of();
    if (field.dropdown() instanceof DropdownSource.Static s) return s.options();
    DropdownSource.Ldap src = (DropdownSource.Ldap) field.dropdown();
    CachedOptions cached = optionCache.get(src);
    if (cached != null && System.currentTimeMillis() - cached.at() < OPTIONS_TTL_MS) return cached.options();

    Filter filter;
    try {
      filter = src.filter() == null || src.filter().isBlank() ? Filter.createPresenceFilter("objectClass") : Filter.create(src.filter());
    } catch (LDAPException e) {
      throw ApiException.badRequest("Drop down \"" + field.label() + "\" has an invalid filter");
    }
    List<String> attrs = src.valueIsDn() ? List.of(src.labelAttribute()) : List.of(src.labelAttribute(), src.valueAttribute());
    List<DirectoryEntry> entries = directory.search(src.baseDn(), scope(src.scope()), filter, OPTIONS_LIMIT, attrs).entries();
    Map<String, OptionItem> options = new LinkedHashMap<>();
    for (DirectoryEntry e : entries) {
      String label = e.first(src.labelAttribute());
      List<String> values = src.valueIsDn() ? List.of(e.dn()) : e.values(src.valueAttribute());
      for (String v : values) options.putIfAbsent(v, new OptionItem(v, label != null ? label : v));
    }
    List<OptionItem> sorted = options.values().stream().sorted(Comparator.comparing(OptionItem::display, String.CASE_INSENSITIVE_ORDER)).toList();
    optionCache.put(src, new CachedOptions(System.currentTimeMillis(), sorted));
    return sorted;
  }

  public List<OptionItem> options(SessionUser user, DirectoryDefinition def, String fieldId) {
    boolean canRead = access(user, def).atLeast(AccessLevel.READ) || !(def.selfMatch() instanceof SelfMatch.None);
    if (!canRead) throw ApiException.forbidden("You do not have access to this directory");
    FormField field = form(def).fields().stream()
        .filter(f -> f.id().equals(fieldId))
        .findFirst()
        .orElseThrow(() -> ApiException.notFound("Field not found"));
    return fieldOptions(field);
  }

  private Map<String, List<OptionItem>> resolveOptions(FormDefinition form, Set<String> attributeKeys) {
    Map<String, List<OptionItem>> out = new HashMap<>();
    for (FormField f : form.fields()) {
      if (!f.dropdownWidget() || !(f.dropdown() instanceof DropdownSource.Ldap) || f.allowsCustomValues()) continue;
      if (attributeKeys.contains(f.attribute().toLowerCase())) out.put(f.id(), fieldOptions(f));
    }
    return out;
  }

  private Map<String, List<String>> validate(FormDefinition form, Map<String, Object> values, FormValidator.Mode mode) {
    Set<String> keys = new LinkedHashSet<>();
    if (mode == FormValidator.Mode.CREATE) form.fields().forEach(f -> keys.add(f.attribute().toLowerCase()));
    else if (values != null) values.keySet().forEach(k -> keys.add(k.toLowerCase()));
    FormValidator.Result result = FormValidator.validate(form, values, mode, resolveOptions(form, keys));
    if (!result.errors().isEmpty()) throw ApiException.badRequest("Some fields are invalid", result.errors());
    return result.values();
  }

  // ---------------------------------------------------------------------------
  // Write
  // ---------------------------------------------------------------------------

  public DirectoryEntry create(SessionUser user, DirectoryDefinition def, String parentDn, Map<String, Object> input) {
    AuditLog.Draft event = audit.event("entry.create").by(user).definition(def.id()).target("entry", parentDn, null);
    return audited(event, () -> doCreate(event, user, def, parentDn, input));
  }

  private DirectoryEntry doCreate(AuditLog.Draft event, SessionUser user, DirectoryDefinition def, String parentDn, Map<String, Object> input) {
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
    directory.add(dn, attributes);
    // Recorded as soon as the directory accepts it: reading the entry back can still fail.
    event.success();
    return loadEntry(def, form, dn);
  }

  private static boolean sameSet(List<String> a, List<String> b) {
    return a.size() == b.size() && a.containsAll(b);
  }

  public DirectoryEntry update(SessionUser user, DirectoryDefinition def, String dn, Map<String, Object> input, boolean self) {
    AuditLog.Draft event = audit.event(self ? "entry.self_update" : "entry.update").by(user).definition(def.id()).target("entry", dn, null);
    return audited(event, () -> doUpdate(event, user, def, dn, input, self));
  }

  private DirectoryEntry doUpdate(AuditLog.Draft event, SessionUser user, DirectoryDefinition def, String dn, Map<String, Object> input, boolean self) {
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
        targetDn = directory.rename(current.dn(), new RDN(rdnNames[0], newRdnValues.get(0)));
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
      if (sameSet(prev, next)) continue;
      String attr = form.fields().stream().filter(f -> f.attribute().equalsIgnoreCase(key)).findFirst().orElseThrow().attribute();
      recorded.add(change(attr, prev, next));
      if (next.isEmpty()) {
        if (!prev.isEmpty()) changes.add(new Modification(ModificationType.DELETE, attr));
      } else {
        changes.add(new Modification(ModificationType.REPLACE, attr, next.toArray(String[]::new)));
      }
    }
    event.changes(recorded);
    directory.modify(targetDn, changes);
    // A save that changed nothing is not worth a line in the audit log.
    if (!recorded.isEmpty()) {
      List<String> titles = values.getOrDefault(def.titleAttribute().toLowerCase(), current.values(def.titleAttribute()));
      event.target("entry", targetDn, titles.isEmpty() ? Dns.rdnValue(targetDn) : titles.get(0)).success();
    }
    return loadEntry(def, form, targetDn);
  }

  public void remove(SessionUser user, DirectoryDefinition def, String dn) {
    AuditLog.Draft event = audit.event("entry.delete").by(user).definition(def.id()).target("entry", dn, null);
    audited(event, () -> {
      require(user, def, AccessLevel.WRITE);
      DirectoryEntry current = loadEntry(def, form(def), dn);
      // What the entry held, so a mistaken delete can be put back by hand.
      event.target("entry", current.dn(), title(def, current))
          .changes(current.attributes().entrySet().stream().map(e -> change(e.getKey(), e.getValue(), List.of())).toList());
      directory.delete(current.dn());
      event.success();
      return null;
    });
  }

  // ---------------------------------------------------------------------------
  // Audit
  // ---------------------------------------------------------------------------

  /**
   * Run a write, recording it as denied when the portal refuses it and failed when the
   * directory does. The operation records its own success. Input that fails validation (400) or
   * names an entry outside the directory (404) is not recorded: nothing was attempted.
   */
  private <T> T audited(AuditLog.Draft event, Supplier<T> operation) {
    try {
      return operation.get();
    } catch (ApiException e) {
      if (e.status() == HttpStatus.FORBIDDEN) event.denied(e.getMessage());
      throw e;
    } catch (DirectoryException e) {
      event.failed(e.getMessage());
      throw e;
    }
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
