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
import java.util.Optional;
import java.util.Properties;

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

    private FileTime loadedModified;
    private EnsLicense loaded;
    private String loadError;
    private String installationId;

    public LicenseManager(String product, LicenseVerifier verifier, Path licenseFile, Path stateDir,
                          Optional<Instant> releaseDate, Clock clock) {
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
        return new LicenseManager(product, verifier, Path.of(file), Path.of(state), releaseDate, Clock.systemUTC());
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

    /** The license as it stands now. */
    public synchronized LicenseCheck current() {
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
