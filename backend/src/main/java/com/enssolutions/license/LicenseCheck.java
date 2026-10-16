// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The outcome of checking the installed license: what state it is in, what
 * the application may do, and what to tell the administrator. This is the
 * one object application code asks about features and limits.
 *
 * <pre>
 * LicenseCheck check = licenses.current();
 * if (check.isFeatureEnabled("revocation-checks")) { ... }
 * if (!check.mayAdd(peopleCount)) { stop adding people, show check.usageMessage(peopleCount) }
 * </pre>
 */
public final class LicenseCheck {
    /** How far over its limit a deployment may go before it stops taking on more. */
    public static final double OVERAGE_TOLERANCE = 0.10;
    /** Show a renewal reminder this many days before expiry. */
    public static final int WARN_DAYS = 30;

    public enum Usage {
        WITHIN,
        /** Over the limit, by no more than {@link #OVERAGE_TOLERANCE}: warn. */
        WARNING,
        /** Further over: stop taking on more. Never remove what is there. */
        EXCEEDED
    }

    private final String product;
    private final EnsLicense license;
    private final LicenseState state;
    private final String message;
    private final Instant checkedAt;

    LicenseCheck(String product, EnsLicense license, LicenseState state, String message, Instant checkedAt) {
        this.product = product;
        this.license = license;
        this.state = state;
        this.message = message;
        this.checkedAt = checkedAt;
    }

    public Optional<EnsLicense> license() {
        return Optional.ofNullable(license);
    }

    public LicenseState state() {
        return state;
    }

    public LicenseState.Mode mode() {
        return state.mode();
    }

    /** One sentence for the license screen and the administrator banner. */
    public String message() {
        return message;
    }

    public boolean isFullyLicensed() {
        return mode() == LicenseState.Mode.FULL;
    }

    public boolean isReadOnly() {
        return mode() != LicenseState.Mode.FULL;
    }

    /**
     * Whether a feature may be used: the license is in full effect and lists
     * it. Accepts "revocation-checks" or "certalert.revocation-checks".
     */
    public boolean isFeatureEnabled(String feature) {
        if (license == null || !isFullyLicensed()) {
            return false;
        }
        String id = feature.contains(".") ? feature : product + "." + feature;
        return license.features().contains(id);
    }

    /** Show the evaluation banner on every page. */
    public boolean showEvaluationBanner() {
        return license != null && license.watermark() && mode() != LicenseState.Mode.UNLICENSED;
    }

    /** Days until the license expires (or, in grace, until grace ends). Empty for perpetual. */
    public Optional<Long> daysLeft() {
        if (license == null || license.expiresAt().isEmpty()) return Optional.empty();
        Instant end = license.expiresAt().get();
        if (state == LicenseState.GRACE) end = end.plus(Duration.ofDays(license.graceDays()));
        long seconds = Duration.between(checkedAt, end).getSeconds();
        return Optional.of(Math.max(0, (seconds + 86_399) / 86_400));
    }

    /** Show administrators a renewal reminder. */
    public boolean showRenewalWarning() {
        return state == LicenseState.GRACE
                || (state == LicenseState.VALID && daysLeft().map(d -> d <= WARN_DAYS).orElse(false));
    }

    /** How a current count of people, servers or tokens stands against the license. */
    public Usage usage(long count) {
        if (license == null) return Usage.EXCEEDED;
        long limit = license.limitQuantity();
        if (count <= limit) return Usage.WITHIN;
        return count <= Math.floor(limit * (1 + OVERAGE_TOLERANCE)) ? Usage.WARNING : Usage.EXCEEDED;
    }

    /** Whether one more may be added to a current count. */
    public boolean mayAdd(long currentCount) {
        return isFullyLicensed() && usage(currentCount + 1) != Usage.EXCEEDED;
    }

    public String usageMessage(long count) {
        if (license == null) return "No license is installed.";
        String of = count + " of " + license.limitQuantity() + " " + license.limitUnit();
        return switch (usage(count)) {
            case WITHIN -> of + ".";
            case WARNING -> of + ": over the licensed size. Update the license to stay covered.";
            case EXCEEDED -> of + ": more than " + Math.round(OVERAGE_TOLERANCE * 100)
                    + "% over the licensed size, so no more are being added until the license is updated.";
        };
    }
}
