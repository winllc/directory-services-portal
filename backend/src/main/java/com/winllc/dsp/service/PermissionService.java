package com.winllc.dsp.service;

import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.PermissionGrant;
import com.winllc.dsp.model.SessionUser;
import java.util.List;

/** Computes a user's effective access to a directory definition. */
public final class PermissionService {
  private PermissionService() {}

  public static boolean matches(PermissionGrant grant, SessionUser user) {
    if ("user".equals(grant.subjectType())) {
      return grant.subject().equalsIgnoreCase(user.username()) || Dns.equal(grant.subject(), user.dn());
    }
    return user.groups().stream().anyMatch(g -> Dns.equal(g, grant.subject()));
  }

  /**
   * Administrators get full access; otherwise the highest matching user/group grant, plus read
   * when everyone may read. Read-only (white pages) definitions are always capped at read.
   */
  public static AccessLevel effectiveAccess(SessionUser user, DirectoryDefinition def, List<PermissionGrant> grants) {
    AccessLevel access = AccessLevel.NONE;
    if (user.isAdmin()) {
      access = AccessLevel.WRITE;
    } else {
      if (def.everyoneCanRead()) access = AccessLevel.READ;
      for (PermissionGrant g : grants) {
        if (g.definitionId().equals(def.id()) && matches(g, user)) access = AccessLevel.max(access, AccessLevel.parse(g.access()));
      }
    }
    if (def.readOnly() && access == AccessLevel.WRITE) access = AccessLevel.READ;
    return access;
  }
}
