// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * The contents of a verified license file. Only {@link LicenseVerifier}
 * creates these, after checking the signature, so every field can be trusted.
 *
 * @param product     "certalert", "mfa-portal", "readyroom" or "directory-portal"
 * @param edition     "evaluation", "pilot", "subscription" or "perpetual"
 * @param features    feature ids, "&lt;product&gt;.&lt;feature&gt;", e.g. "certalert.revocation-checks"
 * @param expiresAt   empty for a perpetual license
 * @param maintenanceUntil perpetual only: releases built after this are not covered
 * @param installationId   when present, only that installation accepts the license
 * @param watermark   the application must show an evaluation banner
 */
public record EnsLicense(
        String id,
        int revision,
        String keyId,
        String issuer,
        String product,
        String edition,
        String licenseeName,
        String licenseeOrganization,
        String licenseeEmail,
        long limitQuantity,
        String limitUnit,
        Set<String> features,
        String support,
        Instant issuedAt,
        Instant notBefore,
        Optional<Instant> expiresAt,
        int graceDays,
        Optional<Instant> maintenanceUntil,
        Optional<String> installationId,
        boolean watermark) {

    public boolean isEvaluation() {
        return "evaluation".equals(edition);
    }

    public boolean isPerpetual() {
        return "perpetual".equals(edition);
    }

    /** Who the license is for, as an application's license screen should show it. */
    public String licensee() {
        return licenseeOrganization.isBlank() ? licenseeName : licenseeName + ", " + licenseeOrganization;
    }
}
