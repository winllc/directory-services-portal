package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Grants a user (by username) or group (by DN) read or write access to a definition. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionGrant(
    String id,
    String definitionId,
    String subjectType,
    String subject,
    String subjectLabel,
    String access,
    String createdAt) {

  public PermissionGrant withAccess(String newAccess, String newLabel) {
    return new PermissionGrant(id, definitionId, subjectType, subject, newLabel, newAccess, createdAt);
  }
}
