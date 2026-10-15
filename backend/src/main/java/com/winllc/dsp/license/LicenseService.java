package com.winllc.dsp.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which licensed features this deployment may use.
 *
 * <p>Without a valid license the application still runs, with its core features only. The
 * license file is re-read when it changes (checked at most once every {@link #RECHECK}), so a
 * renewed license takes effect without a restart; expiry is checked on every question.
 */
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    static final Duration RECHECK = Duration.ofSeconds(30);

    public enum State { LICENSED, MISSING, INVALID, EXPIRED }

    /** What the application and its pages are told about the license. */
    public record Status(
            State state,
            String product,
            String licensee,
            String licenseId,
            LocalDate expires,
            Set<String> features,
            String problem,
            String file) {

        public boolean isLicensed() {
            return state == State.LICENSED;
        }
    }

    private final LicenseVerifier verifier;
    private final Path file;
    private final String product;
    private final Clock clock;

    private Instant lastCheck = Instant.MIN;
    private FileTime lastModified;
    private License license;
    private String problem;

    public LicenseService(LicenseVerifier verifier, Path file, String product, Clock clock) {
        this.verifier = verifier;
        this.file = file;
        this.product = product;
        this.clock = clock;
        refresh(true);
        log.info("License: {}", describe(status()));
    }

    /** True when a current license for this product covers the feature. */
    public synchronized boolean isEnabled(String feature) {
        return status().isLicensed() && license.covers(feature);
    }

    public synchronized Status status() {
        refresh(false);
        if (license == null) {
            State state = problem == null ? State.MISSING : State.INVALID;
            return new Status(state, product, null, null, null, Set.of(), problem, file.toString());
        }
        boolean expired = license.isExpired(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        return new Status(
                expired ? State.EXPIRED : State.LICENSED,
                product,
                license.licensee(),
                license.licenseId(),
                license.expires(),
                expired ? Set.of() : license.features(),
                expired ? "the license expired on " + license.expires() : null,
                file.toString());
    }

    private synchronized void refresh(boolean force) {
        Instant now = clock.instant();
        if (!force && now.isBefore(lastCheck.plus(RECHECK))) {
            return;
        }
        lastCheck = now;
        FileTime modified;
        try {
            modified = Files.getLastModifiedTime(file);
        } catch (NoSuchFileException e) {
            modified = null;
        } catch (IOException e) {
            modified = FileTime.from(now);
        }
        if (!force && Objects.equals(modified, lastModified)) {
            return;
        }
        lastModified = modified;
        License previous = license;
        if (modified == null) {
            license = null;
            problem = null;
        } else {
            try {
                license = verifier.verify(Files.readString(file, StandardCharsets.UTF_8));
                problem = null;
            } catch (LicenseVerifier.InvalidLicenseException e) {
                license = null;
                problem = e.getMessage();
            } catch (IOException e) {
                license = null;
                problem = "the license file could not be read: " + e.getMessage();
            }
        }
        if (!force && !Objects.equals(previous, license)) {
            log.info("License file changed. License: {}", describe(status()));
        }
    }

    private static String describe(Status s) {
        return switch (s.state()) {
            case LICENSED -> "licensed to " + s.licensee() + " (" + s.licenseId() + "), features "
                    + s.features() + ", expires " + (s.expires() == null ? "never" : s.expires());
            case MISSING -> "no license file at " + s.file() + "; running with core features only";
            case INVALID -> "the license file at " + s.file() + " is not valid (" + s.problem()
                    + "); running with core features only";
            case EXPIRED -> s.problem() + "; running with core features only";
        };
    }
}
