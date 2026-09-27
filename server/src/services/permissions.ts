import type { AccessLevel, DirectoryDefinition, PermissionGrant, SessionUser } from '@dsp/shared';
import { dnEquals } from '@dsp/shared';

const RANK: Record<AccessLevel, number> = { none: 0, read: 1, write: 2 };

export function maxAccess(a: AccessLevel, b: AccessLevel): AccessLevel {
  return RANK[a] >= RANK[b] ? a : b;
}

export function hasAccess(actual: AccessLevel, required: AccessLevel): boolean {
  return RANK[actual] >= RANK[required];
}

export function grantMatchesUser(grant: PermissionGrant, user: SessionUser): boolean {
  if (grant.subjectType === 'user') {
    return grant.subject.toLowerCase() === user.username.toLowerCase() || dnEquals(grant.subject, user.dn);
  }
  return user.groups.some((g) => dnEquals(g, grant.subject));
}

/**
 * Effective access of a user to a definition:
 *  - administrators get full access;
 *  - otherwise the highest matching user/group grant, plus read when everyoneCanRead;
 *  - read-only (white pages) definitions are always capped at read.
 */
export function effectiveAccess(
  user: SessionUser,
  definition: DirectoryDefinition,
  grants: readonly PermissionGrant[],
): AccessLevel {
  let access: AccessLevel = 'none';
  if (user.isAdmin) access = 'write';
  else {
    if (definition.everyoneCanRead) access = 'read';
    for (const g of grants) {
      if (g.definitionId === definition.id && grantMatchesUser(g, user)) access = maxAccess(access, g.access);
    }
  }
  if (definition.mode === 'readonly' && access === 'write') access = 'read';
  return access;
}
