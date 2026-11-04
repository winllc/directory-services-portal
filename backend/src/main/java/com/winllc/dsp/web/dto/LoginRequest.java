package com.winllc.dsp.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code directory}: the LDAP directory chosen on the login page (its id); none: the primary one. */
public record LoginRequest(@NotBlank @Size(max = 256) String username, @NotBlank @Size(max = 1024) String password, @Size(max = 64) String directory) {

  public LoginRequest(String username, String password) {
    this(username, password, null);
  }
}
