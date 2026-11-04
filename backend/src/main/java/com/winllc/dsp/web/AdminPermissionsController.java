package com.winllc.dsp.web;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.PermissionGrant;
import com.winllc.dsp.model.SubjectSearchResult;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.GrantInput;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read/write grants per directory definition, and user/group lookup for choosing subjects. */
@RestController
@RequestMapping("/api/admin")
public class AdminPermissionsController {
  private final ConfigStore store;
  private final EntryService entries;
  private final Directories directories;
  private final AuditLog audit;

  public AdminPermissionsController(ConfigStore store, EntryService entries, Directories directories, AuditLog audit) {
    this.store = store;
    this.entries = entries;
    this.directories = directories;
    this.audit = audit;
  }

  @GetMapping("/grants")
  public List<PermissionGrant> grants(@RequestParam(required = false) String definitionId) {
    return store.snapshot().grants.stream().filter(g -> definitionId == null || g.definitionId().equals(definitionId)).toList();
  }

  /** Create a grant, or update the access level of an existing grant for the same subject. */
  @PostMapping("/grants")
  public ResponseEntity<PermissionGrant> grant(@Valid @RequestBody GrantInput in) {
    DirectoryDefinition def = entries.definition(in.definitionId());
    if ("group".equals(in.subjectType()) && !Dns.isValid(in.subject())) {
      throw ApiException.badRequest("Group subjects must be DNs", Map.of("subject", "Invalid DN"));
    }
    if (def.readOnly() && "write".equals(in.access())) {
      throw ApiException.badRequest("Read-only directories cannot grant write access", Map.of("access", "Read only directory"));
    }
    // The subject's directory: as given, else the definition's.
    String subjectDirectory = in.directory() == null || in.directory().isBlank() ? def.directory() : in.directory();
    if (directories.find(subjectDirectory).isEmpty()) {
      throw ApiException.badRequest("Unknown LDAP directory", Map.of("directory", "No directory \"" + subjectDirectory + "\" is configured"));
    }
    String dirId = directories.canonicalId(subjectDirectory);
    AtomicReference<PermissionGrant> previous = new AtomicReference<>();
    PermissionGrant saved = store.update(d -> {
      for (int i = 0; i < d.grants.size(); i++) {
        PermissionGrant g = d.grants.get(i);
        if (g.definitionId().equals(in.definitionId()) && g.subjectType().equals(in.subjectType()) && g.subject().equalsIgnoreCase(in.subject())
            && directories.same(g.directory() != null ? g.directory() : def.directory(), dirId)) {
          PermissionGrant next = g.withAccess(in.access(), in.subjectLabel() != null ? in.subjectLabel() : g.subjectLabel());
          previous.set(g);
          d.grants.set(i, next);
          return next;
        }
      }
      PermissionGrant g = new PermissionGrant("grant-" + UUID.randomUUID(), in.definitionId(), in.subjectType(), in.subject().trim(),
          in.subjectLabel(), in.access(), Instant.now().toString(), dirId);
      d.grants.add(g);
      return g;
    });
    record("grant.save", def.id(), def.name(), previous.get(), saved);
    return ResponseEntity.status(HttpStatus.CREATED).body(saved);
  }

  @DeleteMapping("/grants/{id}")
  public ResponseEntity<Void> revoke(@PathVariable String id) {
    PermissionGrant removed = store.update(d -> {
      PermissionGrant g = d.grants.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Grant not found"));
      d.grants.remove(g);
      return g;
    });
    String defName = store.snapshot().definitions.stream().filter(d -> d.id().equals(removed.definitionId()))
        .map(DirectoryDefinition::name).findFirst().orElse(removed.definitionId());
    record("grant.revoke", removed.definitionId(), defName, removed, null);
    return ResponseEntity.noContent().build();
  }

  private void record(String action, String definitionId, String definitionName, PermissionGrant before, PermissionGrant after) {
    PermissionGrant g = after != null ? after : before;
    audit.event(action)
        .target("grant", g.id(), (g.subjectLabel() != null ? g.subjectLabel() : g.subject()) + " on " + definitionName)
        .definition(definitionId)
        .detail("subjectType", g.subjectType())
        .detail("subject", g.subject())
        .directory(g.directory())
        .changes(audit.diff(before, after, Set.of("id", "createdAt", "definitionId")))
        .success();
  }

  private static Filter userFilter(DirectoryProperties c, String usernameValue) {
    try {
      return Filter.create(c.getUserFilter().replace("{{username}}", usernameValue));
    } catch (LDAPException e) {
      throw new IllegalStateException("Invalid user filter template", e);
    }
  }

  /**
   * Search users (by login name, name or e-mail) and groups (by cn) in one LDAP directory
   * ({@code directory}; none: the primary one).
   */
  @GetMapping("/subjects")
  public List<SubjectSearchResult> subjects(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
      @RequestParam(required = false) String directory) {
    Directories.Directory dir = directories.require(directory);
    DirectoryProperties c = dir.config();
    String query = q == null ? "" : q.trim();
    if (query.length() > 100) query = query.substring(0, 100);
    String t = Filter.encodeValue(query);
    List<SubjectSearchResult> out = new ArrayList<>();

    if (!"group".equals(type)) {
      String ua = c.getUsernameAttribute();
      Filter filter = query.isEmpty()
          ? userFilter(c, "*")
          : Filter.createORFilter(
              userFilter(c, "*" + t + "*"),
              Filter.createANDFilter(userFilter(c, "*"), Filter.createORFilter(
                  Filter.createSubstringFilter("cn", null, new String[] {query}, null),
                  Filter.createSubstringFilter("mail", null, new String[] {query}, null))));
      for (DirectoryEntry e : dir.ldap().search(c.effectiveUserSearchBase(), SearchScope.SUB, filter, 25, List.of(ua, "cn", "displayName")).entries()) {
        String id = e.first(ua);
        if (id == null) continue;
        String name = e.first("displayName") != null ? e.first("displayName") : e.first("cn") != null ? e.first("cn") : id;
        out.add(new SubjectSearchResult("user", id, name + " (" + id + ")", e.dn()));
      }
    }
    if (!"user".equals(type)) {
      List<Filter> parts = new ArrayList<>(List.of(Filter.createORFilter(
          Filter.createEqualityFilter("objectClass", "groupOfNames"),
          Filter.createEqualityFilter("objectClass", "groupOfUniqueNames"),
          Filter.createEqualityFilter("objectClass", "group"),
          Filter.createEqualityFilter("objectClass", "posixGroup"))));
      if (!query.isEmpty()) parts.add(Filter.createSubstringFilter("cn", null, new String[] {query}, null));
      for (DirectoryEntry e : dir.ldap().search(c.effectiveGroupSearchBase(), SearchScope.SUB, Filter.createANDFilter(parts), 25, List.of("cn")).entries()) {
        out.add(new SubjectSearchResult("group", e.dn(), e.first("cn") != null ? e.first("cn") : Dns.rdnValue(e.dn()), e.dn()));
      }
    }
    return out;
  }
}
