// Ported from products-page licensing/java (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs against license files signed by the site's TypeScript code
 * (licensing/fixtures, made by src/lib/licensing/fixtures.test.ts), so a
 * pass here means the two implementations agree on the format.
 */
class LicenseVerifierTest {
    static Path fixtures;
    static String keyId;
    static LicenseVerifier verifier;

    @BeforeAll
    static void load() throws IOException {
        fixtures = Path.of(System.getProperty("ens.license.fixtures", "src/test/resources/ens-license-fixtures"));
        String[] key = Files.readString(fixtures.resolve("test-public-key.txt")).trim().split(" ");
        keyId = key[0];
        verifier = LicenseVerifier.trusting(key[1]);
    }

    static String fixture(String name) throws IOException {
        return Files.readString(fixtures.resolve(name));
    }

    static LicensePolicy.Context at(String product, String instant) {
        return new LicensePolicy.Context(product, "CA-0000-0000", Optional.empty(), 0, Instant.parse(instant));
    }

    @Test
    void readsWhatTheSiteSigned() throws Exception {
        EnsLicense l = verifier.verify(fixture("subscription.lic"));
        assertEquals("lic_00000000000000000001", l.id());
        assertEquals(3, l.revision());
        assertEquals(keyId, l.keyId());
        assertEquals("certalert", l.product());
        assertEquals("subscription", l.edition());
        assertEquals(10_000, l.limitQuantity());
        assertEquals("directory users", l.limitUnit());
        assertEquals("mission", l.support());
        assertEquals(30, l.graceDays());
        assertEquals(Instant.parse("2027-10-14T00:00:00Z"), l.expiresAt().orElseThrow());
        assertTrue(l.features().contains("certalert.revocation-checks"));
        assertFalse(l.features().contains("certalert.multi-directory"));
        assertEquals("Pat Rivera, Example Agency", l.licensee());
    }

    @Test
    void readsUnicodeAndEscapes() throws Exception {
        EnsLicense l = verifier.verify(fixture("evaluation-bound.lic"));
        assertEquals("Agência Ñandú \"Test\"", l.licenseeOrganization());
        assertEquals(Optional.of("RR-TEST-0001"), l.installationId());
        assertTrue(l.watermark());
        assertEquals(500, l.limitQuantity());
    }

    @Test
    void rejectsAnEditedFile() {
        LicenseException e = assertThrows(LicenseException.class, () -> verifier.verify(fixture("tampered.lic")));
        assertEquals(LicenseException.Reason.BAD_SIGNATURE, e.reason());
    }

    @Test
    void rejectsKeysItDoesNotTrust() {
        LicenseVerifier other = LicenseVerifier.trusting("MCowBQYDK2VwAyEA5B4YgB0lvRVU4TuopqRS74fFvpj5LMaqFne3YzgyX64=");
        LicenseException e = assertThrows(LicenseException.class, () -> other.verify(fixture("subscription.lic")));
        assertEquals(LicenseException.Reason.UNKNOWN_KEY, e.reason());
    }

    @Test
    void rejectsJunk() {
        assertEquals(LicenseException.Reason.MALFORMED,
                assertThrows(LicenseException.class, () -> verifier.verify("hello")).reason());
    }

    @Test
    void movesFromValidToGraceToReadOnly() throws Exception {
        EnsLicense l = verifier.verify(fixture("subscription.lic"));
        LicenseCheck valid = LicensePolicy.evaluate(l, at("certalert", "2027-01-01T00:00:00Z"));
        assertEquals(LicenseState.VALID, valid.state());
        assertTrue(valid.isFeatureEnabled("revocation-checks"));
        assertTrue(valid.isFeatureEnabled("certalert.metrics"));
        assertFalse(valid.isFeatureEnabled("multi-directory"));
        assertFalse(valid.showRenewalWarning());

        assertTrue(LicensePolicy.evaluate(l, at("certalert", "2027-10-01T00:00:00Z")).showRenewalWarning());

        LicenseCheck grace = LicensePolicy.evaluate(l, at("certalert", "2027-10-20T00:00:00Z"));
        assertEquals(LicenseState.GRACE, grace.state());
        assertTrue(grace.isFeatureEnabled("metrics"));
        assertTrue(grace.showRenewalWarning());
        assertEquals(Optional.of(24L), grace.daysLeft());

        LicenseCheck expired = LicensePolicy.evaluate(l, at("certalert", "2027-11-14T00:00:00Z"));
        assertEquals(LicenseState.EXPIRED, expired.state());
        assertEquals(LicenseState.Mode.READ_ONLY, expired.mode());
        assertFalse(expired.isFeatureEnabled("metrics"));
    }

    @Test
    void refusesOtherProductsAndInstallations() throws Exception {
        EnsLicense sub = verifier.verify(fixture("subscription.lic"));
        assertEquals(LicenseState.WRONG_PRODUCT, LicensePolicy.evaluate(sub, at("readyroom", "2027-01-01T00:00:00Z")).state());

        EnsLicense bound = verifier.verify(fixture("evaluation-bound.lic"));
        LicensePolicy.Context here = new LicensePolicy.Context("readyroom", "rr-test-0001", Optional.empty(), 0, Instant.parse("2026-10-20T00:00:00Z"));
        LicensePolicy.Context elsewhere = new LicensePolicy.Context("readyroom", "RR-OTHER-0002", Optional.empty(), 0, Instant.parse("2026-10-20T00:00:00Z"));
        assertEquals(LicenseState.VALID, LicensePolicy.evaluate(bound, here).state());
        assertTrue(LicensePolicy.evaluate(bound, here).showEvaluationBanner());
        assertEquals(LicenseState.WRONG_INSTALLATION, LicensePolicy.evaluate(bound, elsewhere).state());
    }

    @Test
    void refusesAnOlderRevisionOnceANewerOneWasSeen() throws Exception {
        EnsLicense l = verifier.verify(fixture("subscription.lic")); // revision 3
        Instant now = Instant.parse("2027-01-01T00:00:00Z");
        assertEquals(LicenseState.VALID, LicensePolicy.evaluate(l, new LicensePolicy.Context("certalert", "CA-0000-0000", Optional.empty(), 3, now)).state());
        assertEquals(LicenseState.SUPERSEDED, LicensePolicy.evaluate(l, new LicensePolicy.Context("certalert", "CA-0000-0000", Optional.empty(), 4, now)).state());
    }

    @Test
    void perpetualCoversReleasesUpToMaintenance() throws Exception {
        EnsLicense l = verifier.verify(fixture("perpetual.lic"));
        assertTrue(l.expiresAt().isEmpty());
        Instant later = Instant.parse("2035-01-01T00:00:00Z");
        LicensePolicy.Context oldRelease = new LicensePolicy.Context("directory-portal", "DP-0000-0000", Optional.of(Instant.parse("2027-06-01T00:00:00Z")), 0, later);
        LicensePolicy.Context newRelease = new LicensePolicy.Context("directory-portal", "DP-0000-0000", Optional.of(Instant.parse("2028-06-01T00:00:00Z")), 0, later);
        assertEquals(LicenseState.VALID, LicensePolicy.evaluate(l, oldRelease).state());
        assertEquals(LicenseState.RELEASE_NOT_COVERED, LicensePolicy.evaluate(l, newRelease).state());
    }

    @Test
    void warnsThenStopsWhenOverTheLimit() throws Exception {
        LicenseCheck c = LicensePolicy.evaluate(verifier.verify(fixture("subscription.lic")), at("certalert", "2027-01-01T00:00:00Z"));
        assertEquals(LicenseCheck.Usage.WITHIN, c.usage(10_000));
        assertEquals(LicenseCheck.Usage.WARNING, c.usage(11_000));
        assertEquals(LicenseCheck.Usage.EXCEEDED, c.usage(11_001));
        assertTrue(c.mayAdd(10_999));
        assertFalse(c.mayAdd(11_000));
    }

    @Test
    void managerInstallsAndRefusesToGoBackARevision(@TempDir Path dir) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        LicenseManager m = new LicenseManager("certalert", verifier, dir.resolve("certalert.lic"), dir.resolve("state"), Optional.empty(), clock);

        assertEquals(LicenseState.MISSING, m.current().state());
        assertEquals(LicenseState.Mode.UNLICENSED, m.current().mode());
        assertTrue(m.installationId().matches("CA-[0-9A-Z]{4}-[0-9A-Z]{4}"));
        assertEquals(m.installationId(), new LicenseManager("certalert", verifier, dir.resolve("certalert.lic"), dir.resolve("state"), Optional.empty(), clock).installationId());

        assertEquals(LicenseState.VALID, m.install(fixture("subscription.lic")).state());
        assertEquals(LicenseState.VALID, m.current().state());
        assertEquals("3", Files.readAllLines(dir.resolve("state/revisions.properties")).stream()
                .filter(s -> s.startsWith("lic_00000000000000000001=")).findFirst().orElseThrow().split("=")[1]);

        // A wrong-product file is refused and the installed one stays.
        assertEquals(LicenseState.WRONG_PRODUCT, m.install(fixture("perpetual.lic")).state());
        assertEquals(LicenseState.VALID, m.current().state());

        // An edited file on disk is noticed.
        Files.writeString(dir.resolve("certalert.lic"), fixture("tampered.lic"));
        Files.setLastModifiedTime(dir.resolve("certalert.lic"), java.nio.file.attribute.FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        assertEquals(LicenseState.INVALID, m.current().state());
    }

    @Test
    void installationIdsAreUnambiguous() {
        String id = InstallationId.generate("readyroom", new java.security.SecureRandom());
        assertTrue(id.matches("RR-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}"), id);
    }
}
