package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.List;

/** The authenticated user, stored in the HTTP session. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionUser(
    String username,
    String dn,
    String displayName,
    String mail,
    List<String> groups,
    @JsonProperty("isAdmin") boolean isAdmin)
    implements Serializable {}
