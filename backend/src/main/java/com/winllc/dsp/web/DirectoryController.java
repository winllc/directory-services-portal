package com.winllc.dsp.web;

import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DefinitionSummary;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.EntryListResponse;
import com.winllc.dsp.model.OptionItem;
import com.winllc.dsp.model.Patterns;
import com.winllc.dsp.model.SelfEntryResult;
import com.winllc.dsp.model.SessionUser;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.EntryRequests;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** End-user API: directories the user can see, entry CRUD, drop-down options and "My profile". */
@RestController
@RequestMapping("/api")
public class DirectoryController {
  private final EntryService entries;
  private final ConfigStore store;

  public DirectoryController(EntryService entries, ConfigStore store) {
    this.entries = entries;
    this.store = store;
  }

  static String dnParam(String dn) {
    if (dn == null || dn.isBlank()) throw ApiException.badRequest("Query parameter \"dn\" is required");
    if (dn.length() > 1024 || !Dns.isValid(dn)) throw ApiException.badRequest("Invalid DN", Map.of("dn", "Invalid DN"));
    return dn.trim();
  }

  @GetMapping("/definitions")
  public List<DefinitionSummary> definitions(@AuthenticationPrincipal SessionUser user) {
    return store.snapshot().definitions.stream()
        .map(d -> entries.summarize(user, d))
        .filter(s -> s.access() != AccessLevel.NONE)
        .sorted(Comparator.comparing(s -> s.definition().name(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @GetMapping("/definitions/{id}")
  public Map<String, Object> definition(@AuthenticationPrincipal SessionUser user, @PathVariable String id) {
    DirectoryDefinition def = entries.definition(id);
    DefinitionSummary summary = entries.summarize(user, def);
    if (summary.access() == AccessLevel.NONE) throw ApiException.forbidden("You do not have access to this directory");
    return Map.of("definition", summary, "form", entries.form(def));
  }

  @GetMapping("/directories/{id}/entries")
  public EntryListResponse list(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable String id,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer pageSize,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order) {
    if (q != null && q.length() > 256) throw ApiException.badRequest("Search text is too long");
    if (sort != null && !sort.isBlank() && !sort.matches(Patterns.ATTRIBUTE_OR_DN)) throw ApiException.badRequest("Invalid sort attribute");
    if (page != null && (page < 1 || page > 10_000)) throw ApiException.badRequest("Invalid page");
    if (pageSize != null && (pageSize < 1 || pageSize > 200)) throw ApiException.badRequest("Invalid page size");
    return entries.list(user, entries.definition(id), new EntryService.ListQuery(q, page, pageSize, sort, "desc".equals(order) ? "desc" : "asc"));
  }

  @GetMapping("/directories/{id}/entry")
  public DirectoryEntry get(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestParam(required = false) String dn) {
    return entries.get(user, entries.definition(id), dnParam(dn));
  }

  @PostMapping("/directories/{id}/entries")
  public ResponseEntity<DirectoryEntry> create(
      @AuthenticationPrincipal SessionUser user, @PathVariable String id, @Valid @RequestBody EntryRequests.Create body) {
    return ResponseEntity.status(HttpStatus.CREATED).body(entries.create(user, entries.definition(id), body.parentDn(), body.values()));
  }

  @PutMapping("/directories/{id}/entry")
  public DirectoryEntry update(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable String id,
      @RequestParam(required = false) String dn,
      @Valid @RequestBody EntryRequests.Update body) {
    return entries.update(user, entries.definition(id), dnParam(dn), body.values(), false);
  }

  @DeleteMapping("/directories/{id}/entry")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @RequestParam(required = false) String dn) {
    entries.remove(user, entries.definition(id), dnParam(dn));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/directories/{id}/options/{fieldId}")
  public List<OptionItem> options(@AuthenticationPrincipal SessionUser user, @PathVariable String id, @PathVariable String fieldId) {
    return entries.options(user, entries.definition(id), fieldId);
  }

  @GetMapping("/me/entries")
  public List<SelfEntryResult> me(@AuthenticationPrincipal SessionUser user) {
    return entries.selfOverview(user);
  }

  @PutMapping("/me/{id}/entry")
  public DirectoryEntry updateSelf(
      @AuthenticationPrincipal SessionUser user,
      @PathVariable String id,
      @RequestParam(required = false) String dn,
      @Valid @RequestBody EntryRequests.Update body) {
    return entries.update(user, entries.definition(id), dnParam(dn), body.values(), true);
  }
}
