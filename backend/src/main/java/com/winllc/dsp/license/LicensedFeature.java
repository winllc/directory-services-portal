package com.winllc.dsp.license;

/**
 * The Directory Services Portal's features in the ENS license catalog (products-page
 * src/data/licensing.ts, product {@code directory-portal}). A license lists the ones it unlocks as
 * {@code directory-portal.<id>}. The web client mirrors the ids (shared/src/types.ts). Never rename
 * an id: licenses carrying it have been issued.
 */
public enum LicensedFeature {
  /** Browse, search and edit directories through definitions: /api/definitions, /api/directories. */
  DIRECTORY_EDITOR("directory-editor", "Directory editor",
      "Browse, search and edit any LDAP directory through admin-defined forms."),
  /** Forms and directory definitions (and their DN browser and search preview). */
  FORM_BUILDER("form-builder", "Form builder", "Definitions and forms for custom schemas, without code."),
  /** My profile: /api/me. */
  SELF_SERVICE_PROFILE("self-service-profile", "Self-service profiles", "People keep their own directory entries up to date."),
  /**
   * Read access to directories for users who are not administrators: "everyone can read"
   * definitions, read grants and read-only (white pages) definitions. Without it a non-admin
   * only reaches directories they hold a write grant on.
   */
  WHITE_PAGES("white-pages", "White pages", "Directory search for everyone, with per-user and per-group permissions."),
  /** X.509 client-certificate sign-in (/api/auth/x509 and certificate auto-login). */
  SMART_CARD_SIGN_IN("smart-card-sign-in", "Smart-card sign-in", "X.509 client-certificate sign-in, CAC and PIV included."),
  /** Metrics, explorer and dashboards: /api/metrics, /api/admin/dashboards. */
  METRICS("metrics", "Metrics and dashboards", "Counts and trends across the directory, on dashboards you build."),
  /** Data analysis and its findings (CSV included): /api/admin/analysis. */
  DATA_ANALYSIS("data-analysis", "Data-quality analysis",
      "Finds missing, malformed and duplicate data, with CSV export of the findings."),
  /** Schema import and export: /api/admin/schema/import, /api/admin/schema/export. */
  SCHEMA_TRANSFER("schema-transfer", "Schema import and export", "Moves schema between directories.");

  private final String id;
  private final String label;
  private final String description;

  LicensedFeature(String id, String label, String description) {
    this.id = id;
    this.label = label;
    this.description = description;
  }

  /** The id after "directory-portal." in a license file. */
  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }
}
