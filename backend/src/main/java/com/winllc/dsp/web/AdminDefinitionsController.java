package com.winllc.dsp.web;

import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.service.EntryService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.DefinitionInput;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/definitions")
public class AdminDefinitionsController {
  private final ConfigStore store;

  public AdminDefinitionsController(ConfigStore store) {
    this.store = store;
  }

  private void check(DefinitionInput in, String id) {
    if (store.snapshot().forms.stream().noneMatch(f -> f.id().equals(in.formId()))) {
      throw ApiException.badRequest("Unknown form", Map.of("formId", "Unknown form"));
    }
    if (store.snapshot().definitions.stream().anyMatch(d -> d.slug().equals(in.slug()) && !d.id().equals(id))) {
      throw ApiException.badRequest("Another directory already uses that slug", Map.of("slug", "Already in use"));
    }
    EntryService.assertDefinitionShape(in.baseDn(), in.scope(), in.createContainers());
  }

  private static DirectoryDefinition build(DefinitionInput in, String id, String createdAt, String updatedAt) {
    return new DirectoryDefinition(id, in.name().trim(), in.slug(), blank(in.description()), in.formId(), in.baseDn().trim(), in.scope(),
        blank(in.filter()), in.mode(), in.everyoneCanRead(), in.listAttributes(), in.searchAttributes(), in.titleAttribute(),
        in.createContainers(), in.selfMatch(), blank(in.icon()), createdAt, updatedAt);
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  @GetMapping
  public List<DirectoryDefinition> list() {
    return store.snapshot().definitions;
  }

  @PostMapping
  public ResponseEntity<DirectoryDefinition> create(@Valid @RequestBody DefinitionInput in) {
    check(in, null);
    String now = Instant.now().toString();
    DirectoryDefinition def = build(in, "def-" + UUID.randomUUID(), now, now);
    store.update(d -> d.definitions.add(def));
    return ResponseEntity.status(HttpStatus.CREATED).body(def);
  }

  @PutMapping("/{id}")
  public DirectoryDefinition update(@PathVariable String id, @Valid @RequestBody DefinitionInput in) {
    check(in, id);
    return store.update(d -> {
      for (int i = 0; i < d.definitions.size(); i++) {
        if (d.definitions.get(i).id().equals(id)) {
          DirectoryDefinition next = build(in, id, d.definitions.get(i).createdAt(), Instant.now().toString());
          d.definitions.set(i, next);
          return next;
        }
      }
      throw ApiException.notFound("Definition not found");
    });
  }

  /** Deleting a definition also removes its permission grants. */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    store.update(d -> {
      if (!d.definitions.removeIf(x -> x.id().equals(id))) throw ApiException.notFound("Definition not found");
      d.grants.removeIf(g -> g.definitionId().equals(id));
      return null;
    });
    return ResponseEntity.noContent().build();
  }
}
