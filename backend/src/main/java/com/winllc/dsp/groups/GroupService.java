package com.winllc.dsp.groups;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.GroupAccess;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.model.SubjectSearchResult;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.service.People;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.util.LogSafe;
import com.winllc.dsp.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Self-service group access (see {@link GroupAccess}): people look up the groups of definitions
 * that have it enabled, in the LDAP directory they signed in to, and ask to join; a group's owners
 * (its owner attribute names them, or a group they belong to) and anyone who may edit the
 * definition decide, and manage the group's members; groups the definition marks open are joined
 * without asking. Looking groups up needs no grant on the
 * definition: only names, descriptions and owners are shown, and the member list only to those who
 * manage the group.
 *
 * <p>Membership changes go through {@link Memberships} (value-level adds and deletes, the form's
 * validation, the {@code entry.update} audit event and the {@code group.membership_changed}
 * webhook). An owner without write access to the definition changes only their own groups, and
 * the audit event says so ({@code details.via}).
 *
 * <p>A membership the portal grants may have an end date (a whole day, UTC; see
 * {@link MembershipExpiries}): asked for with a request, shortened by the approver, set by an owner,
 * or the directory's longest membership. {@link #expireMemberships} removes the member when it has
 * passed.
 */
@Service
public class GroupService {
  private static final Logger log = LoggerFactory.getLogger(GroupService.class);
  /** Most groups a lookup returns. */
  static final int SEARCH_LIMIT = 100;
  /** Most groups read per definition for one lookup, "my groups" or the groups someone owns. */
  static final int SCAN_LIMIT = 1_000;
  static final int MEMBERS_PAGE = 25;
  static final int MAX_OWNERS_SHOWN = 20;
  static final int MAX_PENDING_PER_USER = 25;
  static final int MAX_REASON = 1_000;
  /** Most of the user's own groups an owner filter names. */
  private static final int MAX_OWNER_GROUPS = 50;

  public static final String VIA_REQUEST = "group-access-request";
  public static final String VIA_OWNER = "group-owner";
  public static final String VIA_LEAVE = "group-leave";
  /** Someone joined an open group, without approval. */
  public static final String VIA_JOIN = "group-join";
  /** The portal removed a member whose end date had passed. */
  public static final String VIA_EXPIRED = "group-membership-expired";
  /** A record whose member isn't in the group is kept this long: its membership may just be on its way. */
  static final Duration EXPIRY_GRACE = Duration.ofMinutes(10);
  /** A member that could not be removed is tried again after this long. */
  static final Duration EXPIRY_RETRY = Duration.ofHours(24);
  /** An owner without write access changed the group's owners. */
  public static final String VIA_OWNERS = "group-owners";
  /** Most lines of a pasted list of people looked up at once. */
  static final int MAX_PASTED = 200;
  /** Most owners added or removed in one change. */
  static final int MAX_OWNER_CHANGES = 20;

  /** {@code maxMembershipDays}: the longest membership the portal grants here (null: no limit). */
  public record GroupDirectory(String id, String name, String slug, String icon, boolean requireReason, boolean allowLeave,
      Integer maxMembershipDays) {}

  /**
   * A group as listed. {@code member}: the user belongs to it; {@code owner}: its owner attribute
   * names the user (or a group of theirs); {@code canManage}: owner, or may edit the definition;
   * {@code requestable}: it takes requests; {@code open}: and can be joined at once, without
   * approval; {@code pendingRequestId}: the user's open request; {@code memberUntil}: the last day
   * of the user's membership, when the portal granted it for a limited time.
   */
  public record GroupSummary(String definitionId, String definitionName, String dn, String name, String description, boolean member,
      boolean owner, boolean canManage, boolean requestable, boolean open, String pendingRequestId, String memberUntil) {}

  /** A member as listed for those who manage the group: {@code until} is the membership's last day, if it has one. */
  public record Member(String value, String dn, String name, String until) {}

  /** A person or group named by a group (owner, member), with the name the directory gives it. */
  public record Person(String value, String dn, String name) {}

  public record GroupList(List<GroupSummary> groups, boolean truncated) {}

  /**
   * {@code memberCount} is null when the directory returned the members in part (Active Directory,
   * over 1,500). {@code canManageOwners}: the viewer may add and remove the group's owners.
   */
  public record GroupDetail(GroupSummary group, List<Person> owners, boolean moreOwners, Integer memberCount, String memberAttribute,
      boolean requireReason, boolean allowLeave, int pendingRequests, boolean canManageOwners, Integer maxMembershipDays) {}

  public record MemberPage(List<Member> items, int total, int page, int pageSize, boolean partial) {}

  /** A pasted line that named one person: {@code value} is what the member attribute holds; {@code member}: already in the group. */
  public record FoundPerson(String line, String value, String dn, String name, boolean member) {}

  /** A pasted line that several people match (their names, the first few). */
  public record AmbiguousLine(String line, List<String> matches) {}

  /** A pasted list of people, looked up: who was found, the lines nobody matches, and those several people match. */
  public record ResolvedPeople(List<FoundPerson> found, List<String> notFound, List<AmbiguousLine> ambiguous) {}

  /** A request as shown: {@code canDecide} when the viewer may approve or reject it. */
  public record RequestView(AccessRequests.Request request, String definitionName, boolean canDecide) {}

  public record Summary(int toReview, int myPending) {}

  private final ConfigStore store;
  private final EntryService entries;
  private final Memberships memberships;
  private final Directories directories;
  private final AccessRequests requests;
  private final MembershipExpiries expiries;
  private final AuditLog audit;

  public GroupService(ConfigStore store, EntryService entries, Memberships memberships, Directories directories, AccessRequests requests,
      MembershipExpiries expiries, AuditLog audit) {
    this.store = store;
    this.entries = entries;
    this.memberships = memberships;
    this.directories = directories;
    this.requests = requests;
    this.expiries = expiries;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // Definitions and the user's relation to a group
  // ---------------------------------------------------------------------------

  /** Definitions with group access in the user's directory (only there can they be a member). */
  public List<DirectoryDefinition> definitions(SessionUser user) {
    return store.snapshot().definitions.stream()
        .filter(d -> d.groupAccess() && directories.available(d.directory()) && directories.same(d.directory(), user.directory()))
        .toList();
  }

  public List<GroupDirectory> directoriesFor(SessionUser user) {
    return definitions(user).stream().map(d -> new GroupDirectory(d.id(), d.name(), d.slug(), d.icon(), d.groups().requireReason(),
        d.groups().allowLeave(), maxDays(d))).toList();
  }

  /** A definition with group access the user can use (404 otherwise: it isn't theirs to know about). */
  DirectoryDefinition definition(SessionUser user, String id) {
    return definitions(user).stream().filter(d -> d.id().equals(id) || d.slug().equals(id)).findFirst()
        .orElseThrow(() -> ApiException.notFound("No such group directory"));
  }

  /**
   * A definition with group access, for managing requests: also in another directory when the
   * user may edit it (administrators review every directory's requests).
   */
  private DirectoryDefinition managedDefinition(SessionUser user, String id) {
    DirectoryDefinition def = store.snapshot().definitions.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
    if (def == null || !def.groupAccess() || !directories.available(def.directory())) throw ApiException.notFound("No such group directory");
    return def;
  }

  private FormField memberField(DirectoryDefinition def) {
    return memberships.field(entries.form(def), def.groups().memberAttribute());
  }

  /** The value naming the user in the member attribute: their DN, or their login name (memberUid). */
  static String memberValue(SessionUser user, FormField field) {
    return Memberships.dnValued(field) ? user.dn() : user.username();
  }

  private static Filter memberFilter(SessionUser user, FormField field) {
    return Filter.createEqualityFilter(field.attribute(), memberValue(user, field));
  }

  /** The owner attribute names the user, or one of their groups. */
  private static Filter ownerFilter(SessionUser user, DirectoryDefinition def) {
    String attr = def.groups().ownerAttributeOrDefault();
    List<Filter> any = new ArrayList<>(List.of(Filter.createEqualityFilter(attr, user.dn())));
    user.groups().stream().limit(MAX_OWNER_GROUPS).forEach(g -> any.add(Filter.createEqualityFilter(attr, g)));
    return any.size() == 1 ? any.get(0) : Filter.createORFilter(any);
  }

  private static boolean names(List<String> owners, SessionUser user) {
    return owners.stream().anyMatch(o -> Dns.equal(o, user.dn()) || user.groups().stream().anyMatch(g -> Dns.equal(g, o)));
  }

  private boolean canEdit(SessionUser user, DirectoryDefinition def) {
    return entries.access(user, def).atLeast(AccessLevel.WRITE);
  }

  private static Integer maxDays(DirectoryDefinition def) {
    return def.groups().maxMembership() > 0 ? def.groups().maxMembership() : null;
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  /** The last day a membership granted on {@code today} may have. */
  private static LocalDate latest(DirectoryDefinition def, LocalDate today) {
    int max = def.groups().maxMembership();
    return today.plusDays(max > 0 ? max : GroupAccess.MAX_MEMBERSHIP_DAYS);
  }

  /**
   * The end date asked for ({@code yyyy-MM-dd}; null when none is): today or a later day, no later
   * than the directory's longest membership allows.
   */
  private static LocalDate endDate(DirectoryDefinition def, String until, LocalDate today) {
    if (until == null || until.isBlank()) return null;
    LocalDate date;
    try {
      date = LocalDate.parse(until.trim());
    } catch (DateTimeParseException e) {
      throw ApiException.badRequest("The end date is not a date", Map.of("until", "Use a date like " + today.plusDays(30)));
    }
    if (date.isBefore(today)) throw ApiException.badRequest("The end date has passed", Map.of("until", "Choose today or a later day"));
    LocalDate latest = latest(def, today);
    if (date.isAfter(latest)) {
      int max = def.groups().maxMembership();
      throw ApiException.badRequest("The end date is too far away",
          Map.of("until", "No later than " + latest + (max > 0 ? ": this directory grants at most " + max + " days" : "")));
    }
    return date;
  }

  /** The end date of a membership granted today: the one chosen, else the directory's longest (none without a limit). */
  private static LocalDate granted(DirectoryDefinition def, LocalDate chosen, LocalDate today) {
    if (chosen != null) return chosen;
    return def.groups().maxMembership() > 0 ? latest(def, today) : null;
  }

  private Filter requestFilter(DirectoryDefinition def) {
    return filter(def, def.groups().requestFilter(), "request");
  }

  /** The filter of the groups joined without approval; null: none is. */
  private Filter openFilter(DirectoryDefinition def) {
    return filter(def, def.groups().openFilter(), "open-group");
  }

  private static Filter filter(DirectoryDefinition def, String f, String what) {
    if (f == null) return null;
    try {
      return Filter.create(f);
    } catch (LDAPException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The group directory \"" + def.name() + "\" has an invalid " + what + " filter");
    }
  }

  private Set<String> dnsMatching(DirectoryDefinition def, String q, Filter extra) {
    Set<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    for (DirectoryEntry e : entries.searchInNamespace(def, q, extra, SCAN_LIMIT, List.of("1.1")).entries()) out.add(normal(e.dn()));
    return out;
  }

  private static String normal(String dn) {
    try {
      return new com.unboundid.ldap.sdk.DN(dn).toNormalizedString();
    } catch (LDAPException e) {
      return dn.toLowerCase(Locale.ROOT);
    }
  }

  private List<String> displayAttributes(DirectoryDefinition def) {
    List<String> attrs = new ArrayList<>(List.of(def.titleAttribute(), "description", def.groups().ownerAttributeOrDefault()));
    return attrs.stream().distinct().toList();
  }

  private static String title(DirectoryDefinition def, DirectoryEntry e) {
    String t = e.first(def.titleAttribute());
    return t != null ? t : Dns.rdnValue(e.dn());
  }

  private Map<String, String> myPending(SessionUser user) {
    Map<String, String> out = new HashMap<>();
    for (AccessRequests.Request r : requests.list(r -> r.pending() && r.requester().equals(user.key()))) {
      out.put(r.definitionId() + "|" + normal(r.groupDn()), r.id());
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Lookup
  // ---------------------------------------------------------------------------

  /** Groups matching {@code q} (all when blank) in one definition, or every one the user can use. */
  public GroupList search(SessionUser user, String q, String definitionId) {
    List<DirectoryDefinition> defs = definitionId == null || definitionId.isBlank() ? definitions(user) : List.of(definition(user, definitionId));
    return list(user, defs, q, false);
  }

  /** The groups the user belongs to. */
  public GroupList mine(SessionUser user) {
    return list(user, definitions(user), null, true);
  }

  private GroupList list(SessionUser user, List<DirectoryDefinition> defs, String q, boolean onlyMine) {
    Map<String, String> pending = myPending(user);
    List<GroupSummary> out = new ArrayList<>();
    boolean truncated = false;
    for (DirectoryDefinition def : defs) {
      FormField member = memberField(def);
      Set<String> mine = dnsMatching(def, q, memberFilter(user, member));
      Map<String, String> until = new HashMap<>();
      for (MembershipExpiries.Expiry x : expiries.ofMember(def.id(), memberValue(user, member))) until.put(normal(x.groupDn()), x.until());
      Filter rf = requestFilter(def);
      Set<String> requestable = rf == null ? null : dnsMatching(def, q, rf);
      Filter of = openFilter(def);
      Set<String> open = of == null ? Set.of() : dnsMatching(def, q, of);
      boolean editor = canEdit(user, def);
      var found = entries.searchInNamespace(def, q, onlyMine ? memberFilter(user, member) : null, SEARCH_LIMIT, displayAttributes(def));
      truncated |= found.truncated();
      for (DirectoryEntry e : found.entries()) {
        String key = normal(e.dn());
        boolean isMember = mine.contains(key);
        boolean owner = names(e.values(def.groups().ownerAttributeOrDefault()), user);
        boolean takesRequests = requestable == null || requestable.contains(key);
        out.add(new GroupSummary(def.id(), def.name(), e.dn(), title(def, e), e.first("description"), isMember, owner, owner || editor,
            takesRequests, takesRequests && open.contains(key), pending.get(def.id() + "|" + key), isMember ? until.get(key) : null));
      }
    }
    out.sort(Comparator.comparing(GroupSummary::name, String.CASE_INSENSITIVE_ORDER).thenComparing(GroupSummary::definitionName));
    if (out.size() > SEARCH_LIMIT) {
      truncated = true;
      out = new ArrayList<>(out.subList(0, SEARCH_LIMIT));
    }
    return new GroupList(out, truncated);
  }

  /** One group, with its owners and how many members it has. */
  public GroupDetail detail(SessionUser user, String definitionId, String dn) {
    DirectoryDefinition def = definition(user, definitionId);
    FormField member = memberField(def);
    String ownerAttr = def.groups().ownerAttributeOrDefault();
    List<String> attrs = new ArrayList<>(displayAttributes(def));
    attrs.add(member.attribute());
    DirectoryEntry e = entries.readInNamespace(def, dn, attrs);
    String key = member.attribute().toLowerCase(Locale.ROOT);
    boolean partial = e.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range="));
    List<String> values = e.values(member.attribute());
    String mine = memberValue(user, member);
    boolean isMember = partial ? entries.matchesInNamespace(def, e.dn(), memberFilter(user, member))
        : values.stream().anyMatch(v -> Memberships.dnValued(member) ? Dns.equal(v, mine) : v.equalsIgnoreCase(mine));
    List<String> owners = e.values(ownerAttr);
    boolean owner = names(owners, user);
    boolean canManage = owner || canEdit(user, def);
    Filter rf = requestFilter(def);
    boolean requestable = rf == null || entries.matchesInNamespace(def, e.dn(), rf);
    Filter of = openFilter(def);
    boolean joinable = requestable && of != null && entries.matchesInNamespace(def, e.dn(), of);
    String pending = myPending(user).get(def.id() + "|" + normal(e.dn()));
    int open = canManage ? requests.list(r -> r.pending() && r.definitionId().equals(def.id()) && Dns.equal(r.groupDn(), e.dn())).size() : 0;
    MembershipExpiries.Expiry ends = isMember ? expiries.find(def.id(), e.dn(), mine) : null;
    GroupSummary summary = new GroupSummary(def.id(), def.name(), e.dn(), title(def, e), e.first("description"), isMember, owner, canManage,
        requestable, joinable, pending, ends == null ? null : ends.until());
    List<Person> ownerList = people(def, owners.stream().limit(MAX_OWNERS_SHOWN).toList(), true);
    return new GroupDetail(summary, ownerList, owners.size() > MAX_OWNERS_SHOWN, partial ? null : values.size(), member.attribute(),
        def.groups().requireReason(), def.groups().allowLeave(), open, canManage && def.groups().ownersManage(), maxDays(def));
  }

  /**
   * Names for DNs (people or groups) read from the definition's directory; values that aren't
   * DNs (memberUid) keep their value as the name.
   */
  private List<Person> people(DirectoryDefinition def, List<String> values, boolean dnValued) {
    Directories.Directory dir = directories.forDefinition(def);
    List<Person> out = new ArrayList<>();
    for (String v : values) {
      if (!dnValued || !Dns.isValid(v)) {
        out.add(new Person(v, null, v));
        continue;
      }
      String name = null;
      try {
        DirectoryEntry p = dir.ldap().get(v, List.of("displayName", "cn"));
        if (p != null) name = p.first("displayName") != null ? p.first("displayName") : p.first("cn");
      } catch (DirectoryException ignored) {
        // Not readable or gone: shown by its DN.
      }
      out.add(new Person(v, v, name != null ? name : Dns.rdnValue(v)));
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Managing a group (owners, editors)
  // ---------------------------------------------------------------------------

  /** The group's entry, when the user manages it (owner or editor); 403 otherwise. */
  private DirectoryEntry managed(SessionUser user, DirectoryDefinition def, String dn) {
    DirectoryEntry e = entries.readInNamespace(def, dn, List.of(def.titleAttribute(), def.groups().ownerAttributeOrDefault()));
    if (!names(e.values(def.groups().ownerAttributeOrDefault()), user) && !canEdit(user, def)) {
      throw ApiException.forbidden("Only the group's owners can do this");
    }
    return e;
  }

  /** A page of the group's members, filtered by {@code q} (value or name), for those who manage it. */
  public MemberPage members(SessionUser user, String definitionId, String dn, String q, int page) {
    DirectoryDefinition def = definition(user, definitionId);
    managed(user, def, dn);
    FormField member = memberField(def);
    // Every member, also of a group the directory returns in ranges (Active Directory, over 1,500).
    DirectoryEntry e = directories.forDefinition(def).ldap().withAllValues(entries.readInNamespace(def, dn, List.of(member.attribute())),
        member.attribute());
    String key = member.attribute().toLowerCase(Locale.ROOT);
    boolean partial = e.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range="));
    String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    List<String> values = e.values(member.attribute()).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    // Filtered by value (a DN's RDN holds the name, e.g. uid=alice); names are read for the page shown.
    List<String> matching = needle.isEmpty() ? values : values.stream().filter(v -> v.toLowerCase(Locale.ROOT).contains(needle)).toList();
    int p = Math.max(1, page);
    int from = Math.min((p - 1) * MEMBERS_PAGE, matching.size());
    List<String> shown = matching.subList(from, Math.min(from + MEMBERS_PAGE, matching.size()));
    Map<String, String> until = new HashMap<>();
    for (MembershipExpiries.Expiry x : expiries.ofGroup(def.id(), e.dn())) until.put(normal(x.member()), x.until());
    List<Member> items = people(def, shown, Memberships.dnValued(member)).stream()
        .map(m -> new Member(m.value(), m.dn(), m.name(), until.get(normal(m.value())))).toList();
    return new MemberPage(items, matching.size(), p, MEMBERS_PAGE, partial);
  }

  /**
   * Adds and removes members of a group the user manages. The members added belong to it until
   * the end of {@code until} (a day; none: the directory's longest membership, or for good without
   * one, which also forgets an end date they had).
   */
  public Memberships.Change changeMembers(SessionUser user, String definitionId, String dn, List<String> add, List<String> remove, String until) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry e = managed(user, def, dn);
    LocalDate today = today();
    LocalDate ends = distinct(add).isEmpty() ? null : granted(def, endDate(def, until, today), today);
    return changeWithEndDate(user, def, e.dn(), title(def, e), add, remove, ends, canEdit(user, def) ? null : VIA_OWNER);
  }

  /**
   * Sets, changes or (with none) clears the end date of a member of a group the user manages.
   * Where the directory limits memberships, a member's end date can't be cleared.
   */
  public Member setMemberUntil(SessionUser user, String definitionId, String dn, String member, String until) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry e = managed(user, def, dn);
    FormField field = memberField(def);
    String value = member == null ? "" : member.trim();
    if (value.isEmpty() || !entries.matchesInNamespace(def, e.dn(), Filter.createEqualityFilter(field.attribute(), value))) {
      throw ApiException.notFound("Not a member of this group");
    }
    LocalDate ends = endDate(def, until, today());
    int max = def.groups().maxMembership();
    if (ends == null && max > 0) {
      throw ApiException.badRequest("Memberships here have an end date", Map.of("until", "Required: this directory grants at most " + max + " days"));
    }
    MembershipExpiries.Expiry old = expiries.find(def.id(), e.dn(), value);
    AuditLog.Draft event = audit.event("group.membership-end-date").by(user).definition(def.id()).directory(entries.directoryId(def))
        .target("group", e.dn(), title(def, e)).detail("member", value)
        .changes(List.of(new AuditEvent.Change("until", old == null ? List.of() : List.of(old.until()), ends == null ? List.of() : List.of(ends.toString()))));
    if (ends == null) expiries.clear(def.id(), e.dn(), value);
    else expiries.set(List.of(record(def, e.dn(), title(def, e), value, ends, user)));
    event.success();
    return new Member(value, null, value, ends == null ? null : ends.toString());
  }

  private static MembershipExpiries.Expiry record(DirectoryDefinition def, String groupDn, String groupName, String member, LocalDate until,
      SessionUser by) {
    return new MembershipExpiries.Expiry(def.id(), groupDn, groupName, member, until.toString(), by == null ? null : by.key(),
        Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(), null);
  }

  /**
   * Changes a group's members for a caller that has authorized it ({@code via}, as for
   * {@link Memberships#change}), keeping the portal's end dates in step: members added get
   * {@code until} (none: an end date they had is forgotten), members removed lose theirs. The end
   * dates are recorded before the group changes, so a membership never outlasts what was granted
   * because recording failed; when the directory refuses the change they are put back.
   */
  private Memberships.Change changeWithEndDate(SessionUser user, DirectoryDefinition def, String groupDn, String groupName, List<String> add,
      List<String> remove, LocalDate until, String via) {
    List<String> adding = distinct(add);
    List<String> removing = distinct(remove);
    String attribute = memberField(def).attribute();
    if (until == null) {
      Memberships.Change change = memberships.change(user, def, groupDn, attribute, add, remove, via);
      List<String> forget = new ArrayList<>(adding);
      forget.addAll(removing);
      expiries.clear(def.id(), groupDn, forget);
      return change;
    }
    Map<String, MembershipExpiries.Expiry> before = new HashMap<>();
    for (MembershipExpiries.Expiry x : expiries.ofGroup(def.id(), groupDn)) before.put(normal(x.member()), x);
    expiries.set(adding.stream().map(v -> record(def, groupDn, groupName, v, until, user)).toList());
    Memberships.Change change;
    try {
      change = memberships.change(user, def, groupDn, attribute, add, remove, via);
    } catch (RuntimeException e) {
      expiries.clear(def.id(), groupDn, adding.stream().filter(v -> !before.containsKey(normal(v))).toList());
      expiries.set(adding.stream().map(v -> before.get(normal(v))).filter(x -> x != null).toList());
      throw e;
    }
    Set<String> added = new TreeSet<>(adding.stream().map(GroupService::normal).toList());
    expiries.clear(def.id(), groupDn, removing.stream().filter(v -> !added.contains(normal(v))).toList());
    try {
      audit.event("group.membership-end-date").by(user).definition(def.id()).directory(entries.directoryId(def)).target("group", groupDn, groupName)
          .detail("members", String.join("; ", adding.stream().limit(20).toList())).detail("count", adding.size())
          .detail("until.new", until.toString()).detail("via", via).success();
    } catch (RuntimeException e) {
      // The group has changed (and that is audited): the caller must not undo its own work over this.
      log.error("Could not record the end date {} set in {}: {}", until, LogSafe.of(groupDn), LogSafe.of(e.getMessage()));
    }
    return change;
  }

  /**
   * Adds people to and removes values (people or groups) from the group's owners, for those who
   * manage it, when its directory lets owners do so. The group keeps at least one owner.
   */
  public Memberships.Change changeOwners(SessionUser user, String definitionId, String dn, List<String> add, List<String> remove) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry e = managed(user, def, dn);
    if (!def.groups().ownersManage()) throw ApiException.forbidden("This directory's owners can't change a group's owners");
    String attr = def.groups().ownerAttributeOrDefault();
    FormField field = entries.form(def).fields().stream().filter(f -> f.multiValued() && f.attribute().equalsIgnoreCase(attr)).findFirst()
        .orElseThrow(() -> ApiException.badRequest("This directory's form has no field for the owner attribute (" + attr + ")"));
    List<String> present = e.values(attr);
    List<String> toAdd = distinct(add).stream().filter(v -> present.stream().noneMatch(p -> sameOwner(p, v))).toList();
    List<String> wanted = distinct(remove);
    List<String> toRemove = present.stream().filter(p -> wanted.stream().anyMatch(v -> sameOwner(p, v))).toList();
    if (toAdd.isEmpty() && toRemove.isEmpty()) throw ApiException.badRequest("Nothing to add or remove");
    Directories.Directory dir = directories.forDefinition(def);
    for (String v : toAdd) {
      if (People.byDn(dir, v) == null) throw ApiException.badRequest("\"" + (v.length() > 80 ? v.substring(0, 80) + "..." : v) + "\" is not a person of this directory");
    }
    if (present.size() - toRemove.size() + toAdd.size() < 1) throw ApiException.badRequest("A group keeps at least one owner: add another one first");
    EntryService.ValueChange c = entries.changeValues(user, def, e.dn(), field, toAdd, toRemove, true, canEdit(user, def) ? null : VIA_OWNERS);
    return new Memberships.Change(c.dn(), c.attribute(), c.added(), c.removed(), c.partial());
  }

  private static boolean sameOwner(String a, String b) {
    return Dns.isValid(a) && Dns.isValid(b) ? Dns.equal(a, b) : a.equalsIgnoreCase(b);
  }

  private static List<String> distinct(List<String> values) {
    return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
  }

  /** People of the group's directory to add, for those who manage it: {@code value} is what the member attribute holds. */
  public List<Person> findPeople(SessionUser user, String definitionId, String dn, String q) {
    DirectoryDefinition def = definition(user, definitionId);
    managed(user, def, dn);
    boolean dnValued = Memberships.dnValued(memberField(def));
    List<Person> out = new ArrayList<>();
    for (SubjectSearchResult r : People.search(directories.forDefinition(def), q, 20)) {
      out.add(new Person(dnValued ? r.dn() : r.id(), r.dn(), r.label()));
    }
    return out;
  }

  /**
   * Looks up a pasted list of people for those who manage the group: each line is a login name, an
   * e-mail address or a DN, and must name exactly one person of the group's directory. Blank and
   * repeated lines are skipped; a person named twice is listed once.
   */
  public ResolvedPeople resolvePeople(SessionUser user, String definitionId, String dn, List<String> lines) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry group = managed(user, def, dn);
    FormField member = memberField(def);
    boolean dnValued = Memberships.dnValued(member);
    Directories.Directory dir = directories.forDefinition(def);
    Set<String> members = new TreeSet<>();
    for (String v : dir.ldap().withAllValues(entries.readInNamespace(def, group.dn(), List.of(member.attribute())), member.attribute())
        .values(member.attribute())) {
      members.add(normal(v));
    }
    List<FoundPerson> found = new ArrayList<>();
    List<String> notFound = new ArrayList<>();
    List<AmbiguousLine> ambiguous = new ArrayList<>();
    Set<String> seen = new TreeSet<>();
    for (String line : distinct(lines)) {
      List<SubjectSearchResult> matches;
      if (Dns.isValid(line) && line.contains("=")) {
        SubjectSearchResult p = People.byDn(dir, line);
        matches = p == null ? List.of() : List.of(p);
      } else {
        matches = People.exact(dir, line, 5);
      }
      if (matches.isEmpty()) {
        notFound.add(line);
      } else if (matches.size() > 1) {
        ambiguous.add(new AmbiguousLine(line, matches.stream().map(SubjectSearchResult::label).toList()));
      } else if (seen.add(normal(matches.get(0).dn()))) {
        SubjectSearchResult p = matches.get(0);
        String value = dnValued ? p.dn() : p.id();
        found.add(new FoundPerson(line, value, p.dn(), p.label(), members.contains(normal(value))));
      }
    }
    return new ResolvedPeople(found, notFound, ambiguous);
  }

  // ---------------------------------------------------------------------------
  // Joining and leaving
  // ---------------------------------------------------------------------------

  /** Asks to join a group (it must take requests, and the user mustn't be a member already). */
  public AccessRequests.Request request(SessionUser user, String definitionId, String dn, String reason) {
    return request(user, definitionId, dn, reason, null);
  }

  /** As above, for access until the end of {@code until} (a day; none: no end asked, the directory's limit applies). */
  public AccessRequests.Request request(SessionUser user, String definitionId, String dn, String reason, String until) {
    DirectoryDefinition def = definition(user, definitionId);
    GroupAccess settings = def.groups();
    String why = reason == null || reason.isBlank() ? null : reason.trim();
    if (why != null && why.length() > MAX_REASON) throw ApiException.badRequest("The reason is too long", Map.of("reason", "At most " + MAX_REASON + " characters"));
    if (why == null && settings.requireReason()) throw ApiException.badRequest("Say why you need access", Map.of("reason", "Required"));
    LocalDate ends = endDate(def, until, today());
    String asked = ends == null ? null : ends.toString();
    GroupDetail d = detail(user, def.id(), dn);
    AuditLog.Draft event = audit.event("group.access-requested").by(user).definition(def.id()).directory(entries.directoryId(def))
        .target("group", d.group().dn(), d.group().name());
    if (d.group().member()) throw new ApiException(HttpStatus.CONFLICT, "You are already a member of this group");
    if (!d.group().requestable()) throw ApiException.forbidden("This group doesn't take requests");
    if (d.group().open()) throw new ApiException(HttpStatus.CONFLICT, "This group is open: join it without a request");
    FormField member = memberField(def);
    AccessRequests.Request r = requests.add(def.id(), d.group().dn(), d.group().name(), user.key(), user.dn(), displayName(user),
        memberValue(user, member), why, asked, MAX_PENDING_PER_USER);
    event.detail("request", r.id()).detail("reason", why).detail("until", asked).success();
    return r;
  }

  /**
   * Adds the user to an open group at once (it must take requests and match the directory's
   * open-group filter), for the directory's longest membership when it has one. A request the user
   * made before the group was opened is withdrawn.
   */
  public Memberships.Change join(SessionUser user, String definitionId, String dn) {
    DirectoryDefinition def = definition(user, definitionId);
    GroupDetail d = detail(user, def.id(), dn);
    if (d.group().member()) throw new ApiException(HttpStatus.CONFLICT, "You are already a member of this group");
    if (!d.group().open()) throw ApiException.forbidden("This group can't be joined without its owners' approval");
    Memberships.Change change = changeWithEndDate(user, def, d.group().dn(), d.group().name(), List.of(memberValue(user, memberField(def))),
        List.of(), granted(def, null, today()), VIA_JOIN);
    if (d.group().pendingRequestId() != null) {
      try {
        cancel(user, d.group().pendingRequestId());
      } catch (ApiException e) {
        // Decided meanwhile: nothing left to withdraw.
      }
    }
    return change;
  }

  /** Withdraws the user's own open request. */
  public AccessRequests.Request cancel(SessionUser user, String id) {
    AccessRequests.Request r = requests.find(id);
    if (r == null || !r.requester().equals(user.key())) throw ApiException.notFound("Request not found");
    AccessRequests.Request closed = requests.close(id, AccessRequests.CANCELLED, user.key(), displayName(user), null);
    audit.event("group.access-cancelled").by(user).definition(r.definitionId()).target("group", r.groupDn(), r.groupName())
        .detail("request", r.id()).success();
    return closed;
  }

  /** Removes the user from a group, when its directory lets members leave. */
  public Memberships.Change leave(SessionUser user, String definitionId, String dn) {
    DirectoryDefinition def = definition(user, definitionId);
    if (!def.groups().allowLeave()) throw ApiException.forbidden("Ask the group's owners to remove you");
    FormField member = memberField(def);
    DirectoryEntry e = entries.readInNamespace(def, dn, List.of(def.titleAttribute()));
    if (!entries.matchesInNamespace(def, e.dn(), memberFilter(user, member))) throw new ApiException(HttpStatus.CONFLICT, "You are not a member of this group");
    return changeWithEndDate(user, def, e.dn(), title(def, e), List.of(), List.of(memberValue(user, member)), null, VIA_LEAVE);
  }

  // ---------------------------------------------------------------------------
  // Reviewing requests
  // ---------------------------------------------------------------------------

  /** The groups (normalized DNs) of each definition the user owns, read once per definition. */
  private final class Reviewer {
    final SessionUser user;
    final Map<String, Boolean> editors = new HashMap<>();
    final Map<String, Set<String>> owned = new HashMap<>();
    final Map<String, DirectoryDefinition> defs = new LinkedHashMap<>();

    Reviewer(SessionUser user) {
      this.user = user;
      store.snapshot().definitions.stream().filter(DirectoryDefinition::groupAccess).forEach(d -> defs.put(d.id(), d));
    }

    boolean canDecide(AccessRequests.Request r) {
      DirectoryDefinition def = defs.get(r.definitionId());
      if (def == null || !directories.available(def.directory())) return false;
      if (editors.computeIfAbsent(def.id(), k -> canEdit(user, def))) return true;
      if (!directories.same(def.directory(), user.directory())) return false;
      return owned.computeIfAbsent(def.id(), k -> {
        try {
          return dnsMatching(def, null, ownerFilter(user, def));
        } catch (DirectoryException e) {
          return Set.of();
        }
      }).contains(normal(r.groupDn()));
    }

    String name(String definitionId) {
      DirectoryDefinition def = defs.get(definitionId);
      return def == null ? null : def.name();
    }
  }

  /**
   * Requests: {@code mine} (the user's own), {@code review} (open requests the user may decide,
   * never their own), {@code decided} (approved and rejected ones the user decided; administrators:
   * all of them).
   */
  public List<RequestView> requests(SessionUser user, String scope) {
    Reviewer reviewer = new Reviewer(user);
    List<RequestView> out = new ArrayList<>();
    switch (scope == null ? "mine" : scope) {
      case "mine" -> {
        for (AccessRequests.Request r : requests.list(r -> r.requester().equals(user.key()))) out.add(new RequestView(r, reviewer.name(r.definitionId()), false));
      }
      case "review" -> {
        for (AccessRequests.Request r : requests.list(r -> r.pending() && !r.requester().equals(user.key()))) {
          if (reviewer.canDecide(r)) out.add(new RequestView(r, reviewer.name(r.definitionId()), true));
        }
      }
      case "decided" -> {
        for (AccessRequests.Request r : requests.list(r -> (AccessRequests.APPROVED.equals(r.status()) || AccessRequests.REJECTED.equals(r.status()))
            && (user.isAdmin() || user.key().equals(r.decidedBy())))) {
          out.add(new RequestView(r, reviewer.name(r.definitionId()), false));
        }
      }
      default -> throw ApiException.badRequest("Unknown scope");
    }
    return out;
  }

  /** Open requests in a group, for those who manage it. */
  public List<RequestView> groupRequests(SessionUser user, String definitionId, String dn) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry e = managed(user, def, dn);
    return requests.list(r -> r.pending() && r.definitionId().equals(def.id()) && Dns.equal(r.groupDn(), e.dn())).stream()
        .map(r -> new RequestView(r, def.name(), !r.requester().equals(user.key()))).toList();
  }

  public Summary summary(SessionUser user) {
    Reviewer reviewer = new Reviewer(user);
    int review = 0;
    int mine = 0;
    for (AccessRequests.Request r : requests.list(AccessRequests.Request::pending)) {
      if (r.requester().equals(user.key())) mine++;
      else if (reviewer.canDecide(r)) review++;
    }
    return new Summary(review, mine);
  }

  /**
   * Approves (adding the requester to the group) or rejects an open request. Only the group's
   * owners and those who may edit its definition decide, and never on their own request. The
   * request stays open when the group can't be changed (e.g. the directory refused it).
   */
  public AccessRequests.Request decide(SessionUser user, String id, boolean approve, String note) {
    return decide(user, id, approve, note, null);
  }

  /**
   * As above. An approval may shorten the access: {@code until} (a day) is no later than the day
   * asked for. Without it the membership ends when the requester asked, else after the directory's
   * longest membership, else never.
   */
  public AccessRequests.Request decide(SessionUser user, String id, boolean approve, String note, String until) {
    AccessRequests.Request r = requests.find(id);
    Reviewer reviewer = new Reviewer(user);
    if (r == null || (!r.requester().equals(user.key()) && !reviewer.canDecide(r))) throw ApiException.notFound("Request not found");
    if (r.requester().equals(user.key())) throw ApiException.forbidden("You can't decide your own request");
    if (!r.pending()) throw new ApiException(HttpStatus.CONFLICT, "This request was already " + r.status());
    String why = note == null || note.isBlank() ? null : note.trim();
    if (why != null && why.length() > MAX_REASON) throw ApiException.badRequest("The note is too long", Map.of("note", "At most " + MAX_REASON + " characters"));
    DirectoryDefinition def = managedDefinition(user, r.definitionId());
    LocalDate ends = approve ? approvedUntil(def, r, until) : null;
    String granted = ends == null ? null : ends.toString();
    AuditLog.Draft event = audit.event(approve ? "group.access-approved" : "group.access-rejected").by(user).definition(def.id())
        .directory(entries.directoryId(def)).target("group", r.groupDn(), r.groupName()).detail("request", r.id())
        .detail("requester", r.requesterDn() != null ? r.requesterDn() : r.requester()).detail("note", why).detail("until", granted);
    // Closed first (409 when it was cancelled or decided meanwhile), so the group is changed only for
    // a request this decision closed; reopened when the directory refuses the change.
    AccessRequests.Request closed = requests.close(id, approve ? AccessRequests.APPROVED : AccessRequests.REJECTED, user.key(), displayName(user), why,
        granted);
    if (approve) {
      try {
        changeWithEndDate(user, def, r.groupDn(), r.groupName(), List.of(r.member()), List.of(), ends, VIA_REQUEST);
      } catch (RuntimeException e) {
        requests.reopen(closed);
        throw e;
      }
    }
    event.success();
    return closed;
  }

  /** The end date an approval today gives the request's membership (null: none); see {@link #decide}. */
  private static LocalDate approvedUntil(DirectoryDefinition def, AccessRequests.Request r, String until) {
    LocalDate today = today();
    LocalDate asked = r.until() == null ? null : LocalDate.parse(r.until());
    LocalDate chosen = endDate(def, until, today);
    if (chosen != null && asked != null && chosen.isAfter(asked)) {
      throw ApiException.badRequest("The access asked for can be shortened, not extended", Map.of("until", "No later than " + asked + ", the day asked for"));
    }
    LocalDate ends = granted(def, chosen != null ? chosen : asked, today);
    // Asked for before the directory's limit was lowered: the limit holds.
    if (ends != null && ends.isAfter(latest(def, today))) ends = latest(def, today);
    if (ends != null && ends.isBefore(today)) {
      throw new ApiException(HttpStatus.CONFLICT, "Access was asked for until " + ends + ", which has passed: reject the request");
    }
    return ends;
  }

  /**
   * Removes the members whose end date has passed, as of {@code now} (days are UTC): each is the
   * usual {@code entry.update} (by the system, {@code details.via} {@value #VIA_EXPIRED}, with the
   * membership webhook) and a {@code group.membership-expired} event; returns how many were
   * removed. A record whose member is no longer in the group (removed elsewhere), or whose group
   * or definition is gone, is dropped. A member that can't be removed (the directory refuses, the
   * group would be left empty) is recorded as a failure and tried again a day later.
   */
  public int expireMemberships(Instant now) {
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Map<String, DirectoryDefinition> defs = new HashMap<>();
    store.snapshot().definitions.forEach(d -> defs.put(d.id(), d));
    Map<String, List<MembershipExpiries.Expiry>> byGroup = new LinkedHashMap<>();
    for (MembershipExpiries.Expiry x : expiries.all()) byGroup.computeIfAbsent(x.definitionId() + "|" + normal(x.groupDn()), k -> new ArrayList<>()).add(x);
    int removed = 0;
    for (List<MembershipExpiries.Expiry> records : byGroup.values()) {
      MembershipExpiries.Expiry first = records.get(0);
      DirectoryDefinition def = defs.get(first.definitionId());
      if (def == null || def.groups() == null) {
        records.forEach(expiries::remove);
        continue;
      }
      if (!directories.available(def.directory())) continue;
      FormField field;
      Set<String> present;
      try {
        field = memberField(def);
        DirectoryEntry e = directories.forDefinition(def).ldap().withAllValues(entries.readInNamespace(def, first.groupDn(), List.of(field.attribute())),
            field.attribute());
        String key = field.attribute().toLowerCase(Locale.ROOT);
        // Null: the directory returned the members in part, so who is absent isn't known.
        present = e.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range=")) ? null
            : new TreeSet<>(e.values(field.attribute()).stream().map(GroupService::normal).toList());
      } catch (ApiException e) {
        if (e.status() == HttpStatus.NOT_FOUND) records.forEach(expiries::remove);
        continue;
      } catch (RuntimeException e) {
        log.warn("Could not read {} to end its time-limited memberships: {}", LogSafe.of(first.groupDn()), LogSafe.of(e.getMessage()));
        continue;
      }
      for (MembershipExpiries.Expiry x : records) {
        boolean due = x.untilDate().isBefore(today);
        if (present != null && !present.contains(normal(x.member()))) {
          // A record just written waits for its membership (it is recorded first).
          if (due || Instant.parse(x.grantedAt()).isBefore(now.minus(EXPIRY_GRACE))) expiries.remove(x);
          continue;
        }
        if (!due || (x.lastAttempt() != null && Instant.parse(x.lastAttempt()).isAfter(now.minus(EXPIRY_RETRY)))) continue;
        if (expire(def, field, x, now)) removed++;
      }
    }
    return removed;
  }

  /** Removes one member whose end date has passed; false when they were not removed. */
  private boolean expire(DirectoryDefinition def, FormField field, MembershipExpiries.Expiry x, Instant now) {
    // Changed meanwhile (an owner extended it): not this record's to act on any more.
    MembershipExpiries.Expiry current = expiries.find(x.definitionId(), x.groupDn(), x.member());
    if (current == null || !current.until().equals(x.until())) return false;
    AuditLog.Draft event = audit.event("group.membership-expired").system().definition(def.id()).directory(entries.directoryId(def))
        .target("group", x.groupDn(), x.groupName()).detail("member", x.member()).detail("until", x.until());
    try {
      Memberships.Change change = memberships.change(null, def, x.groupDn(), field.attribute(), List.of(), List.of(x.member()), VIA_EXPIRED);
      expiries.remove(x);
      if (change.removed().isEmpty() && !change.partial()) return false;
      event.success();
      return true;
    } catch (RuntimeException e) {
      expiries.attempted(x, now);
      event.failed(e.getMessage());
      log.warn("Could not remove {} from {} after its end date {}: {}", LogSafe.of(x.member()), LogSafe.of(x.groupDn()), x.until(),
          LogSafe.of(e.getMessage()));
      return false;
    }
  }

  /**
   * Closes the open requests nobody answered within their definition's {@code requestExpiryDays},
   * as of {@code now}, recording {@code group.access-expired} for each; returns how many. A request
   * decided or withdrawn meanwhile is left alone, so each is expired (and recorded) once.
   */
  public int expireRequests(Instant now) {
    Map<String, DirectoryDefinition> defs = new HashMap<>();
    store.snapshot().definitions.stream().filter(DirectoryDefinition::groupAccess).forEach(d -> defs.put(d.id(), d));
    int expired = 0;
    for (AccessRequests.Request r : requests.list(AccessRequests.Request::pending)) {
      DirectoryDefinition def = defs.get(r.definitionId());
      int days = def == null ? 0 : def.groups().requestExpiry();
      if (days <= 0 || Instant.parse(r.createdAt()).plus(days, ChronoUnit.DAYS).isAfter(now)) continue;
      try {
        requests.expire(r.id(), now, days);
      } catch (ApiException e) {
        continue;
      }
      audit.event("group.access-expired").system().definition(def.id()).directory(entries.directoryId(def))
          .target("group", r.groupDn(), r.groupName()).detail("request", r.id())
          .detail("requester", r.requesterDn() != null ? r.requesterDn() : r.requester()).detail("afterDays", days).success();
      expired++;
    }
    return expired;
  }

  private static String displayName(SessionUser user) {
    return user.displayName() != null && !user.displayName().isBlank() ? user.displayName() : user.username();
  }
}
