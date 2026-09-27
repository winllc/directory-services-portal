package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A form: how entries of an object type are displayed and edited. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormDefinition(
    String id,
    String name,
    String description,
    List<String> objectClasses,
    String rdnAttribute,
    List<FormField> fields,
    String createdAt,
    String updatedAt) {}
