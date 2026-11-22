package com.winllc.dsp.web;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.RequiresFeature;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.schema.SchemaIndex;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.web.dto.SchemaRequests;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin helpers: preview which entries a namespace selects, and browse the tree for DN pickers. */
@RestController
@RequiresFeature(LicensedFeature.FORM_BUILDER)
@RequestMapping("/api/admin")
public class AdminDirectoryController {
  private final Directories directories;
  private final SchemaService schema;

  public AdminDirectoryController(Directories directories, SchemaService schema) {
    this.directories = directories;
    this.schema = schema;
  }

  @PostMapping("/preview")
  public LdapDirectory.Result preview(@Valid @RequestBody SchemaRequests.Preview body) {
    Filter filter;
    try {
      filter = body.filter() == null || body.filter().isBlank() ? Filter.createPresenceFilter("objectClass") : Filter.create(body.filter());
    } catch (LDAPException e) {
      throw ApiException.badRequest("Invalid LDAP filter");
    }
    SearchScope scope = switch (body.scope()) {
      case "base" -> SearchScope.BASE;
      case "one" -> SearchScope.ONE;
      default -> SearchScope.SUB;
    };
    Directories.Directory dir = directories.require(body.directory());
    // Password attributes are never returned or searched on, here either (by name, alias or OID).
    SchemaIndex index = schema.mergedIndex(dir.id());
    if (filterAttributes(filter).stream().anyMatch(a -> a == null || EntryService.isHidden(a, index))) {
      throw ApiException.badRequest("The filter uses an attribute that can't be searched on", Map.of("filter", "Password attributes can't be used here"));
    }
    List<String> wanted = body.attributes() == null ? List.of() : body.attributes().stream().filter(a -> !EntryService.isHidden(a, index)).toList();
    // Every requested attribute hidden: ask for none ("1.1"), not for all of them.
    List<String> requested = wanted.isEmpty() && body.attributes() != null && !body.attributes().isEmpty() ? List.of("1.1") : wanted;
    LdapDirectory.Result r = dir.ldap().search(body.baseDn(), scope, filter, 25, requested);
    List<DirectoryEntry> entries = r.entries().stream().map(e -> EntryService.withoutHidden(e, index)).toList();
    return new LdapDirectory.Result(entries, r.truncated());
  }

  /** Attribute names a filter tests; null for an extensible match without one (it tests every attribute). */
  private static List<String> filterAttributes(Filter f) {
    List<String> out = new java.util.ArrayList<>();
    switch (f.getFilterType()) {
      case Filter.FILTER_TYPE_AND, Filter.FILTER_TYPE_OR -> {
        for (Filter c : f.getComponents()) out.addAll(filterAttributes(c));
      }
      case Filter.FILTER_TYPE_NOT -> out.addAll(filterAttributes(f.getNOTComponent()));
      default -> out.add(f.getAttributeName());
    }
    return out;
  }

  public record Child(String dn, String name, List<String> objectClasses) {}

  public record BrowseResult(String base, List<Child> children, boolean truncated) {}

  /** The children of {@code base} (none: the directory's base DN) in {@code directory} (none: the primary one). */
  @GetMapping("/browse")
  public BrowseResult browse(@RequestParam(required = false) String base, @RequestParam(required = false) String directory) {
    Directories.Directory dir = directories.require(directory);
    String b = base == null || base.isBlank() ? dir.config().getBaseDn() : base.trim();
    if (!Dns.isValid(b)) throw ApiException.badRequest("Invalid DN", Map.of("base", "Invalid DN"));
    LdapDirectory.Result result = dir.ldap().search(b, SearchScope.ONE, Filter.createPresenceFilter("objectClass"), 500, List.of("objectClass"));
    List<Child> children = result.entries().stream()
        .map(e -> new Child(e.dn(), Dns.rdnValue(e.dn()), e.values("objectClass")))
        .sorted(Comparator.comparing(Child::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
    return new BrowseResult(b, children, result.truncated());
  }
}
