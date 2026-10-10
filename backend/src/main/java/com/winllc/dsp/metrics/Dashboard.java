package com.winllc.dsp.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A saved set of metric widgets. Shared dashboards are listed for every signed-in user; each
 * widget is still only computed for users who can read its source directory.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Dashboard(
    String id,
    String name,
    String description,
    boolean shared,
    List<MetricWidget> widgets,
    String createdAt,
    String updatedAt) {}
