package com.winllc.dsp.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The portal's audit action ids and their categories. Ids are stable: never rename one that has
 * shipped (the portal's original names, such as {@code entry.self_update}, are kept as they were).
 */
public final class AuditCatalog {
  public static final List<String> CATEGORIES =
      List.of("authentication", "authorization", "account", "configuration", "data", "license", "system", "audit");
  public static final List<String> OUTCOMES = List.of(AuditEvent.SUCCESS, AuditEvent.FAILURE, AuditEvent.DENIED);
  public static final List<String> SEVERITIES = List.of(AuditEvent.INFO, AuditEvent.NOTICE, AuditEvent.WARNING, AuditEvent.ALERT);

  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    for (String a : List.of("auth.login", "auth.logout", "auth.lockout", "auth.password-changed", "auth.password-reset",
        "auth.consent-acknowledged")) {
      ACTIONS.put(a, "authentication");
    }
    ACTIONS.put("access.denied", "authorization");
    // Permissions on directories (grants to users and groups).
    ACTIONS.put("grant.save", "account");
    ACTIONS.put("grant.revoke", "account");
    for (String a : List.of("entry.create", "entry.update", "entry.self_update", "entry.delete",
        "form.create", "form.update", "form.delete", "definition.create", "definition.update", "definition.delete",
        "schema.import", "schema.attribute_type.save", "schema.attribute_type.delete", "schema.object_class.save",
        "schema.object_class.delete", "analysis.export", "data.exported")) {
      ACTIONS.put(a, "data");
    }
    for (String a : List.of("dashboard.create", "dashboard.update", "dashboard.delete",
        "analysis.create", "analysis.update", "analysis.delete")) {
      ACTIONS.put(a, "configuration");
    }
    // license.install: the name used before ens-audit/1, kept on migrated history.
    for (String a : List.of("license.installed", "license.rejected", "license.mode-changed", "license.install")) {
      ACTIONS.put(a, "license");
    }
    for (String a : List.of("system.started", "system.stopped", "job.completed", "job.failed")) ACTIONS.put(a, "system");
    for (String a : List.of("audit.exported", "audit.verified", "audit.token-created", "audit.token-revoked", "audit.pruned",
        "audit.delivery-failed", "audit.delivery-recovered")) {
      ACTIONS.put(a, "audit");
    }
  }

  private AuditCatalog() {}

  /** Known actions, in display order, with their category. */
  public static Map<String, String> actions() {
    return ACTIONS;
  }

  public static String category(String action) {
    String c = ACTIONS.get(action);
    if (c != null) return c;
    String prefix = action.contains(".") ? action.substring(0, action.indexOf('.')) : action;
    return switch (prefix) {
      case "auth" -> "authentication";
      case "access" -> "authorization";
      case "grant", "user", "group", "role" -> "account";
      case "license" -> "license";
      case "system", "job" -> "system";
      case "audit" -> "audit";
      case "settings", "dashboard" -> "configuration";
      default -> "data";
    };
  }

  /**
   * The default severity: warning for failures and denials; notice for changes to configuration,
   * permissions, the license and the portal's forms, definitions and schema; info otherwise.
   */
  public static String severity(String category, String action, String outcome) {
    if (!AuditEvent.SUCCESS.equals(outcome)) return AuditEvent.WARNING;
    if (action.endsWith(".export") || action.endsWith(".exported") || action.equals("audit.verified")) return AuditEvent.INFO;
    return switch (category) {
      case "configuration", "account", "license" -> AuditEvent.NOTICE;
      case "audit" -> action.startsWith("audit.token") || action.equals("audit.pruned") ? AuditEvent.NOTICE : AuditEvent.INFO;
      case "authentication" -> action.startsWith("auth.password") ? AuditEvent.NOTICE : AuditEvent.INFO;
      case "data" -> action.startsWith("form.") || action.startsWith("definition.") || action.startsWith("schema.")
          ? AuditEvent.NOTICE : AuditEvent.INFO;
      default -> AuditEvent.INFO;
    };
  }

  /**
   * Security actions (sign-in, permission, configuration and license changes) are refused when
   * they cannot be audited; system and job events, and denials, are logged and carried on.
   */
  public static boolean mustBeRecorded(String category, String action) {
    return switch (category) {
      case "system", "authorization" -> false;
      case "audit" -> action.startsWith("audit.token");
      default -> true;
    };
  }
}
