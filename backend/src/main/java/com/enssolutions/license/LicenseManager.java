// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
package com.enssolutions.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The installed license for one application. Keeps the license, the
 * installation id and the highest revision of each license it has accepted (so
 * an older file can't be put back) in a {@link LicenseStore}: a file and a state
 * directory ({@link FileLicenseStore}, one replica), or the application's
 * database ({@link JdbcLicenseStore}, any number of replicas). Re-reads the
 * license when it changes, including when another replica changed it.
 *
 * <pre>
 * LicenseManager licenses = LicenseManager.fromEnvironment(
 *         "certalert", LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS), Optional.of(BUILD_DATE));
 * LicenseCheck check = licenses.current();
 * </pre>
 *
 * <p>Several replicas: {@code LicenseManager.fromEnvironment(product, verifier, releaseDate, dataSource)}.
 *
 * <p>Environment (or pass paths, or a store, to the constructor):
 * <ul>
 *   <li>{@code ENS_LICENSE_FILE}: the license file, default {@code /etc/ens/<product>.lic}</li>
 *   <li>{@code ENS_LICENSE_STATE_DIR}: where the installation id and revisions are kept,
 *       default {@code /var/lib/ens/<product>}. Keep it on a persistent volume.</li>
 *   <li>{@code ENS_LICENSE}: optional, the license itself rather than a path. It takes the
 *       whole file, the token on one line (or, for a file with several signatures, each block's
 *       token on its own line), or the file base64-encoded. It is installed (written
 *       to the license file) the first time the license is checked, if no usable license is
 *       installed yet or if it is a newer revision of the installed one; a license installed any
 *       other way, such as an upload on the license screen, is otherwise left alone.</li>
 * </ul>
 *
 * <p>Thread-safe. {@link #current()} is cheap: it re-reads the license only when
 * it changes (a file's modification time, a database row's version, asked for at
 * most every {@link LicenseStore#recheckInterval()}), so call it per request if
 * that's convenient.
 */
public final class LicenseManager {
    private final String product;
    private final LicenseVerifier verifier;
    private final LicenseStore store;
    private final Optional<Instant> releaseDate;
    private final Clock clock;
    private final Optional<String> environmentLicense;
    private boolean environmentApplied;
    private String environmentStatus;

    private Object loadedVersion;
    private EnsLicense loaded;
    private String loadError;
    private String installationId;
    /** Highest revisions read since the last recheck, per license id. */
    private final Map<String, Integer> highest = new HashMap<>();
    /** The last highest revision read, per license id, for when a shared store can't be reached. */
    private final Map<String, Integer> lastKnownHighest = new HashMap<>();
    private long checkedAt;
    private boolean checked;

    public LicenseManager(String product, LicenseVerifier verifier, Path licenseFile, Path stateDir,
                          Optional<Instant> releaseDate, Clock clock) {
        this(product, verifier, licenseFile, stateDir, releaseDate, clock, Optional.empty());
    }

    /**
     * @param environmentLicense the license text from {@code ENS_LICENSE}, if any; see the class
     *        description for when it is installed.
     */
    public LicenseManager(String product, LicenseVerifier verifier, Path licenseFile, Path stateDir,
                          Optional<Instant> releaseDate, Clock clock, Optional<String> environmentLicense) {
        this(product, verifier, new FileLicenseStore(licenseFile, stateDir), releaseDate, clock, environmentLicense);
    }

    /**
     * @param store where the license, installation id and revisions are kept
     * @param environmentLicense the license text from {@code ENS_LICENSE}, if any; see the class
     *        description for when it is installed.
     */
    public LicenseManager(String product, LicenseVerifier verifier, LicenseStore store,
                          Optional<Instant> releaseDate, Clock clock, Optional<String> environmentLicense) {
        this.environmentLicense = environmentLicense.map(String::trim).filter(t -> !t.isEmpty());
        this.product = Objects.requireNonNull(product, "product");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.store = Objects.requireNonNull(store, "store");
        this.releaseDate = releaseDate;
        this.clock = clock;
    }

    /** One replica: the license file and state directory from the environment (see the class description). */
    public static LicenseManager fromEnvironment(String product, LicenseVerifier verifier, Optional<Instant> releaseDate) {
        return new LicenseManager(product, verifier, environmentLicenseFile(product), environmentStateDir(product),
                releaseDate, Clock.systemUTC(), Optional.ofNullable(System.getenv("ENS_LICENSE")));
    }

    /**
     * Any number of replicas: the license state in the application's database (the tables of
     * {@link JdbcLicenseStore.Dialect#ddl()}, created by the application's migrations). On first
     * use (not here, so the migrations may run after this bean is made) it adopts the
     * installation id, revisions and license from {@code ENS_LICENSE_STATE_DIR} and
     * {@code ENS_LICENSE_FILE} if they exist and the database has no installation id yet, so an
     * existing installation keeps its id. {@code ENS_LICENSE} works as with files.
     *
     * <p>If the database can't be reached for a moment, the license read last stays in force.
     */
    public static LicenseManager fromEnvironment(String product, LicenseVerifier verifier, Optional<Instant> releaseDate,
                                                 DataSource dataSource) {
        LicenseStore store = new JdbcLicenseStore(dataSource, product)
                .importingFileStateOnFirstUse(environmentLicenseFile(product), environmentStateDir(product));
        return new LicenseManager(product, verifier, store, releaseDate, Clock.systemUTC(),
                Optional.ofNullable(System.getenv("ENS_LICENSE")));
    }

    static Path environmentLicenseFile(String product) {
        return Path.of(System.getenv().getOrDefault("ENS_LICENSE_FILE", "/etc/ens/" + product + ".lic"));
    }

    static Path environmentStateDir(String product) {
        return Path.of(System.getenv().getOrDefault("ENS_LICENSE_STATE_DIR", "/var/lib/ens/" + product));
    }

    public String product() {
        return product;
    }

    /** The license file, or null when the license is kept elsewhere (see {@link #licenseLocation()}). */
    public Path licenseFile() {
        return store instanceof FileLicenseStore f ? f.licenseFile() : null;
    }

    /** Where the license is kept, for the license screen: the file's path, or "the database". */
    public String licenseLocation() {
        return store.location();
    }

    public LicenseStore store() {
        return store;
    }

    /** This installation's id, for the license screen. Created on first use. */
    public synchronized String installationId() {
        if (installationId == null) {
            try {
                installationId = store.installationId(InstallationId.generate(product));
            } catch (IOException e) {
                throw new IllegalStateException("Cannot keep the installation id in " + store.location() + ": " + e.getMessage(), e);
            }
        }
        return installationId;
    }

    /**
     * What happened to {@code ENS_LICENSE}: installed, kept the installed license instead, or
     * why it was refused. Empty when the variable isn't set or hasn't been looked at yet.
     */
    public synchronized Optional<String> environmentLicenseStatus() {
        return Optional.ofNullable(environmentStatus);
    }

    /** The license as it stands now. */
    public synchronized LicenseCheck current() {
        applyEnvironmentLicenseOnce();
        Instant now = clock.instant();
        refreshIfDue(false);
        if (loaded == null) {
            return loadError == null
                    ? LicensePolicy.missing(product, now, "No license is installed. Upload one on the license screen.")
                    : LicensePolicy.invalid(product, now, loadError);
        }
        LicenseCheck check = LicensePolicy.evaluate(loaded, context(loaded, now));
        if (check.state() != LicenseState.SUPERSEDED && check.state().mode() != LicenseState.Mode.UNLICENSED) {
            remember(loaded);
        }
        return check;
    }

    /**
     * Checks an uploaded license and, if this installation can use it,
     * replaces the installed file with it. Returns the check either way, so the
     * license screen can say why a file was refused.
     */
    public synchronized LicenseCheck install(String fileText) throws LicenseException, IOException {
        EnsLicense candidate = verifier.verify(fileText);
        refreshIfDue(true); // the latest revisions, from every replica
        LicenseCheck check = LicensePolicy.evaluate(candidate, context(candidate, clock.instant()));
        switch (check.state()) {
            case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> {
                return check;
            }
            default -> {
                store.writeLicense(fileText);
                loadedVersion = null; // re-read it even if the file's time didn't change
                refreshIfDue(true);
                return current();
            }
        }
    }

    /* ---- ENS_LICENSE --------------------------------------------------------- */

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{16}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final System.Logger LOG = System.getLogger(LicenseManager.class.getName());

    /** The file text for a license given as the file, the bare token, or the file in base64. */
    static String licenseText(String value) throws LicenseException {
        // The file in base64 is a third longer than the file; anything well past that is not a license.
        if (value.length() > 2 * LicenseVerifier.MAX_FILE_CHARS) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "ENS_LICENSE is not a license: longer than "
                    + 2 * LicenseVerifier.MAX_FILE_CHARS + " characters");
        }
        String v = value.trim();
        if (v.contains("-----BEGIN ENS LICENSE-----")) {
            return v.endsWith("\n") ? v : v + "\n";
        }
        String compact = v.replaceAll("\\s+", "");
        if (TOKEN.matcher(compact).matches()) {
            return "-----BEGIN ENS LICENSE-----\n" + compact + "\n-----END ENS LICENSE-----\n";
        }
        // Several tokens, one per line: the lines between each BEGIN and END of a dual-signed file.
        String[] lines = v.split("\\s+");
        if (lines.length > 1 && Arrays.stream(lines).allMatch(l -> TOKEN.matcher(l).matches())) {
            StringBuilder file = new StringBuilder();
            for (String l : lines) file.append("-----BEGIN ENS LICENSE-----\n").append(l).append("\n-----END ENS LICENSE-----\n");
            return file.toString();
        }
        try {
            String decoded = new String(Base64.getMimeDecoder().decode(v), StandardCharsets.UTF_8);
            if (decoded.contains("-----BEGIN ENS LICENSE-----")
                    || TOKEN.matcher(decoded.replaceAll("\\s+", "")).matches()) {
                return licenseText(decoded);
            }
        } catch (IllegalArgumentException e) {
            // not base64 either
        }
        throw new LicenseException(LicenseException.Reason.MALFORMED, "ENS_LICENSE is not a license: give the license file's text, the line between "
                + "its BEGIN and END lines, or the file base64-encoded");
    }

    private void applyEnvironmentLicenseOnce() {
        if (environmentApplied || environmentLicense.isEmpty()) {
            return;
        }
        environmentApplied = true;
        String status;
        System.Logger.Level level = System.Logger.Level.INFO;
        try {
            String text = licenseText(environmentLicense.get());
            EnsLicense candidate = verifier.verify(text);
            refreshIfDue(true);
            EnsLicense installed = loaded;
            boolean installedUsable = installed != null && LicensePolicy.evaluate(installed,
                    context(installed, clock.instant())).state().mode() != LicenseState.Mode.UNLICENSED;
            if (installedUsable && !installed.id().equals(candidate.id())) {
                status = "ENS_LICENSE (" + candidate.id() + ") was not installed: license " + installed.id()
                        + " is already installed. Remove it, or upload the new one on the license screen, to change licenses.";
            } else if (installedUsable && candidate.revision() <= installed.revision()) {
                status = "ENS_LICENSE (" + candidate.id() + " revision " + candidate.revision()
                        + ") is already installed, or an older revision of the installed license.";
            } else {
                LicenseCheck check = install(text);
                if (check.state() == LicenseState.WRONG_PRODUCT || check.state() == LicenseState.WRONG_INSTALLATION
                        || check.state() == LicenseState.SUPERSEDED) {
                    level = System.Logger.Level.WARNING;
                    status = "ENS_LICENSE (" + candidate.id() + ") was refused: " + check.message();
                } else {
                    status = "ENS_LICENSE (" + candidate.id() + " revision " + candidate.revision()
                            + ") was installed to " + store.location() + ".";
                }
            }
        } catch (LicenseException e) {
            level = System.Logger.Level.WARNING;
            status = "ENS_LICENSE was not installed: " + e.getMessage() + ".";
        } catch (IOException | RuntimeException e) {
            level = System.Logger.Level.WARNING;
            status = "ENS_LICENSE could not be written to " + store.location() + ": " + e.getMessage();
        }
        environmentStatus = status;
        LOG.log(level, status);
    }

    private LicensePolicy.Context context(EnsLicense l, Instant now) {
        return new LicensePolicy.Context(product, installationId(), releaseDate, highestRevision(l.id()), now);
    }

    /**
     * Looks for changes in the store (an edited file, an upload on another replica) unless it did
     * so less than the store's recheck interval ago.
     */
    private void refreshIfDue(boolean force) {
        long now = System.nanoTime();
        if (!force && checked && now - checkedAt < store.recheckInterval().toNanos()) {
            return;
        }
        checked = true;
        checkedAt = now;
        highest.clear();
        reloadIfChanged();
    }

    private String fileOr(String what) {
        return store instanceof FileLicenseStore ? "license file" : what;
    }

    /**
     * A shared store that can't be reached for a moment (a database fail-over) keeps the license
     * read last in force rather than locking every replica out. The file store, as before 1.2.0,
     * reports the error.
     */
    private boolean keepLastKnown(IOException e) {
        if (store instanceof FileLicenseStore || loaded == null) return false;
        LOG.log(System.Logger.Level.WARNING, "License state in " + store.location()
                + " can't be read; keeping license " + loaded.id() + " in force: " + e.getMessage());
        return true;
    }

    private void reloadIfChanged() {
        Optional<Object> version;
        try {
            version = store.licenseVersion();
        } catch (IOException e) {
            if (keepLastKnown(e)) return;
            loaded = null;
            loadError = "The " + fileOr("license") + " can't be read: " + e.getMessage();
            return;
        }
        if (version.isEmpty()) {
            loaded = null;
            loadError = null;
            loadedVersion = null;
            return;
        }
        if (version.get().equals(loadedVersion)) {
            return;
        }
        loadedVersion = version.get();
        try {
            Optional<LicenseStore.StoredLicense> stored = store.readLicense();
            if (stored.isEmpty()) {
                loaded = null;
                loadError = null;
                loadedVersion = null;
                return;
            }
            loadedVersion = stored.get().version();
            loaded = verifier.verify(stored.get().text());
            loadError = null;
        } catch (LicenseException e) {
            loaded = null;
            loadError = "The installed " + fileOr("license") + " isn't valid: " + e.getMessage() + ".";
        } catch (IOException e) {
            if (keepLastKnown(e)) {
                loadedVersion = null; // read it again next time
                return;
            }
            loaded = null;
            loadError = "The " + fileOr("license") + " can't be read: " + e.getMessage();
        }
    }

    /* ---- Highest revision seen, per license id ------------------------------ */

    private int highestRevision(String licenseId) {
        Integer known = highest.get(licenseId);
        if (known != null) return known;
        try {
            int h = store.highestRevision(licenseId);
            highest.put(licenseId, h);
            lastKnownHighest.put(licenseId, h);
            return h;
        } catch (IOException e) {
            Integer last = lastKnownHighest.get(licenseId);
            if (last != null && keepLastKnown(e)) return last;
            throw new IllegalStateException("Cannot read the license revisions in " + store.location() + ": " + e.getMessage(), e);
        }
    }

    private void remember(EnsLicense l) {
        if (l.revision() <= highestRevision(l.id())) return;
        try {
            int h = store.raiseRevision(l.id(), l.revision());
            highest.put(l.id(), h);
            lastKnownHighest.put(l.id(), h);
        } catch (IOException e) {
            if (keepLastKnown(e)) return; // tried again on the next check
            throw new IllegalStateException("Cannot keep the license revision in " + store.location() + ": " + e.getMessage(), e);
        }
    }
}
