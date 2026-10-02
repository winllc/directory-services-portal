package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.model.AttributeTypeDef;
import com.winllc.dsp.model.ObjectClassDef;
import com.winllc.dsp.model.SchemaSnapshot;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.SchemaRequests;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final AuditLog audit;

  public AdminSchemaController(SchemaService schema, ConfigStore store, AuditLog audit) {
    this.schema = schema;
    this.store = store;
    this.audit = audit;
  }

  private static String label(String oid, List<String> names) {
    return names == null || names.isEmpty() ? oid : names.get(0);
  }

  private static <T> T find(List<T> items, java.util.function.Function<T, String> oid, String wanted) {
    return wanted == null ? null : items.stream().filter(x -> oid.apply(x).equals(wanted)).findFirst().orElse(null);
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
    SchemaService.ImportResult result = schema.importText(body.text());
    audit.event("schema.import").target("schema", "custom", "Custom schema")
        .detail("attributeTypes", result.attributeTypes())
        .detail("objectClasses", result.objectClasses())
        .detail("errors", result.errors().size())
        .success();
    return result;
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
    AttributeTypeDef incoming = body.definition().toDef();
    AttributeTypeDef before = find(schema.custom().attributeTypes(), AttributeTypeDef::oid,
        body.originalOid() != null ? body.originalOid() : incoming.oid());
    AttributeTypeDef saved = schema.upsertAttributeType(incoming, body.originalOid());
    audit.event("schema.attribute_type.save").target("schema", saved.oid(), label(saved.oid(), saved.names()))
        .changes(audit.diff(before, saved, Set.of())).success();
    return saved;
  }

  @DeleteMapping("/attribute-types/{oid}")
  public ResponseEntity<Void> deleteAttribute(@PathVariable String oid) {
    AttributeTypeDef before = find(schema.custom().attributeTypes(), AttributeTypeDef::oid, oid);
    schema.deleteAttributeType(oid);
    audit.event("schema.attribute_type.delete").target("schema", oid, before == null ? oid : label(oid, before.names()))
        .changes(audit.diff(before, null, Set.of())).success();
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/object-classes")
  public ObjectClassDef saveClass(@Valid @RequestBody SchemaRequests.SaveObjectClass body) {
    ObjectClassDef incoming = body.definition().toDef();
    ObjectClassDef before = find(schema.custom().objectClasses(), ObjectClassDef::oid,
        body.originalOid() != null ? body.originalOid() : incoming.oid());
    ObjectClassDef saved = schema.upsertObjectClass(incoming, body.originalOid());
    audit.event("schema.object_class.save").target("schema", saved.oid(), label(saved.oid(), saved.names()))
        .changes(audit.diff(before, saved, Set.of())).success();
    return saved;
  }

  @DeleteMapping("/object-classes/{oid}")
  public ResponseEntity<Void> deleteClass(@PathVariable String oid) {
    ObjectClassDef before = find(schema.custom().objectClasses(), ObjectClassDef::oid, oid);
    schema.deleteObjectClass(oid);
    audit.event("schema.object_class.delete").target("schema", oid, before == null ? oid : label(oid, before.names()))
        .changes(audit.diff(before, null, Set.of())).success();
    return ResponseEntity.noContent().build();
  }
}
