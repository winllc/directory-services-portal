package com.winllc.dsp.license;

import java.util.List;

/**
 * The features an ENS Solutions license unlocks. Everything not listed here is a core feature
 * that works without a license. The web client mirrors the ids (shared/src/types.ts).
 */
public enum LicensedFeature {
  ANALYSIS("analysis", "Data analysis", List.of("/api/admin/analysis", "/api/admin/analysis/**")),
  METRICS("metrics", "Metrics", List.of("/api/metrics", "/api/metrics/**", "/api/admin/dashboards", "/api/admin/dashboards/**")),
  SCHEMA_TRANSFER("schema-transfer", "Schema import and export", List.of("/api/admin/schema/import", "/api/admin/schema/export"));

  private final String id;
  private final String label;
  private final List<String> paths;

  LicensedFeature(String id, String label, List<String> paths) {
    this.id = id;
    this.label = label;
    this.paths = paths;
  }

  /** The id used in license files ({@code features=analysis,metrics} or {@code *}). */
  public String id() {
    return id;
  }

  public String label() {
    return label;
  }

  /** API paths (Spring path patterns) that belong to this feature. */
  public List<String> paths() {
    return paths;
  }
}
