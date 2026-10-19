// Ported from products-page licensing/java 1.2.0 (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The file store reads and writes state directories exactly as ens-license 1.1.0 and earlier did. */
class FileLicenseStoreTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path dir;

    LicenseManager manager() throws Exception {
        return new LicenseManager("certalert", TestLicenses.verifier(), dir.resolve("certalert.lic"), dir.resolve("state"),
                Optional.empty(), CLOCK);
    }

    @Test
    void readsAStateDirectoryWrittenBy110() throws Exception {
        // Byte for byte what 1.1.0 left behind: the id and a newline, and Properties.store output.
        Path state = Files.createDirectories(dir.resolve("state"));
        Files.write(state.resolve("installation-id"), "CA-7F3K-29QD\n".getBytes(StandardCharsets.UTF_8));
        Files.write(state.resolve("revisions.properties"), ("#Highest ENS license revision accepted per license id. Do not edit.\n"
                + "#Sat Oct 17 12:00:00 UTC 2026\nlic_00000000000000000001=3\nlic_other=7\n").getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(dir.resolve("certalert.lic"), TestLicenses.license("lic_00000000000000000001", 3, "CA-7F3K-29QD"));

        LicenseManager m = manager();
        assertEquals("CA-7F3K-29QD", m.installationId());
        assertEquals(LicenseState.VALID, m.current().state());
        assertEquals(LicenseState.SUPERSEDED, m.install(TestLicenses.license("lic_00000000000000000001", 2, null)).state());
        assertEquals(Map.of("lic_00000000000000000001", 3, "lic_other", 7), new FileLicenseStore(dir.resolve("certalert.lic"), state).revisions());
        assertEquals(dir.resolve("certalert.lic"), m.licenseFile());
        assertEquals(dir.resolve("certalert.lic").toString(), m.licenseLocation());
    }

    @Test
    void writesTheSameFilesAs110() throws Exception {
        LicenseManager m = manager();
        String id = m.installationId();
        assertEquals(id + "\n", Files.readString(dir.resolve("state/installation-id")));

        String text = TestLicenses.license("lic_00000000000000000001", 4, null);
        assertEquals(LicenseState.VALID, m.install(text).state());
        assertEquals(text, Files.readString(dir.resolve("certalert.lic")));
        List<String> lines = Files.readAllLines(dir.resolve("state/revisions.properties"), StandardCharsets.ISO_8859_1);
        assertEquals(3, lines.size(), lines.toString());
        assertEquals("#Highest ENS license revision accepted per license id. Do not edit.", lines.get(0));
        assertTrue(lines.get(1).startsWith("#"));
        assertEquals("lic_00000000000000000001=4", lines.get(2));
        try (var files = Files.list(dir.resolve("state"))) {
            assertEquals(List.of("installation-id", "revisions.properties"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void raisingNeverLowers() throws Exception {
        FileLicenseStore store = new FileLicenseStore(dir.resolve("x.lic"), dir.resolve("state"));
        assertEquals(0, store.highestRevision("a"));
        assertEquals(5, store.raiseRevision("a", 5));
        assertEquals(5, store.raiseRevision("a", 2));
        assertEquals(5, store.highestRevision("a"));
        assertEquals("CA-AAAA-AAAA", store.installationId("CA-AAAA-AAAA"));
        assertEquals("CA-AAAA-AAAA", store.installationId("CA-BBBB-BBBB"));
        assertEquals("CA-AAAA-AAAA", InstallationId.loadOrCreate(dir.resolve("state"), "certalert"));
    }

    @Test
    void keepsTheInputCap() throws Exception {
        Files.writeString(dir.resolve("certalert.lic"), "x".repeat(4 * LicenseVerifier.MAX_FILE_CHARS + 1));
        LicenseCheck c = manager().current();
        assertEquals(LicenseState.INVALID, c.state());
        assertTrue(c.message().contains("larger than"), c.message());
    }
}
