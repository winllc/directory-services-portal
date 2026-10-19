// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
package com.enssolutions.license;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Where a {@link LicenseManager} keeps an installation's license state: the installed license
 * text, the installation id, and the highest revision accepted per license id.
 *
 * <ul>
 *   <li>{@link FileLicenseStore}: a license file and a state directory on a local volume, as in
 *       every release before 1.2.0. One replica.</li>
 *   <li>{@link JdbcLicenseStore}: the application's database, so any number of replicas share
 *       one installation id, one license and one revision history.</li>
 * </ul>
 *
 * <p>Implementations must be safe to use from several threads, and, for a store shared between
 * processes, from several processes at once: {@link #installationId(String)} creates the id at
 * most once, and {@link #raiseRevision(String, int)} never lowers a revision.
 */
public interface LicenseStore {

    /** The installed license's text, with the {@link #licenseVersion() version} it was read at. */
    record StoredLicense(String text, Object version) {}

    /**
     * A cheap token that changes whenever the installed license changes (a file's modification
     * time, a row's version number). Empty when no license is installed.
     */
    Optional<Object> licenseVersion() throws IOException;

    /**
     * The installed license. Empty when none is installed.
     *
     * @throws LicenseException if what is stored is plainly not a license (too large to be one)
     */
    Optional<StoredLicense> readLicense() throws IOException, LicenseException;

    /** Replaces the installed license. The caller has already verified it. */
    void writeLicense(String text) throws IOException;

    /**
     * The installation id, storing {@code candidate} if there is none yet. Atomic: when several
     * callers race, all of them get the same id back.
     */
    String installationId(String candidate) throws IOException;

    /** The highest revision accepted for this license id, 0 if none. */
    int highestRevision(String licenseId) throws IOException;

    /**
     * Raises the highest revision accepted for this license id to {@code revision}, unless it is
     * already that high. Never lowers it. Returns the highest revision now stored.
     */
    int raiseRevision(String licenseId, int revision) throws IOException;

    /**
     * How long a {@link LicenseManager} may use what it last read before asking the store again
     * whether the license or revisions changed. Zero means on every check; a store shared with
     * other replicas returns a few seconds, so that a change made on one replica shows up on the
     * others within that time without a database query per request.
     */
    default Duration recheckInterval() {
        return Duration.ZERO;
    }

    /** Where the license is kept, for messages and the license screen: a path, or "the database". */
    String location();
}
