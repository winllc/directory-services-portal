package com.winllc.dsp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/license. Every signed-in user gets what the web client needs to decide what to show
 * ({@code mode}, {@code state}, {@code message}, the banners and the features); administrators
 * also get the license details, this installation's id and the license file's path ({@link Details}).
 *
 * @param mode           FULL, READ_ONLY or UNLICENSED
 * @param state          the library's LicenseState (VALID, GRACE, EXPIRED, MISSING, ...)
 * @param evaluation     show the evaluation banner
 * @param renewalWarning show administrators a renewal reminder ({@code daysLeft} days left)
 * @param features       every catalog feature of the product, and whether it can be used now
 * @param menuVisible    show the license page's entry in the navigation menu (ENS_LICENSE_MENU_VISIBLE);
 *                       false hides only that entry, not the page or the ways to it while a license is
 *                       missing, expiring or read-only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LicenseStatus(
    String product,
    String mode,
    String state,
    String message,
    boolean evaluation,
    Boolean renewalWarning,
    Long daysLeft,
    List<Feature> features,
    Details details,
    boolean menuVisible) {

  public record Feature(String id, String label, String description, boolean enabled) {}

  /** For administrators (the license screen). License fields are null when no license file was read. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Details(
      String installationId,
      String installationIdError,
      String file,
      String licenseId,
      Integer revision,
      String licensee,
      String email,
      String edition,
      String support,
      Instant issuedAt,
      Instant notBefore,
      Instant expiresAt,
      Integer graceDays,
      Instant maintenanceUntil,
      String boundTo,
      Size size) {}

  /**
   * The licensed size. {@code used} and {@code usage} are null: the portal does not count directory
   * users (it would mean a full directory scan), so the size is shown, not enforced.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Size(long quantity, String unit, Long used, String usage) {}
}
