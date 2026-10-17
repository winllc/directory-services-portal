package com.winllc.dsp.audit;

import java.util.List;
import java.util.Map;

/**
 * One audit event in the shared ENS format {@code ens-audit/1} (see README, Audit logging).
 *
 * <p>Events are serialized by {@link AuditJson} in a fixed field order, chained by
 * {@code prevHash}/{@code hash}, and stored as one line each by {@link AuditStore}. The stored line
 * is the source of truth: the admin API returns it parsed, never re-serialized from this record.
 */
public record AuditEvent(
    String id,
    long seq,
    String time,
    String product,
    String version,
    String host,
    String installationId,
    String category,
    String action,
    String outcome,
    String severity,
    Actor actor,
    Source source,
    Target target,
    String reason,
    Map<String, Object> details,
    String requestId,
    String session,
    String prevHash,
    String hash) {

  public static final String SCHEMA = "ens-audit/1";

  public static final String SUCCESS = "success";
  public static final String FAILURE = "failure";
  public static final String DENIED = "denied";

  public static final String INFO = "info";
  public static final String NOTICE = "notice";
  public static final String WARNING = "warning";
  public static final String ALERT = "alert";

  /** {@code type} is user, service, system or anonymous. */
  public record Actor(String type, String id, String name, List<String> roles) {
    public static final Actor SYSTEM = new Actor("system", null, null, List.of());
  }

  public record Source(String ip, String userAgent) {}

  /** What was acted on: e.g. entry, user, form, definition, grant, schema, license, audit-token. */
  public record Target(String type, String id, String name) {}

  /**
   * An attribute or setting before and after (recorded as {@code details["<attribute>.old"]} and
   * {@code details["<attribute>.new"]}). Secret values are recorded as {@code [redacted]}.
   */
  public record Change(String attribute, List<String> before, List<String> after) {}

  /** The same event with its chain position; {@code hash} is filled in by the store. */
  AuditEvent chained(long seq, String prevHash) {
    return new AuditEvent(id, seq, time, product, version, host, installationId, category, action, outcome, severity,
        actor, source, target, reason, details, requestId, session, prevHash, null);
  }
}
