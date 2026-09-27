package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A directory exposed in the portal: a form scoped to a namespace (base DN, scope, filter),
 * either read/write or read-only ("white pages").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryDefinition(
    String id,
    String name,
    String slug,
    String description,
    String formId,
    String baseDn,
    String scope,
    String filter,
    String mode,
    boolean everyoneCanRead,
    List<String> listAttributes,
    List<String> searchAttributes,
    String titleAttribute,
    List<String> createContainers,
    SelfMatch selfMatch,
    String icon,
    String createdAt,
    String updatedAt) {

  public static final String READ_ONLY = "readonly";

  public boolean readOnly() {
    return READ_ONLY.equals(mode);
  }
}
