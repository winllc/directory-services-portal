// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
package com.enssolutions.license;

/** Where a license stands for this application, right now. */
public enum LicenseState {
    /** In effect. */
    VALID(Mode.FULL),
    /** Expired, but within its grace period: everything works, with a renewal warning. */
    GRACE(Mode.FULL),
    /** Past expiry and grace. */
    EXPIRED(Mode.READ_ONLY),
    /** Perpetual license whose maintenance ended before this release was built. */
    RELEASE_NOT_COVERED(Mode.READ_ONLY),
    /** Its start date hasn't come yet. */
    NOT_YET_VALID(Mode.UNLICENSED),
    /** A license for a different product. */
    WRONG_PRODUCT(Mode.UNLICENSED),
    /** Bound to a different installation. */
    WRONG_INSTALLATION(Mode.UNLICENSED),
    /** An older revision of a license this installation has already seen a newer one of. */
    SUPERSEDED(Mode.UNLICENSED),
    /** No license file installed. */
    MISSING(Mode.UNLICENSED),
    /** A file that isn't a genuine ENS license. */
    INVALID(Mode.UNLICENSED);

    private final Mode mode;

    LicenseState(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * What the application does in each state. The same rules are on the
     * site's licensing page, so customers and the application agree.
     */
    public enum Mode {
        /** Everything the license's features allow. */
        FULL,
        /**
         * Data stays visible and exportable; nothing changes it. Scheduled
         * jobs, syncs and notifications pause. The license screen works.
         */
        READ_ONLY,
        /** Only sign-in and the license screen, so an administrator can install a license. */
        UNLICENSED
    }
}
