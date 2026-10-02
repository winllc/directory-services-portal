package com.winllc.dsp.web;

import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.model.DirectoryDefinition;
import com.winllc.dsp.model.FormDefinition;
import com.winllc.dsp.schema.FormLinter;
import com.winllc.dsp.schema.SchemaService;
import com.winllc.dsp.store.ConfigStore;
import com.winllc.dsp.web.dto.FormInput;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
@RequestMapping("/api/admin/forms")
public class AdminFormsController {
  private final ConfigStore store;
  private final SchemaService schema;
  private final AuditLog audit;

  public AdminFormsController(ConfigStore store, SchemaService schema, AuditLog audit) {
    this.store = store;
    this.schema = schema;
    this.audit = audit;
  }

  private void record(String action, FormDefinition before, FormDefinition after) {
    FormDefinition f = after != null ? after : before;
    audit.event(action).target("form", f.id(), f.name()).changes(audit.diff(before, after, Set.of("id", "createdAt", "updatedAt"))).success();
  }

  public record SaveResult(FormDefinition form, List<String> warnings) {}

  private FormLinter.Lint lint(FormInput in) {
    return FormLinter.lint(in.name(), in.objectClasses(), in.rdnAttribute(), in.fields(), schema.mergedIndex());
  }

  private FormLinter.Lint check(FormInput in) {
    FormLinter.Lint lint = lint(in);
    if (!lint.errors().isEmpty()) {
      Map<String, String> details = new LinkedHashMap<>();
      for (int i = 0; i < lint.errors().size(); i++) details.put("form." + i, lint.errors().get(i));
      throw ApiException.badRequest(lint.errors().get(0), details);
    }
    return lint;
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  @GetMapping
  public List<FormDefinition> list() {
    return store.snapshot().forms;
  }

  @GetMapping("/{id}")
  public FormDefinition get(@PathVariable String id) {
    return store.snapshot().forms.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Form not found"));
  }

  @PostMapping("/lint")
  public FormLinter.Lint lintOnly(@Valid @RequestBody FormInput in) {
    return lint(in);
  }

  @PostMapping
  public ResponseEntity<SaveResult> create(@Valid @RequestBody FormInput in) {
    FormLinter.Lint lint = check(in);
    String now = Instant.now().toString();
    FormDefinition form = new FormDefinition("form-" + UUID.randomUUID(), in.name().trim(), blank(in.description()), in.objectClasses(),
        in.rdnAttribute(), in.fields(), now, now);
    store.update(d -> d.forms.add(form));
    record("form.create", null, form);
    return ResponseEntity.status(HttpStatus.CREATED).body(new SaveResult(form, lint.warnings()));
  }

  @PutMapping("/{id}")
  public SaveResult update(@PathVariable String id, @Valid @RequestBody FormInput in) {
    FormLinter.Lint lint = check(in);
    AtomicReference<FormDefinition> previous = new AtomicReference<>();
    FormDefinition saved = store.update(d -> {
      for (int i = 0; i < d.forms.size(); i++) {
        FormDefinition f = d.forms.get(i);
        if (f.id().equals(id)) {
          previous.set(f);
          FormDefinition next = new FormDefinition(id, in.name().trim(), blank(in.description()), in.objectClasses(), in.rdnAttribute(),
              in.fields(), f.createdAt(), Instant.now().toString());
          d.forms.set(i, next);
          return next;
        }
      }
      throw ApiException.notFound("Form not found");
    });
    record("form.update", previous.get(), saved);
    return new SaveResult(saved, lint.warnings());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    FormDefinition removed = store.update(d -> {
      List<DirectoryDefinition> used = d.definitions.stream().filter(x -> x.formId().equals(id)).toList();
      if (!used.isEmpty()) {
        throw ApiException.conflict("Form is used by: " + used.stream().map(DirectoryDefinition::name).collect(Collectors.joining(", ")));
      }
      FormDefinition f = d.forms.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Form not found"));
      d.forms.remove(f);
      return f;
    });
    record("form.delete", removed, null);
    return ResponseEntity.noContent().build();
  }
}
