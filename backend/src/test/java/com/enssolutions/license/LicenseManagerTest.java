// Ported from products-page licensing/java 1.2.0 (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Installing over a license in force, and a clock set back. */
class LicenseManagerTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path dir;

    LicenseManager manager(String at) throws Exception {
        return manager(at, null);
    }

    LicenseManager manager(String at, String environment) throws Exception {
        return new LicenseManager("certalert", TestLicenses.verifier(), dir.resolve("certalert.lic"), dir.resolve("state"),
                Optional.empty(), Clock.fixed(Instant.parse(at), ZoneOffset.UTC), Optional.ofNullable(environment));
    }

    String installedId() throws Exception {
        return TestLicenses.verifier().verify(Files.readString(dir.resolve("certalert.lic"))).id();
    }

    /* ---- Install ------------------------------------------------------------------ */

    @Test
    void aLicenseThatHasNotStartedIsNotInstalledOverOneInForce() throws Exception {
        LicenseManager m = manager("2026-11-01T00:00:00Z");
        assertEquals(LicenseState.VALID, m.install(TestLicenses.license("lic_now", 1, "2026-10-01T00:00:00Z", "2027-10-01T00:00:00Z")).state());

        LicenseCheck early = m.install(TestLicenses.license("lic_next", 1, "2027-10-01T00:00:00Z", "2028-10-01T00:00:00Z"));
        assertEquals(LicenseState.NOT_IN_EFFECT, early.state());
        assertEquals("This license starts on 2027-10-01; the license installed now is still in force. "
                + "Upload this one on or after that day.", early.message());
        assertEquals("lic_now", installedId());
        assertEquals(LicenseState.VALID, m.current().state());
    }

    @Test
    void anExpiredLicenseIsNotInstalledOverOneInForce() throws Exception {
        LicenseManager m = manager("2026-11-01T00:00:00Z");
        m.install(TestLicenses.license("lic_now", 1, "2026-10-01T00:00:00Z", "2027-10-01T00:00:00Z"));

        LicenseCheck old = m.install(TestLicenses.license("lic_old", 1, "2024-10-01T00:00:00Z", "2025-10-01T00:00:00Z"));
        assertEquals(LicenseState.NOT_IN_EFFECT, old.state());
        assertTrue(old.message().startsWith("This license expired on 2025-10-01; the license installed now is still in force."), old.message());
        assertEquals("lic_now", installedId());
        assertEquals(LicenseState.VALID, m.current().state());
    }

    @Test
    void withNoLicenseInForceEitherIsInstalledAsBefore() throws Exception {
        LicenseManager m = manager("2026-11-01T00:00:00Z");
        assertEquals(LicenseState.NOT_YET_VALID,
                m.install(TestLicenses.license("lic_next", 1, "2027-10-01T00:00:00Z", "2028-10-01T00:00:00Z")).state());
        assertEquals("lic_next", installedId());

        // An expired license (read-only) is no better than one not yet valid: replacing it is allowed.
        assertEquals(LicenseState.EXPIRED,
                m.install(TestLicenses.license("lic_old", 1, "2024-10-01T00:00:00Z", "2025-10-01T00:00:00Z")).state());
        assertEquals("lic_old", installedId());
        assertEquals(LicenseState.VALID,
                m.install(TestLicenses.license("lic_now", 1, "2026-10-01T00:00:00Z", "2027-10-01T00:00:00Z")).state());
    }

    @Test
    void theVariableIsRefusedTooWhenItHasNotStarted() throws Exception {
        manager("2026-11-01T00:00:00Z").install(TestLicenses.license("lic_env", 1, "2026-10-01T00:00:00Z", "2027-10-01T00:00:00Z"));
        LicenseManager m = manager("2026-11-01T00:00:00Z",
                TestLicenses.license("lic_env", 2, "2027-10-01T00:00:00Z", "2028-10-01T00:00:00Z"));
        assertEquals(LicenseState.VALID, m.current().state());
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("was refused: This license starts on 2027-10-01"),
                m.environmentLicenseStatus().orElseThrow());
        assertEquals(1, m.current().license().orElseThrow().revision());
    }

    /* ---- Clock set back -------------------------------------------------------------- */

    // The license runs until 2027-10-01, with 30 days' grace: GRACE until 2027-10-31, EXPIRED after.
    static final String TERM_START = "2026-10-01T00:00:00Z";
    static final String TERM_END = "2027-10-01T00:00:00Z";

    @Test
    void settingTheClockBackDoesNotBringAnExpiredLicenseBack() throws Exception {
        manager("2026-11-01T00:00:00Z").install(TestLicenses.license("lic_a", 1, TERM_START, TERM_END));
        assertEquals(LicenseState.EXPIRED, manager("2027-12-01T00:00:00Z").current().state());
        assertEquals("2027-12-01T00:00:00Z", Files.readString(dir.resolve("state/latest-time-seen")).trim());

        LicenseCheck back = manager("2027-06-01T00:00:00Z").current();
        assertEquals(LicenseState.EXPIRED, back.state());
        assertTrue(back.message().contains("clock is behind a time this installation has already seen, so the license is checked as of 2027-11-30"),
                back.message());
        // and the time recorded is not lowered
        assertEquals("2027-12-01T00:00:00Z", Files.readString(dir.resolve("state/latest-time-seen")).trim());
        // An upload is checked as of that time too: a renewal that started by then is in force.
        assertEquals(LicenseState.VALID, manager("2027-06-01T00:00:00Z")
                .install(TestLicenses.license("lic_b", 1, "2027-11-15T00:00:00Z", "2028-11-15T00:00:00Z")).state());
    }

    @Test
    void aClockCorrectedByMinutesChangesNothing() throws Exception {
        manager("2026-11-01T00:00:00Z").install(TestLicenses.license("lic_a", 1, TERM_START, TERM_END));
        // Grace ends at 2027-10-31T00:00Z. A clock that ran 7 minutes fast past that, then corrected:
        assertEquals(LicenseState.EXPIRED, manager("2027-10-31T00:05:00Z").current().state());
        LicenseCheck corrected = manager("2027-10-30T23:58:00Z").current();
        assertEquals(LicenseState.GRACE, corrected.state());
        assertFalse(corrected.message().contains("clock"), corrected.message());
    }

    @Test
    void withoutARecordedTimeTheClockAloneCounts() throws Exception {
        manager("2026-11-01T00:00:00Z").install(TestLicenses.license("lic_a", 1, TERM_START, TERM_END));
        assertEquals(LicenseState.EXPIRED, manager("2027-12-01T00:00:00Z").current().state());
        // State from a release that kept no time, or a file that can't be read: as before.
        Files.writeString(dir.resolve("state/latest-time-seen"), "not a time\n");
        assertEquals(LicenseState.VALID, manager("2027-06-01T00:00:00Z").current().state());
        Files.delete(dir.resolve("state/latest-time-seen"));
        assertEquals(LicenseState.VALID, manager("2027-06-01T00:00:00Z").current().state());
    }
}
