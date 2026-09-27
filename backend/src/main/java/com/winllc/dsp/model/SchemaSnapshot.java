package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaSnapshot(
    List<AttributeTypeDef> attributeTypes,
    List<ObjectClassDef> objectClasses,
    String fetchedAt,
    String subschemaDn) {}
