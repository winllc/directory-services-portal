package com.winllc.dsp.model;

import java.util.ArrayList;
import java.util.List;

/** Portal-side schema elements layered over the directory's schema. */
public record CustomSchema(List<AttributeTypeDef> attributeTypes, List<ObjectClassDef> objectClasses) {
  public static CustomSchema empty() {
    return new CustomSchema(new ArrayList<>(), new ArrayList<>());
  }
}
