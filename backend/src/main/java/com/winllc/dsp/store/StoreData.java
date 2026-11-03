package com.winllc.dsp.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.winllc.dsp.analysis.AnalysisDefinition;
import com.winllc.dsp.metrics.Dashboard;
import com.winllc.dsp.model.CustomSchema;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.PermissionGrant;
import com.winllc.dsp.model.SchemaSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything the portal persists: custom schema, cached server schema, forms, definitions, grants,
 * metric dashboards and saved analyses.
 */
public class StoreData {
  /** 2: definitions and grants name their LDAP directory (see ConfigStore#migrateDirectories). */
  public static final int CURRENT_VERSION = 2;

  public int version = CURRENT_VERSION;
  public CustomSchema customSchema = CustomSchema.empty();
  /** Last schema pulled from the primary directory. */
  public SchemaSnapshot schemaCache;
  /** Last schema pulled from each other directory, by directory id. */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public java.util.Map<String, SchemaSnapshot> schemaCaches = new java.util.LinkedHashMap<>();
  public List<FormDefinition> forms = new ArrayList<>();
  public List<DirectoryDefinition> definitions = new ArrayList<>();
  public List<PermissionGrant> grants = new ArrayList<>();
  public List<Dashboard> dashboards = new ArrayList<>();
  public List<AnalysisDefinition> analyses = new ArrayList<>();

  @JsonIgnore
  public boolean isEmpty() {
    return forms.isEmpty() && definitions.isEmpty();
  }
}
