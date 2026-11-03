package com.winllc.dsp.service;

import com.winllc.dsp.ldap.Dns;
import com.winllc.dsp.model.AccessLevel;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.PermissionGrant;
import com.winllc.dsp.model.SessionUser;
import java.util.List;
import java.util.function.BiPredicate;

/** Computes a user's effective access to a directory definition. */
public final class PermissionService {
  private PermissionService() {}

  /**
   * Whether a grant applies to the user. The grant's subject lives in the grant's directory, or the
   * definition's ({@code definitionDirectory}) when the grant names none; {@code sameDirectory}
   * compares directory references (aliases such as "default" resolved). A user of another directory
   * never matches, whatever their name or DN.
   */
  public static boolean matches(PermissionGrant grant, SessionUser user, String definitionDirectory, BiPredicate<String, String> sameDirectory) {
    String subjectDirectory = grant.directory() != null ? grant.directory() : definitionDirectory;
    if (!sameDirectory.test(subjectDirectory, user.directory())) return false;
    return matches(grant, user);
  }

  /** Whether the grant's subject is the user or one of their groups, by name and DN alone. */
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
    return effectiveAccess(user, def, grants, java.util.Objects::equals);
  }

  /** As above; {@code sameDirectory} compares directory references (see {@link #matches(PermissionGrant, SessionUser, String, BiPredicate)}). */
  public static AccessLevel effectiveAccess(SessionUser user, DirectoryDefinition def, List<PermissionGrant> grants,
      BiPredicate<String, String> sameDirectory) {
    AccessLevel access = AccessLevel.NONE;
    if (user.isAdmin()) {
      access = AccessLevel.WRITE;
    } else {
      if (def.everyoneCanRead()) access = AccessLevel.READ;
      for (PermissionGrant g : grants) {
        if (g.definitionId().equals(def.id()) && matches(g, user, def.directory(), sameDirectory)) access = AccessLevel.max(access, AccessLevel.parse(g.access()));
      }
    }
    if (def.readOnly() && access == AccessLevel.WRITE) access = AccessLevel.READ;
    return access;
  }
}
