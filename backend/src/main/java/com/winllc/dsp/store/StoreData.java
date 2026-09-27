package com.winllc.dsp.store;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.winllc.dsp.model.CustomSchema;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.model.PermissionGrant;
import com.winllc.dsp.model.SchemaSnapshot;
import java.util.ArrayList;
import java.util.List;

/** Everything the portal persists: custom schema, cached server schema, forms, definitions, grants. */
public class StoreData {
  public int version = 1;
  public CustomSchema customSchema = CustomSchema.empty();
  /** Last schema pulled from the directory. */
  public SchemaSnapshot schemaCache;
  public List<FormDefinition> forms = new ArrayList<>();
  public List<DirectoryDefinition> definitions = new ArrayList<>();
  public List<PermissionGrant> grants = new ArrayList<>();

  @JsonIgnore
  public boolean isEmpty() {
    return forms.isEmpty() && definitions.isEmpty();
  }
}
