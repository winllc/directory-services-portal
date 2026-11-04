package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Grants a user (by username) or group (by DN) read or write access to a definition. The subject
 * lives in {@code directory} (an LDAP directory id): null means the definition's own directory, so a
 * user or group of another directory matches only a grant that names theirs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionGrant(
    String id,
    String definitionId,
    String subjectType,
    String subject,
    String subjectLabel,
    String access,
    String createdAt,
    String directory) {

  public PermissionGrant(String id, String definitionId, String subjectType, String subject, String subjectLabel, String access,
      String createdAt) {
    this(id, definitionId, subjectType, subject, subjectLabel, access, createdAt, null);
  }

  public PermissionGrant withAccess(String newAccess, String newLabel) {
    return new PermissionGrant(id, definitionId, subjectType, subject, newLabel, newAccess, createdAt, directory);
  }

  public PermissionGrant withDirectory(String directoryId) {
    return new PermissionGrant(id, definitionId, subjectType, subject, subjectLabel, access, createdAt, directoryId);
  }
}
