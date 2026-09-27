package com.winllc.dsp.web;

import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.SchemaSnapshot;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.SchemaRequests;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Schema browsing, pulling from the directory, import/export and custom definitions. */
@RestController
@RequestMapping("/api/admin/schema")
public class AdminSchemaController {
  private final SchemaService schema;
  private final ConfigStore store;

  public AdminSchemaController(SchemaService schema, ConfigStore store) {
    this.schema = schema;
    this.store = store;
  }

  @GetMapping
  public Map<String, Object> get() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("merged", schema.mergedIndex().schema());
    body.put("custom", schema.custom());
    SchemaSnapshot server = store.snapshot().schemaCache;
    if (server == null) {
      body.put("server", null);
    } else {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("fetchedAt", server.fetchedAt());
      s.put("subschemaDn", server.subschemaDn());
      s.put("attributeTypes", server.attributeTypes().size());
      s.put("objectClasses", server.objectClasses().size());
      body.put("server", s);
    }
    return body;
  }

  @PostMapping("/refresh")
  public Map<String, Object> refresh() {
    SchemaSnapshot s = schema.refresh();
    return Map.of("fetchedAt", s.fetchedAt(), "attributeTypes", s.attributeTypes().size(), "objectClasses", s.objectClasses().size());
  }

  @PostMapping("/import")
  public SchemaService.ImportResult importText(@Valid @RequestBody SchemaRequests.Import body) {
    return schema.importText(body.text());
  }

  @GetMapping("/export")
  public ResponseEntity<String> export() {
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"custom.schema\"")
        .body(schema.exportCustom());
  }

  @PutMapping("/attribute-types")
  public AttributeTypeDef saveAttribute(@Valid @RequestBody SchemaRequests.SaveAttributeType body) {
    return schema.upsertAttributeType(body.definition().toDef(), body.originalOid());
  }

  @DeleteMapping("/attribute-types/{oid}")
  public ResponseEntity<Void> deleteAttribute(@PathVariable String oid) {
    schema.deleteAttributeType(oid);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/object-classes")
  public ObjectClassDef saveClass(@Valid @RequestBody SchemaRequests.SaveObjectClass body) {
    return schema.upsertObjectClass(body.definition().toDef(), body.originalOid());
  }

  @DeleteMapping("/object-classes/{oid}")
  public ResponseEntity<Void> deleteClass(@PathVariable String oid) {
    schema.deleteObjectClass(oid);
    return ResponseEntity.noContent().build();
  }
}
