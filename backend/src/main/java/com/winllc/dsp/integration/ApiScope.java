package com.winllc.dsp.integration;

import java.util.Arrays;
import java.util.List;

/**
 * What an API token may do. A scope only ever narrows: every request is also checked against the
 * rights its creator has in the directory at that moment, exactly as in the portal.
 */
public enum ApiScope {
  ENTRIES_READ("entries:read", "List directories, search and read entries"),
  ENTRIES_WRITE("entries:write", "Create and change entries"),
  ENTRIES_DELETE("entries:delete", "Delete entries"),
  GROUPS_READ("groups:read", "Read group members"),
  GROUPS_WRITE("groups:write", "Add and remove group members");

  private final String id;
  private final String description;

  ApiScope(String id, String description) {
    this.id = id;
    this.description = description;
  }

  public String id() {
    return id;
  }

  public String description() {
    return description;
  }

  public static ApiScope parse(String id) {
    return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst().orElse(null);
  }

  public static List<String> ids() {
    return Arrays.stream(values()).map(ApiScope::id).toList();
  }
}
