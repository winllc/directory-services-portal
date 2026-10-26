package com.winllc.dsp.service;

import com.winllc.dsp.audit.AuditEvent;
import com.winllc.dsp.model.SessionUser;
import java.util.List;

/**
 * Published (as a Spring application event) after the portal changed an entry: created, updated
 * (renamed too) or deleted, by a person, an API token or an import. {@code changes} are as audited
 * (secrets redacted). Webhooks are sent from these. It is published with the change's audit event
 * as that is stored: with the shared database inside the event's transaction (listeners that write
 * to the database keep their writes in a savepoint), else just after the event is written.
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
    Actor actor) {

  public static final String CREATE = "create";
  public static final String UPDATE = "update";
  public static final String DELETE = "delete";

  /**
   * Who made the change: the signed-in user (or API token user) and how they signed in, and the
   * API client when the change came through the integration API. Without a user (an import run
   * by the system) username and auth method are null.
   */
  public record Actor(String username, String authMethod, String apiClientId) {

    /** The actor for a change made by {@code user} (may be null), through {@code apiClientId} (may be null). */
    public static Actor of(SessionUser user, String apiClientId) {
      return user == null ? new Actor(null, null, apiClientId) : from(user, apiClientId);
    }

    private static Actor from(SessionUser user, String apiClientId) {
      return new Actor(user.username(), user.authMethod(), apiClientId);
    }
  }
}
