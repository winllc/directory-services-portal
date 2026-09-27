package com.winllc.dsp.web;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.config.PortalProperties;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.ldap.LdapDirectory;
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
@RequestMapping("/api/admin")
public class AdminDirectoryController {
  private final LdapDirectory directory;
  private final PortalProperties props;

  public AdminDirectoryController(LdapDirectory directory, PortalProperties props) {
    this.directory = directory;
    this.props = props;
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
    return directory.search(body.baseDn(), scope, filter, 25, body.attributes());
  }

  public record Child(String dn, String name, List<String> objectClasses) {}

  public record BrowseResult(String base, List<Child> children, boolean truncated) {}

  @GetMapping("/browse")
  public BrowseResult browse(@RequestParam(required = false) String base) {
    String b = base == null || base.isBlank() ? props.getBaseDn() : base.trim();
    if (!Dns.isValid(b)) throw ApiException.badRequest("Invalid DN", Map.of("base", "Invalid DN"));
    LdapDirectory.Result result = directory.search(b, SearchScope.ONE, Filter.createPresenceFilter("objectClass"), 500, List.of("objectClass"));
    List<Child> children = result.entries().stream()
        .map(e -> new Child(e.dn(), Dns.rdnValue(e.dn()), e.values("objectClass")))
        .sorted(Comparator.comparing(Child::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
    return new BrowseResult(b, children, result.truncated());
  }
}
