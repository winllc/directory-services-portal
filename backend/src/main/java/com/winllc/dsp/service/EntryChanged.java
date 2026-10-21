package com.winllc.dsp.service;

import com.winllc.dsp.audit.AuditEvent;
import java.util.List;

/**
 * Published (as a Spring application event) after the portal changed an entry: created, updated
 * (renamed too) or deleted, by a person, an API token or an import. {@code changes} are as audited
 * (secrets redacted). Webhooks are sent from these.
 */
public record EntryChanged(
    String operation,
    String definitionId,
    String formId,
    String dn,
    String previousDn,
    String name,
    List<AuditEvent.Change> changes,
    boolean self,
    String actor,
    String authMethod,
    String apiClientId) {

  public static final String CREATE = "create";
  public static final String UPDATE = "update";
  public static final String DELETE = "delete";
}
