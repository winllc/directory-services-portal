package com.winllc.dsp.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * One audited action: who did what to which target, from where, and whether it succeeded.
 *
 * <p>{@code action} is a dotted name such as {@code entry.update} or {@code auth.login};
 * {@code outcome} is {@code success}, {@code denied} (refused by the portal's own checks) or
 * {@code failed} (the directory or the credentials said no).
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AuditEvent(
    String id,
    String at,
    String action,
    String outcome,
    Actor actor,
    String sourceAddress,
    Target target,
    String definitionId,
    String message,
    List<Change> changes,
    Map<String, String> details) {

  public static final String SUCCESS = "success";
  public static final String DENIED = "denied";
  public static final String FAILED = "failed";

  /** The signed-in user, or null for an anonymous request such as a failed sign-in. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Actor(String username, String dn, String authMethod) {}

  /** What was acted on: {@code type} is entry, user, form, definition, grant or schema. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Target(String type, String id, String name) {}

  /** An attribute or setting before and after. Secret values are recorded as redacted. */
  public record Change(String attribute, List<String> before, List<String> after) {}
}
