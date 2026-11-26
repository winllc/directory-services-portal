package com.winllc.dsp.expiry;

import com.winllc.dsp.model.SessionUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Entries the signed-in user sponsors, and extending them (see {@link EntryExpiryService}). */
@RestController
@RequestMapping("/api/sponsored")
public class SponsorController {
  /** A new end date for one entry. */
  public record Renewal(@NotBlank @Size(max = 128) String definitionId, @NotBlank @Size(max = 1024) String dn,
      @NotBlank @Size(max = 32) String date) {}

  private final EntryExpiryService expiry;

  public SponsorController(EntryExpiryService expiry) {
    this.expiry = expiry;
  }

  @GetMapping
  public List<EntryExpiryService.Sponsored> list(@AuthenticationPrincipal SessionUser user) {
    return expiry.sponsored(user);
  }

  @PostMapping("/renew")
  public EntryExpiryService.Sponsored renew(@AuthenticationPrincipal SessionUser user, @Valid @RequestBody Renewal in) {
    return expiry.renew(user, in.definitionId(), in.dn(), in.date());
  }
}
