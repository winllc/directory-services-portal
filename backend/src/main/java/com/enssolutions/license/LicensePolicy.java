// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides what state a verified license is in for one installation. Pure:
 * everything it depends on is passed in, which is what makes it testable and
 * keeps every application applying the same rules.
 */
public final class LicensePolicy {
    private LicensePolicy() {}

    /**
     * @param product           this application's product id, e.g. "certalert"
     * @param installationId    this installation's id (see {@link InstallationId})
     * @param releaseDate       when this build was released; perpetual licenses
     *                          cover releases up to their maintenance date
     * @param highestRevision   highest revision of this license id seen before, or 0
     */
    public record Context(String product, String installationId, Optional<Instant> releaseDate, int highestRevision, Instant now) {}

    public static LicenseCheck evaluate(EnsLicense l, Context c) {
        if (!c.product().equals(l.product())) {
            return check(c, l, LicenseState.WRONG_PRODUCT, "This is a license for " + l.product() + ", not " + c.product() + ".");
        }
        if (l.installationId().isPresent() && !l.installationId().get().equalsIgnoreCase(c.installationId())) {
            return check(c, l, LicenseState.WRONG_INSTALLATION,
                    "This license is bound to installation " + l.installationId().get() + "; this one is " + c.installationId() + ".");
        }
        if (l.revision() < c.highestRevision()) {
            return check(c, l, LicenseState.SUPERSEDED,
                    "A newer revision (" + c.highestRevision() + ") of this license has been installed here; install that one.");
        }
        if (c.now().isBefore(l.notBefore())) {
            return check(c, l, LicenseState.NOT_YET_VALID, "This license starts on " + l.notBefore().toString().substring(0, 10) + ".");
        }
        if (l.expiresAt().isPresent()) {
            Instant expires = l.expiresAt().get();
            if (!c.now().isBefore(expires)) {
                Instant graceEnd = expires.plus(Duration.ofDays(l.graceDays()));
                if (c.now().isBefore(graceEnd)) {
                    return check(c, l, LicenseState.GRACE,
                            "This license expired on " + day(expires) + ". Renew it before " + day(graceEnd) + " to keep full use.");
                }
                return check(c, l, LicenseState.EXPIRED,
                        "This license expired on " + day(expires) + ". The application is read-only until a renewed license is installed.");
            }
        }
        if (l.isPerpetual() && l.maintenanceUntil().isPresent() && c.releaseDate().isPresent()
                && c.releaseDate().get().isAfter(l.maintenanceUntil().get())) {
            return check(c, l, LicenseState.RELEASE_NOT_COVERED,
                    "This release is newer than your maintenance, which ended " + day(l.maintenanceUntil().get())
                            + ". Renew maintenance, or run a release from before then.");
        }
        String until = l.expiresAt().map(e -> " until " + day(e)).orElse("");
        return check(c, l, LicenseState.VALID, "Licensed to " + l.licensee() + until + ".");
    }

    static LicenseCheck missing(String product, Instant now, String why) {
        return new LicenseCheck(product, null, LicenseState.MISSING, why, now);
    }

    static LicenseCheck invalid(String product, Instant now, String why) {
        return new LicenseCheck(product, null, LicenseState.INVALID, why, now);
    }

    private static LicenseCheck check(Context c, EnsLicense l, LicenseState s, String message) {
        return new LicenseCheck(c.product(), l, s, message, c.now());
    }

    private static String day(Instant i) {
        return i.toString().substring(0, 10);
    }
}
