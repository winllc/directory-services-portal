// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The installed license for one application. Holds the license file and the
 * installation's state directory, re-reads the file when it changes, and
 * remembers the highest revision of each license it has accepted so an older
 * file can't be put back.
 *
 * <pre>
 * LicenseManager licenses = LicenseManager.fromEnvironment(
 *         "certalert", LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS), Optional.of(BUILD_DATE));
 * LicenseCheck check = licenses.current();
 * </pre>
 *
 * <p>Environment (or pass paths to the constructor):
 * <ul>
 *   <li>{@code ENS_LICENSE_FILE}: the license file, default {@code /etc/ens/<product>.lic}</li>
 *   <li>{@code ENS_LICENSE_STATE_DIR}: where the installation id and revisions are kept,
 *       default {@code /var/lib/ens/<product>}. Keep it on a persistent volume.</li>
 *   <li>{@code ENS_LICENSE}: optional, the license itself rather than a path. It takes the
 *       whole file, the token on one line, or the file base64-encoded. It is installed (written
 *       to the license file) the first time the license is checked, if no usable license is
 *       installed yet or if it is a newer revision of the installed one; a license installed any
 *       other way, such as an upload on the license screen, is otherwise left alone.</li>
 * </ul>
 *
 * <p>Thread-safe. {@link #current()} is cheap: it re-reads the file only when
 * its modification time changes, so call it per request if that's convenient.
 */
public final class LicenseManager {
    private final String product;
    private final LicenseVerifier verifier;
    private final Path licenseFile;
    private final Path stateDir;
    private final Optional<Instant> releaseDate;
    private final Clock clock;
    private final Optional<String> environmentLicense;
    private boolean environmentApplied;
    private String environmentStatus;

    private FileTime loadedModified;
    private EnsLicense loaded;
    private String loadError;
    private String installationId;

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
        this.environmentLicense = environmentLicense.map(String::trim).filter(t -> !t.isEmpty());
        this.product = product;
        this.verifier = verifier;
        this.licenseFile = licenseFile;
        this.stateDir = stateDir;
        this.releaseDate = releaseDate;
        this.clock = clock;
    }

    public static LicenseManager fromEnvironment(String product, LicenseVerifier verifier, Optional<Instant> releaseDate) {
        String file = System.getenv().getOrDefault("ENS_LICENSE_FILE", "/etc/ens/" + product + ".lic");
        String state = System.getenv().getOrDefault("ENS_LICENSE_STATE_DIR", "/var/lib/ens/" + product);
        return new LicenseManager(product, verifier, Path.of(file), Path.of(state), releaseDate, Clock.systemUTC(),
                Optional.ofNullable(System.getenv("ENS_LICENSE")));
    }

    public String product() {
        return product;
    }

    public Path licenseFile() {
        return licenseFile;
    }

    /** This installation's id, for the license screen. Created on first use. */
    public synchronized String installationId() {
        if (installationId == null) {
            try {
                installationId = InstallationId.loadOrCreate(stateDir, product);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot keep the installation id in " + stateDir + ": " + e.getMessage(), e);
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
        reloadIfChanged();
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
        LicenseCheck check = LicensePolicy.evaluate(candidate, context(candidate, clock.instant()));
        switch (check.state()) {
            case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> {
                return check;
            }
            default -> {
                Files.createDirectories(licenseFile.toAbsolutePath().getParent());
                Path tmp = Files.createTempFile(licenseFile.toAbsolutePath().getParent(), "license", ".tmp");
                Files.writeString(tmp, fileText, StandardCharsets.UTF_8);
                Files.move(tmp, licenseFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                loadedModified = null;
                return current();
            }
        }
    }

    /* ---- ENS_LICENSE --------------------------------------------------------- */

    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{16}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final System.Logger LOG = System.getLogger(LicenseManager.class.getName());

    /** The file text for a license given as the file, the bare token, or the file in base64. */
    static String licenseText(String value) throws LicenseException {
        String v = value.trim();
        if (v.contains("-----BEGIN ENS LICENSE-----")) {
            return v.endsWith("\n") ? v : v + "\n";
        }
        String compact = v.replaceAll("\\s+", "");
        if (TOKEN.matcher(compact).matches()) {
            return "-----BEGIN ENS LICENSE-----\n" + compact + "\n-----END ENS LICENSE-----\n";
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
            reloadIfChanged();
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
                            + ") was installed to " + licenseFile + ".";
                }
            }
        } catch (LicenseException e) {
            level = System.Logger.Level.WARNING;
            status = "ENS_LICENSE was not installed: " + e.getMessage() + ".";
        } catch (IOException | RuntimeException e) {
            level = System.Logger.Level.WARNING;
            status = "ENS_LICENSE could not be written to " + licenseFile + ": " + e.getMessage();
        }
        environmentStatus = status;
        LOG.log(level, status);
    }

    private LicensePolicy.Context context(EnsLicense l, Instant now) {
        return new LicensePolicy.Context(product, installationId(), releaseDate, highestRevision(l.id()), now);
    }

    private void reloadIfChanged() {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(licenseFile);
        } catch (NoSuchFileException e) {
            loaded = null;
            loadError = null;
            loadedModified = null;
            return;
        } catch (IOException e) {
            loaded = null;
            loadError = "The license file can't be read: " + e.getMessage();
            return;
        }
        if (modified.equals(loadedModified)) {
            return;
        }
        loadedModified = modified;
        try {
            loaded = verifier.verify(Files.readString(licenseFile, StandardCharsets.UTF_8));
            loadError = null;
        } catch (LicenseException e) {
            loaded = null;
            loadError = "The installed license file isn't valid: " + e.getMessage() + ".";
        } catch (IOException e) {
            loaded = null;
            loadError = "The license file can't be read: " + e.getMessage();
        }
    }

    /* ---- Highest revision seen, per license id ------------------------------ */

    private Path revisionsFile() {
        return stateDir.resolve("revisions.properties");
    }

    private Properties revisions() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(revisionsFile())) {
            p.load(in);
        } catch (NoSuchFileException e) {
            // none yet
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + revisionsFile() + ": " + e.getMessage(), e);
        }
        return p;
    }

    private int highestRevision(String licenseId) {
        try {
            return Integer.parseInt(revisions().getProperty(licenseId, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void remember(EnsLicense l) {
        if (l.revision() <= highestRevision(l.id())) return;
        Properties p = revisions();
        p.setProperty(l.id(), Integer.toString(l.revision()));
        try {
            Files.createDirectories(stateDir);
            Path tmp = Files.createTempFile(stateDir, "revisions", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                p.store(out, "Highest ENS license revision accepted per license id. Do not edit.");
            }
            Files.move(tmp, revisionsFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + revisionsFile() + ": " + e.getMessage(), e);
        }
    }
}
