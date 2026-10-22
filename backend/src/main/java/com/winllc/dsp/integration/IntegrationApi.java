package com.winllc.dsp.integration;

import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.RequiresFeature;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.EntryListResponse;
import com.winllc.dsp.model.FormField;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.service.Memberships;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The integration API, version 1: directory search and read, entry writes and group membership
 * for other systems, authenticated with an API token ({@link ApiTokenFilter}). Every call runs
 * through the same services as the portal's own pages, as the token's creator: the definition's
 * namespace, the creator's current permissions, form validation and the audit trail all apply,
 * narrowed further by the token's scopes and directories. Directories a token can't reach answer
 * 404, as if they didn't exist.
 */
@RestController
@RequiresFeature(LicensedFeature.DIRECTORY_EDITOR)
@RequestMapping(path = IntegrationApi.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME)
public class IntegrationApi {
  public static final String BASE = "/api/v1";
  public static final String OPENAPI_PATH = BASE + "/openapi";

  private final EntryService entries;
  private final Memberships memberships;
  private final ConfigStore store;

  public IntegrationApi(EntryService entries, Memberships memberships, ConfigStore store) {
    this.entries = entries;
    this.memberships = memberships;
    this.store = store;
  }

  /** A directory as the token sees it: {@code access} is read or write. */
  public record Directory(String id, String slug, String name, String description, String access, boolean readOnly) {}

  /** A field of the directory's form: the attributes an entry may be given. */
  public record Field(String attribute, String label, boolean multiValued, boolean required, boolean readOnly, String format) {}

  public record DirectoryDetail(Directory directory, String rdnAttribute, List<String> objectClasses, List<Field> fields) {}

  public record CreateEntry(@Size(max = 1024) String parentDn, @NotNull Map<String, Object> values) {}

  public record UpdateEntry(@NotNull Map<String, Object> values) {}

  public record MembershipChange(@Size(max = 128) String attribute, List<String> add, List<String> remove) {}

  // ---------------------------------------------------------------------------

  private static ApiTokenContext token() {
    return ApiTokenContext.current();
  }

  /** The definition, if this token may reach it and its creator can at least read it; else 404. */
  private DirectoryDefinition definition(SessionUser owner, String id) {
    DirectoryDefinition def = entries.definition(id);
    if (!token().covers(def.id()) || entries.access(owner, def) == AccessLevel.NONE) throw ApiException.notFound("Directory definition not found");
    return def;
  }

  private Directory describe(SessionUser owner, DirectoryDefinition def, ApiTokenContext t) {
    AccessLevel access = entries.access(owner, def);
    boolean canWrite = t.has(ApiScope.ENTRIES_WRITE) || t.has(ApiScope.ENTRIES_DELETE) || t.has(ApiScope.GROUPS_WRITE);
    if (!canWrite && access == AccessLevel.WRITE) access = AccessLevel.READ;
    return new Directory(def.id(), def.slug(), def.name(), def.description(), access.json(), def.readOnly());
  }

  private static String dn(String dn) {
    if (dn == null || dn.isBlank()) throw ApiException.badRequest("Query parameter \"dn\" is required");
    if (dn.length() > 1024 || !Dns.isValid(dn)) throw ApiException.badRequest("Invalid DN", Map.of("dn", "Invalid DN"));
    return dn.trim();
  }

  private static void readScope() {
    ApiTokenContext t = token();
    if (!t.has(ApiScope.ENTRIES_READ) && !t.has(ApiScope.GROUPS_READ)) t.require(ApiScope.ENTRIES_READ);
  }

  // ---------------------------------------------------------------------------

  @Tag(name = "Directories")
  @Operation(summary = "List the directories this token can reach", description = "Scope entries:read or groups:read.")
  @GetMapping("/directories")
  public List<Directory> directories(@AuthenticationPrincipal SessionUser owner) {
    readScope();
    ApiTokenContext t = token();
    return store.snapshot().definitions.stream()
        .filter(d -> t.covers(d.id()))
        .filter(d -> entries.access(owner, d) != AccessLevel.NONE)
        .map(d -> describe(owner, d, t))
        .sorted(Comparator.comparing(Directory::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @Tag(name = "Directories")
  @Operation(summary = "A directory and its form's fields", description = "Scope entries:read or groups:read.")
  @GetMapping("/directories/{id}")
  public DirectoryDetail directory(@AuthenticationPrincipal SessionUser owner, @Parameter(description = "Definition id or slug") @PathVariable String id) {
    readScope();
    DirectoryDefinition def = definition(owner, id);
    var form = entries.form(def);
    List<Field> fields = form.fields().stream()
        .filter(f -> !EntryService.isHidden(f.attribute()))
        .map(IntegrationApi::field)
        .toList();
    return new DirectoryDetail(describe(owner, def, token()), form.rdnAttribute(), form.objectClasses(), fields);
  }

  private static Field field(FormField f) {
    return new Field(f.attribute(), f.label(), f.multiValued(), f.required(), f.readOnly(), f.format() == null ? "plain" : f.format());
  }

  @Tag(name = "Entries")
  @Operation(summary = "Search a directory's entries",
      description = "Scope entries:read. Every word of q must match one of the directory's search attributes (substring). "
          + "Pages of up to 200; at most the first 1000 matches are returned (truncated is then true).")
  @GetMapping("/directories/{id}/entries")
  public EntryListResponse search(@AuthenticationPrincipal SessionUser owner, @PathVariable String id,
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    token().require(ApiScope.ENTRIES_READ);
    if (q != null && q.length() > 256) throw ApiException.badRequest("Search text is too long");
    if (sort != null && !sort.isBlank() && !sort.matches(Patterns.ATTRIBUTE_OR_DN)) throw ApiException.badRequest("Invalid sort attribute");
    if (page != null && (page < 1 || page > 10_000)) throw ApiException.badRequest("Invalid page");
    if (pageSize != null && (pageSize < 1 || pageSize > 200)) throw ApiException.badRequest("Invalid page size");
    DirectoryDefinition def = definition(owner, id);
    return entries.list(owner, def, new EntryService.ListQuery(q, page, pageSize, sort, "desc".equals(order) ? "desc" : "asc"));
  }

  @Tag(name = "Entries")
  @Operation(summary = "Read one entry", description = "Scope entries:read.")
  @GetMapping("/directories/{id}/entry")
  public DirectoryEntry read(@AuthenticationPrincipal SessionUser owner, @PathVariable String id, @Parameter(description = "The entry's DN", required = true) @RequestParam(required = false) String dn) {
    token().require(ApiScope.ENTRIES_READ);
    return entries.get(owner, definition(owner, id), dn(dn));
  }

  @Tag(name = "Entries")
  @Operation(summary = "Create an entry", description = "Scope entries:write. Values are validated against the directory's form.")
  @PostMapping("/directories/{id}/entries")
  public ResponseEntity<DirectoryEntry> create(@AuthenticationPrincipal SessionUser owner, @PathVariable String id,
      @Valid @RequestBody CreateEntry body) {
    token().require(ApiScope.ENTRIES_WRITE);
    return ResponseEntity.status(HttpStatus.CREATED).body(entries.create(owner, definition(owner, id), body.parentDn(), body.values()));
  }

  @Tag(name = "Entries")
  @Operation(summary = "Change an entry", description = "Scope entries:write. Only the attributes given change; an empty list clears one.")
  @PatchMapping("/directories/{id}/entry")
  public DirectoryEntry update(@AuthenticationPrincipal SessionUser owner, @PathVariable String id, @Parameter(description = "The entry's DN", required = true) @RequestParam(required = false) String dn,
      @Valid @RequestBody UpdateEntry body) {
    token().require(ApiScope.ENTRIES_WRITE);
    return entries.update(owner, definition(owner, id), dn(dn), body.values(), false);
  }

  @Tag(name = "Entries")
  @Operation(summary = "Delete an entry", description = "Scope entries:delete.")
  @DeleteMapping("/directories/{id}/entry")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal SessionUser owner, @PathVariable String id, @Parameter(description = "The entry's DN", required = true) @RequestParam(required = false) String dn) {
    token().require(ApiScope.ENTRIES_DELETE);
    entries.remove(owner, definition(owner, id), dn(dn));
    return ResponseEntity.noContent().build();
  }

  @Tag(name = "Groups")
  @Operation(summary = "A group's members",
      description = "Scope groups:read. attribute defaults to the form's member, uniqueMember or memberUid field.")
  @GetMapping("/directories/{id}/members")
  public Memberships.Members members(@AuthenticationPrincipal SessionUser owner, @PathVariable String id,
      @Parameter(description = "The entry's DN", required = true) @RequestParam(required = false) String dn, @RequestParam(required = false) String attribute) {
    token().require(ApiScope.GROUPS_READ);
    return memberships.read(owner, definition(owner, id), dn(dn), attribute);
  }

  @Tag(name = "Groups")
  @Operation(summary = "Add and remove group members",
      description = "Scope groups:write. One change of the group entry; members already present (or already absent) are skipped.")
  @PostMapping("/directories/{id}/members")
  public Memberships.Change changeMembers(@AuthenticationPrincipal SessionUser owner, @PathVariable String id,
      @Parameter(description = "The entry's DN", required = true) @RequestParam(required = false) String dn, @Valid @RequestBody MembershipChange body) {
    token().require(ApiScope.GROUPS_WRITE);
    return memberships.change(owner, definition(owner, id), dn(dn), body.attribute(), body.add(), body.remove());
  }
}
