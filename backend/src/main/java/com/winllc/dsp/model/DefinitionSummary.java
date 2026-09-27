package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** A definition plus the current user's effective access (flattened in JSON). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DefinitionSummary(@JsonUnwrapped DirectoryDefinition definition, AccessLevel access, String formName) {}
