package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A directory exposed in the portal: a form scoped to a namespace (base DN, scope, filter),
 * either read/write or read-only ("white pages"), in one of the portal's LDAP directories
 * ({@code directory}, its id; everything done through the definition happens there).
 * {@code groups}: self-service group access, for definitions of groups (see {@link GroupAccess}).
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
    String updatedAt,
    String directory,
    GroupAccess groups) {

  public static final String READ_ONLY = "readonly";

  /** Without a directory: the primary one (as definitions stored before there were several). */
  public DirectoryDefinition(String id, String name, String slug, String description, String formId, String baseDn, String scope,
      String filter, String mode, boolean everyoneCanRead, List<String> listAttributes, List<String> searchAttributes,
      String titleAttribute, List<String> createContainers, SelfMatch selfMatch, String icon, String createdAt, String updatedAt) {
    this(id, name, slug, description, formId, baseDn, scope, filter, mode, everyoneCanRead, listAttributes, searchAttributes, titleAttribute,
        createContainers, selfMatch, icon, createdAt, updatedAt, null, null);
  }

  /** Without group access settings (as definitions stored before there was group access). */
  public DirectoryDefinition(String id, String name, String slug, String description, String formId, String baseDn, String scope,
      String filter, String mode, boolean everyoneCanRead, List<String> listAttributes, List<String> searchAttributes,
      String titleAttribute, List<String> createContainers, SelfMatch selfMatch, String icon, String createdAt, String updatedAt,
      String directory) {
    this(id, name, slug, description, formId, baseDn, scope, filter, mode, everyoneCanRead, listAttributes, searchAttributes, titleAttribute,
        createContainers, selfMatch, icon, createdAt, updatedAt, directory, null);
  }

  /** The same definition in another directory. */
  public DirectoryDefinition withDirectory(String directoryId) {
    return new DirectoryDefinition(id, name, slug, description, formId, baseDn, scope, filter, mode, everyoneCanRead, listAttributes,
        searchAttributes, titleAttribute, createContainers, selfMatch, icon, createdAt, updatedAt, directoryId, groups);
  }

  /** Whether users can look up this definition's groups and ask to join them. */
  public boolean groupAccess() {
    return groups != null && groups.enabled();
  }

  public boolean readOnly() {
    return READ_ONLY.equals(mode);
  }
}
