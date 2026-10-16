// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

/** A license file that can't be trusted at all: not a license, or not signed by ENS. */
public final class LicenseException extends Exception {
    public enum Reason {
        /** Not an ENS license file, or damaged beyond reading. */
        MALFORMED,
        /** Signed with a key this application doesn't trust. */
        UNKNOWN_KEY,
        /** The signature doesn't match: the file was edited or damaged. */
        BAD_SIGNATURE,
        /** A newer license format than this library understands. */
        UNSUPPORTED_FORMAT
    }

    private final Reason reason;

    public LicenseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
