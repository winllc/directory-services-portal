// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * License state on a local volume, laid out exactly as every release before 1.2.0 kept it, so
 * existing installations carry on unchanged:
 *
 * <ul>
 *   <li>the license file ({@code ENS_LICENSE_FILE}, default {@code /etc/ens/<product>.lic}),
 *       written whole and moved into place;</li>
 *   <li>{@code <state dir>/installation-id}: the id and a newline;</li>
 *   <li>{@code <state dir>/revisions.properties}: {@code <license id>=<highest revision>}, as
 *       {@link Properties#store} writes it.</li>
 * </ul>
 *
 * <p>For one replica (or one process) at a time. Changes to the license file are noticed by its
 * modification time on every check.
 */
public final class FileLicenseStore implements LicenseStore {
    static final String INSTALLATION_ID = "installation-id";
    static final String REVISIONS = "revisions.properties";

    private final Path licenseFile;
    private final Path stateDir;

    public FileLicenseStore(Path licenseFile, Path stateDir) {
        this.licenseFile = licenseFile;
        this.stateDir = stateDir;
    }

    public Path licenseFile() {
        return licenseFile;
    }

    public Path stateDir() {
        return stateDir;
    }

    @Override
    public String location() {
        return licenseFile.toString();
    }

    /* ---- License --------------------------------------------------------------- */

    @Override
    public Optional<Object> licenseVersion() throws IOException {
        try {
            return Optional.of(Files.getLastModifiedTime(licenseFile));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<StoredLicense> readLicense() throws IOException, LicenseException {
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(licenseFile);
            // Bytes, not characters: a license is ASCII apart from a name or two in its summary lines.
            if (Files.size(licenseFile) > 4L * LicenseVerifier.MAX_FILE_CHARS) {
                throw new LicenseException(LicenseException.Reason.MALFORMED,
                        "Not an ENS license: larger than " + 4 * LicenseVerifier.MAX_FILE_CHARS + " bytes");
            }
            return Optional.of(new StoredLicense(Files.readString(licenseFile, StandardCharsets.UTF_8), modified));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void writeLicense(String text) throws IOException {
        Path dir = licenseFile.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "license", ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, licenseFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /* ---- Installation id ------------------------------------------------------ */

    /** The installation id in the state directory, if one has been made. */
    public Optional<String> storedInstallationId() throws IOException {
        try {
            String id = Files.readString(stateDir.resolve(INSTALLATION_ID), StandardCharsets.UTF_8).trim();
            return id.isEmpty() ? Optional.empty() : Optional.of(id);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized String installationId(String candidate) throws IOException {
        Optional<String> existing = storedInstallationId();
        if (existing.isPresent()) return existing.get();
        Files.createDirectories(stateDir);
        Path tmp = Files.createTempFile(stateDir, INSTALLATION_ID, ".tmp");
        Files.writeString(tmp, candidate + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, stateDir.resolve(INSTALLATION_ID), StandardCopyOption.ATOMIC_MOVE);
        return candidate;
    }

    /* ---- Highest revision seen, per license id -------------------------------- */

    private Path revisionsFile() {
        return stateDir.resolve(REVISIONS);
    }

    private Properties loadRevisions() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(revisionsFile())) {
            p.load(in);
        } catch (NoSuchFileException e) {
            // none yet
        }
        return p;
    }

    /** Every license id in {@code revisions.properties} with its highest revision. */
    public Map<String, Integer> revisions() throws IOException {
        Map<String, Integer> all = new TreeMap<>();
        Properties p = loadRevisions();
        for (String id : p.stringPropertyNames()) {
            try {
                all.put(id, Integer.parseInt(p.getProperty(id).trim()));
            } catch (NumberFormatException e) {
                // an unreadable entry counts as 0, as it always has
            }
        }
        return all;
    }

    @Override
    public int highestRevision(String licenseId) throws IOException {
        try {
            return Integer.parseInt(loadRevisions().getProperty(licenseId, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public synchronized int raiseRevision(String licenseId, int revision) throws IOException {
        Properties p = loadRevisions();
        int current;
        try {
            current = Integer.parseInt(p.getProperty(licenseId, "0"));
        } catch (NumberFormatException e) {
            current = 0;
        }
        if (revision <= current) return current;
        p.setProperty(licenseId, Integer.toString(revision));
        Files.createDirectories(stateDir);
        Path tmp = Files.createTempFile(stateDir, "revisions", ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Highest ENS license revision accepted per license id. Do not edit.");
        }
        Files.move(tmp, revisionsFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return revision;
    }

    @Override
    public String toString() {
        return "FileLicenseStore[" + licenseFile + ", " + stateDir + "]";
    }
}
