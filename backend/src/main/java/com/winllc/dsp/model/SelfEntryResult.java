package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SelfEntryResult(
    DefinitionSummary definition,
    FormDefinition form,
    List<DirectoryEntry> entries,
    List<String> editableFields,
    String error) {}
