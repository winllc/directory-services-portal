package com.winllc.dsp.web;

import com.winllc.dsp.license.LicenseService;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.model.LicenseInfo;
import com.winllc.dsp.model.SessionUser;
import java.util.Arrays;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which licensed features this deployment may use (the web client hides the others). */
@RestController
public class LicenseController {
  private final LicenseService licenses;

  public LicenseController(LicenseService licenses) {
    this.licenses = licenses;
  }

  @GetMapping("/api/license")
  public LicenseInfo license(@AuthenticationPrincipal SessionUser user) {
    LicenseService.Status s = licenses.status();
    var features = Arrays.stream(LicensedFeature.values())
        .map(f -> new LicenseInfo.Feature(f.id(), f.label(), licenses.isEnabled(f.id())))
        .toList();
    return new LicenseInfo(s.state().name(), s.product(), s.licensee(), s.licenseId(), s.expires(), s.problem(),
        user != null && user.isAdmin() ? s.file() : null, features);
  }
}
