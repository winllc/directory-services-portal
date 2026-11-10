package com.winllc.dsp.groups;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
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
import com.winllc.dsp.web.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Self-service group access (see {@link GroupAccess}): people look up the groups of definitions
 * that have it enabled, in the LDAP directory they signed in to, and ask to join; a group's owners
 * (its owner attribute names them, or a group they belong to) and anyone who may edit the
 * definition decide, and manage the group's members. Looking groups up needs no grant on the
 * definition: only names, descriptions and owners are shown, and the member list only to those who
 * manage the group.
 *
 * <p>Membership changes go through {@link Memberships} (value-level adds and deletes, the form's
 * validation, the {@code entry.update} audit event and the {@code group.membership_changed}
 * webhook). An owner without write access to the definition changes only their own groups, and
 * the audit event says so ({@code details.via}).
 */
@Service
public class GroupService {
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

  public record GroupDirectory(String id, String name, String slug, String icon, boolean requireReason, boolean allowLeave) {}

  /**
   * A group as listed. {@code member}: the user belongs to it; {@code owner}: its owner attribute
   * names the user (or a group of theirs); {@code canManage}: owner, or may edit the definition;
   * {@code requestable}: it takes requests; {@code pendingRequestId}: the user's open request.
   */
  public record GroupSummary(String definitionId, String definitionName, String dn, String name, String description, boolean member,
      boolean owner, boolean canManage, boolean requestable, String pendingRequestId) {}

  /** A person or group named by a group (owner, member), with the name the directory gives it. */
  public record Person(String value, String dn, String name) {}

  public record GroupList(List<GroupSummary> groups, boolean truncated) {}

  /** {@code memberCount} is null when the directory returned the members in part (Active Directory, over 1,500). */
  public record GroupDetail(GroupSummary group, List<Person> owners, boolean moreOwners, Integer memberCount, String memberAttribute,
      boolean requireReason, boolean allowLeave, int pendingRequests) {}

  public record MemberPage(List<Person> items, int total, int page, int pageSize, boolean partial) {}

  /** A request as shown: {@code canDecide} when the viewer may approve or reject it. */
  public record RequestView(AccessRequests.Request request, String definitionName, boolean canDecide) {}

  public record Summary(int toReview, int myPending) {}

  private final ConfigStore store;
  private final EntryService entries;
  private final Memberships memberships;
  private final Directories directories;
  private final AccessRequests requests;
  private final AuditLog audit;

  public GroupService(ConfigStore store, EntryService entries, Memberships memberships, Directories directories, AccessRequests requests,
      AuditLog audit) {
    this.store = store;
    this.entries = entries;
    this.memberships = memberships;
    this.directories = directories;
    this.requests = requests;
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
        d.groups().allowLeave())).toList();
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

  private Filter requestFilter(DirectoryDefinition def) {
    String f = def.groups().requestFilter();
    if (f == null) return null;
    try {
      return Filter.create(f);
    } catch (LDAPException e) {
      throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The group directory \"" + def.name() + "\" has an invalid request filter");
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
      Filter rf = requestFilter(def);
      Set<String> requestable = rf == null ? null : dnsMatching(def, q, rf);
      boolean editor = canEdit(user, def);
      var found = entries.searchInNamespace(def, q, onlyMine ? memberFilter(user, member) : null, SEARCH_LIMIT, displayAttributes(def));
      truncated |= found.truncated();
      for (DirectoryEntry e : found.entries()) {
        String key = normal(e.dn());
        boolean isMember = mine.contains(key);
        boolean owner = names(e.values(def.groups().ownerAttributeOrDefault()), user);
        out.add(new GroupSummary(def.id(), def.name(), e.dn(), title(def, e), e.first("description"), isMember, owner, owner || editor,
            requestable == null || requestable.contains(key), pending.get(def.id() + "|" + key)));
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
    String pending = myPending(user).get(def.id() + "|" + normal(e.dn()));
    int open = canManage ? requests.list(r -> r.pending() && r.definitionId().equals(def.id()) && Dns.equal(r.groupDn(), e.dn())).size() : 0;
    GroupSummary summary = new GroupSummary(def.id(), def.name(), e.dn(), title(def, e), e.first("description"), isMember, owner, canManage,
        requestable, pending);
    List<Person> ownerList = people(def, owners.stream().limit(MAX_OWNERS_SHOWN).toList(), true);
    return new GroupDetail(summary, ownerList, owners.size() > MAX_OWNERS_SHOWN, partial ? null : values.size(), member.attribute(),
        def.groups().requireReason(), def.groups().allowLeave(), open);
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
    DirectoryEntry e = entries.readInNamespace(def, dn, List.of(member.attribute()));
    String key = member.attribute().toLowerCase(Locale.ROOT);
    boolean partial = e.attributes().keySet().stream().anyMatch(k -> k.startsWith(key + ";range="));
    String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    List<String> values = e.values(member.attribute()).stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    // Filtered by value (a DN's RDN holds the name, e.g. uid=alice); names are read for the page shown.
    List<String> matching = needle.isEmpty() ? values : values.stream().filter(v -> v.toLowerCase(Locale.ROOT).contains(needle)).toList();
    int p = Math.max(1, page);
    int from = Math.min((p - 1) * MEMBERS_PAGE, matching.size());
    List<String> shown = matching.subList(from, Math.min(from + MEMBERS_PAGE, matching.size()));
    return new MemberPage(people(def, shown, Memberships.dnValued(member)), matching.size(), p, MEMBERS_PAGE, partial);
  }

  /** Adds and removes members of a group the user manages. */
  public Memberships.Change changeMembers(SessionUser user, String definitionId, String dn, List<String> add, List<String> remove) {
    DirectoryDefinition def = definition(user, definitionId);
    DirectoryEntry e = managed(user, def, dn);
    return memberships.change(user, def, e.dn(), memberField(def).attribute(), add, remove, canEdit(user, def) ? null : VIA_OWNER);
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

  // ---------------------------------------------------------------------------
  // Joining and leaving
  // ---------------------------------------------------------------------------

  /** Asks to join a group (it must take requests, and the user mustn't be a member already). */
  public AccessRequests.Request request(SessionUser user, String definitionId, String dn, String reason) {
    DirectoryDefinition def = definition(user, definitionId);
    GroupAccess settings = def.groups();
    String why = reason == null || reason.isBlank() ? null : reason.trim();
    if (why != null && why.length() > MAX_REASON) throw ApiException.badRequest("The reason is too long", Map.of("reason", "At most " + MAX_REASON + " characters"));
    if (why == null && settings.requireReason()) throw ApiException.badRequest("Say why you need access", Map.of("reason", "Required"));
    GroupDetail d = detail(user, def.id(), dn);
    AuditLog.Draft event = audit.event("group.access-requested").by(user).definition(def.id()).directory(entries.directoryId(def))
        .target("group", d.group().dn(), d.group().name());
    if (d.group().member()) throw new ApiException(HttpStatus.CONFLICT, "You are already a member of this group");
    if (!d.group().requestable()) throw ApiException.forbidden("This group doesn't take requests");
    FormField member = memberField(def);
    AccessRequests.Request r = requests.add(def.id(), d.group().dn(), d.group().name(), user.key(), user.dn(), displayName(user),
        memberValue(user, member), why, MAX_PENDING_PER_USER);
    event.detail("request", r.id()).detail("reason", why).success();
    return r;
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
    return memberships.change(user, def, e.dn(), member.attribute(), List.of(), List.of(memberValue(user, member)), VIA_LEAVE);
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
   * never their own), {@code decided} (closed ones the user decided; administrators: all of them).
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
        for (AccessRequests.Request r : requests.list(r -> !r.pending() && !AccessRequests.CANCELLED.equals(r.status())
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
    AccessRequests.Request r = requests.find(id);
    Reviewer reviewer = new Reviewer(user);
    if (r == null || (!r.requester().equals(user.key()) && !reviewer.canDecide(r))) throw ApiException.notFound("Request not found");
    if (r.requester().equals(user.key())) throw ApiException.forbidden("You can't decide your own request");
    if (!r.pending()) throw new ApiException(HttpStatus.CONFLICT, "This request was already " + r.status());
    String why = note == null || note.isBlank() ? null : note.trim();
    if (why != null && why.length() > MAX_REASON) throw ApiException.badRequest("The note is too long", Map.of("note", "At most " + MAX_REASON + " characters"));
    DirectoryDefinition def = managedDefinition(user, r.definitionId());
    AuditLog.Draft event = audit.event(approve ? "group.access-approved" : "group.access-rejected").by(user).definition(def.id())
        .directory(entries.directoryId(def)).target("group", r.groupDn(), r.groupName()).detail("request", r.id())
        .detail("requester", r.requesterDn() != null ? r.requesterDn() : r.requester()).detail("note", why);
    if (approve) {
      memberships.change(user, def, r.groupDn(), memberField(def).attribute(), List.of(r.member()), List.of(), VIA_REQUEST);
    }
    AccessRequests.Request closed = requests.close(id, approve ? AccessRequests.APPROVED : AccessRequests.REJECTED, user.key(), displayName(user), why);
    event.success();
    return closed;
  }

  private static String displayName(SessionUser user) {
    return user.displayName() != null && !user.displayName().isBlank() ? user.displayName() : user.username();
  }
}
