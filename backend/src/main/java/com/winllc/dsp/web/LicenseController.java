package com.winllc.dsp.web;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.dsp.audit.AuditLifecycle;
import com.winllc.dsp.audit.AuditLog;
import com.winllc.dsp.license.LicensedFeature;
import com.winllc.dsp.license.Licensing;
import com.winllc.dsp.model.LicenseStatus;
import com.winllc.dsp.model.SessionUser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The license: its status for the web client (any signed-in user; details for administrators),
 * and the license screen's upload (administrators). Both work whatever the license's state.
 */
@RestController
public class LicenseController {
  /** Far larger than any license file. */
  static final int MAX_LICENSE_BYTES = 64 * 1024;

  private final Licensing licensing;
  private final AuditLog audit;
  private final AuditLifecycle lifecycle;

  public LicenseController(Licensing licensing, AuditLog audit, AuditLifecycle lifecycle) {
    this.licensing = licensing;
    this.audit = audit;
    this.lifecycle = lifecycle;
  }

  @GetMapping("/api/license")
  public LicenseStatus license(@AuthenticationPrincipal SessionUser user) {
    return status(licensing.current(), user != null && user.isAdmin());
  }

  /** Upload a license file as the request body (text/plain or application/octet-stream). */
  @PostMapping(path = "/api/admin/license", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
  public ResponseEntity<Map<String, Object>> uploadText(@RequestBody byte[] body) {
    return install(body);
  }

  /** Upload a license file as the multipart field {@code file}. */
  @PostMapping(path = "/api/admin/license", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
    if (file.getSize() > MAX_LICENSE_BYTES) return refused("That file is too large to be a license.", null);
    return install(file.getBytes());
  }

  /**
   * Audited as {@code license.installed} or {@code license.rejected} (with the reason); never the
   * license text. Refused up front when the audit log can't be written.
   */
  private ResponseEntity<Map<String, Object>> install(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      rejected("empty", "The license file is empty.");
      return refused("The license file is empty.", null);
    }
    if (bytes.length > MAX_LICENSE_BYTES) {
      rejected("too_large", "That file is too large to be a license.");
      return refused("That file is too large to be a license.", null);
    }
    audit.requireWritable();
    String previousMode = licensing.current().mode().name();
    LicenseCheck check;
    try {
      check = licensing.manager().install(new String(bytes, StandardCharsets.UTF_8));
    } catch (LicenseException e) {
      String why = "That isn't a valid ENS license: " + e.getMessage() + ".";
      rejected(String.valueOf(e.reason()), why);
      return refused(why, null);
    } catch (IOException e) {
      String why = "The license could not be saved to " + licensing.manager().licenseFile() + ": " + e.getMessage();
      audit.event("license.installed").target("license", Licensing.PRODUCT, "License").failed(why);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", why));
    }
    switch (check.state()) {
      case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> {
        // Not installed: the library refuses these and keeps the current file.
        AuditLog.Draft event = audit.event("license.rejected").target("license", Licensing.PRODUCT, "License")
            .detail("reasonCode", check.state().name());
        check.license().ifPresent(l -> event.detail("licenseId", l.id()).detail("revision", l.revision()));
        event.denied(check.message());
        return refused(check.message(), check.state());
      }
      default -> {
        AuditLog.Draft event = audit.event("license.installed").target("license", Licensing.PRODUCT, "License");
        check.license().ifPresent(l -> event.detail("licenseId", l.id()).detail("revision", l.revision()).detail("edition", l.edition())
            .detail("licensee", l.licensee()).detail("expiresAt", l.expiresAt().map(Object::toString).orElse(null)));
        event.detail("state", check.state()).detail("mode.old", previousMode).detail("mode.new", check.mode().name()).success();
      }
    }
    lifecycle.checkLicenseMode();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("installed", true);
    body.put("message", check.message());
    body.put("license", status(check, true));
    return ResponseEntity.ok(body);
  }

  private void rejected(String reasonCode, String why) {
    audit.event("license.rejected").target("license", Licensing.PRODUCT, "License").detail("reasonCode", reasonCode).denied(why);
  }

  private static ResponseEntity<Map<String, Object>> refused(String message, LicenseState state) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", message);
    body.put("code", "license_refused");
    if (state != null) body.put("state", state.name());
    return ResponseEntity.badRequest().body(body);
  }

  private LicenseStatus status(LicenseCheck check, boolean admin) {
    var features = Arrays.stream(LicensedFeature.values())
        .map(f -> new LicenseStatus.Feature(f.id(), f.label(), f.description(), Licensing.allows(check, f)))
        .toList();
    boolean renewal = check.showRenewalWarning();
    return new LicenseStatus(
        Licensing.PRODUCT,
        check.mode().name(),
        check.state().name(),
        check.message(),
        check.showEvaluationBanner(),
        admin && renewal ? Boolean.TRUE : null,
        admin && renewal ? check.daysLeft().orElse(null) : null,
        features,
        admin ? details(check) : null);
  }

  private LicenseStatus.Details details(LicenseCheck check) {
    LicenseManager manager = licensing.manager();
    String installationId = null;
    String installationIdError = null;
    try {
      installationId = manager.installationId();
    } catch (IllegalStateException e) {
      installationIdError = e.getMessage();
    }
    String file = manager.licenseFile().toAbsolutePath().toString();
    EnsLicense l = check.license().orElse(null);
    if (l == null) {
      return new LicenseStatus.Details(installationId, installationIdError, file,
          null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    return new LicenseStatus.Details(installationId, installationIdError, file,
        l.id(), l.revision(), l.licensee(), l.licenseeEmail(), l.edition(), l.support(),
        l.issuedAt(), l.notBefore(), l.expiresAt().orElse(null), l.graceDays(), l.maintenanceUntil().orElse(null),
        l.installationId().orElse(null),
        // Display only: counting directory users would mean scanning the whole directory.
        new LicenseStatus.Size(l.limitQuantity(), l.limitUnit(), null, null));
  }
}
