package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.ldap.Directories;
import com.winllc.dsp.ldap.DirectoryException;
import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.DirectoryEntry;
import com.winllc.dsp.model.SubjectSearchResult;
import java.util.ArrayList;
import java.util.List;

/** Finds people (sign-in accounts) of one LDAP directory by login name, name or e-mail. */
public final class People {
  private People() {}

  /** The directory's user filter with {@code {{username}}} replaced by an already escaped value. */
  public static Filter userFilter(DirectoryProperties c, String usernameValue) {
    try {
      return Filter.create(c.getUserFilter().replace("{{username}}", usernameValue));
    } catch (LDAPException e) {
      throw new IllegalStateException("Invalid user filter template", e);
    }
  }

  /** The person (a sign-in account under the user search base) with this DN; null when there is none. */
  public static SubjectSearchResult byDn(Directories.Directory dir, String dn) {
    DirectoryProperties c = dir.config();
    if (!Dns.isValid(dn) || !Dns.isUnder(dn, c.effectiveUserSearchBase(), false)) return null;
    try {
      List<SubjectSearchResult> found = results(c, dir.ldap().search(dn, SearchScope.BASE, userFilter(c, "*"), 1, attributes(c)).entries());
      return found.isEmpty() ? null : found.get(0);
    } catch (DirectoryException e) {
      if (e.code() == DirectoryException.Code.NO_SUCH_OBJECT) return null;
      throw e;
    }
  }

  private static List<String> attributes(DirectoryProperties c) {
    return List.of(c.getUsernameAttribute(), "cn", "displayName");
  }

  private static List<SubjectSearchResult> results(DirectoryProperties c, List<DirectoryEntry> entries) {
    List<SubjectSearchResult> out = new ArrayList<>();
    for (DirectoryEntry e : entries) {
      String id = e.first(c.getUsernameAttribute());
      if (id == null) continue;
      String name = e.first("displayName") != null ? e.first("displayName") : e.first("cn") != null ? e.first("cn") : id;
      out.add(new SubjectSearchResult("user", id, name + " (" + id + ")", e.dn()));
    }
    return out;
  }

  /** Up to {@code limit} people whose login name or e-mail address is exactly {@code value}. */
  public static List<SubjectSearchResult> exact(Directories.Directory dir, String value, int limit) {
    DirectoryProperties c = dir.config();
    Filter filter = Filter.createORFilter(userFilter(c, Filter.encodeValue(value)),
        Filter.createANDFilter(userFilter(c, "*"), Filter.createEqualityFilter("mail", value)));
    return results(c, dir.ldap().search(c.effectiveUserSearchBase(), SearchScope.SUB, filter, limit, attributes(c)).entries());
  }

  /** Up to {@code limit} people matching {@code q} (all when blank), as "Name (login)" with their DN. */
  public static List<SubjectSearchResult> search(Directories.Directory dir, String q, int limit) {
    DirectoryProperties c = dir.config();
    String query = q == null ? "" : q.trim();
    if (query.length() > 100) query = query.substring(0, 100);
    String t = Filter.encodeValue(query);
    Filter filter = query.isEmpty()
        ? userFilter(c, "*")
        : Filter.createORFilter(
            userFilter(c, "*" + t + "*"),
            Filter.createANDFilter(userFilter(c, "*"), Filter.createORFilter(
                Filter.createSubstringFilter("cn", null, new String[] {query}, null),
                Filter.createSubstringFilter("mail", null, new String[] {query}, null))));
    return results(c, dir.ldap().search(c.effectiveUserSearchBase(), SearchScope.SUB, filter, limit, attributes(c)).entries());
  }
}
