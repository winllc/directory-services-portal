package com.winllc.dsp.service;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import com.winllc.dsp.config.DirectoryProperties;
import com.winllc.dsp.ldap.Directories;
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

  /** Up to {@code limit} people matching {@code q} (all when blank), as "Name (login)" with their DN. */
  public static List<SubjectSearchResult> search(Directories.Directory dir, String q, int limit) {
    DirectoryProperties c = dir.config();
    String query = q == null ? "" : q.trim();
    if (query.length() > 100) query = query.substring(0, 100);
    String t = Filter.encodeValue(query);
    String ua = c.getUsernameAttribute();
    Filter filter = query.isEmpty()
        ? userFilter(c, "*")
        : Filter.createORFilter(
            userFilter(c, "*" + t + "*"),
            Filter.createANDFilter(userFilter(c, "*"), Filter.createORFilter(
                Filter.createSubstringFilter("cn", null, new String[] {query}, null),
                Filter.createSubstringFilter("mail", null, new String[] {query}, null))));
    List<SubjectSearchResult> out = new ArrayList<>();
    for (DirectoryEntry e : dir.ldap().search(c.effectiveUserSearchBase(), SearchScope.SUB, filter, limit, List.of(ua, "cn", "displayName")).entries()) {
      String id = e.first(ua);
      if (id == null) continue;
      String name = e.first("displayName") != null ? e.first("displayName") : e.first("cn") != null ? e.first("cn") : id;
      out.add(new SubjectSearchResult("user", id, name + " (" + id + ")", e.dn()));
    }
    return out;
  }
}
