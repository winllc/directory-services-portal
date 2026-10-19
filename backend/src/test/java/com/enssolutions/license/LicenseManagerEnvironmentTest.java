// Ported from products-page licensing/java 1.2.0 (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ENS_LICENSE: the license given as an environment variable. Licenses are made from the
 * subscription fixture, re-signed with the published test key, so their id and revision can vary.
 */
class LicenseManagerEnvironmentTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    static String keyId;
    static LicenseVerifier verifier;
    static String payload;
    static PrivateKey testKey;

    @TempDir Path dir;

    @BeforeAll
    static void load() throws Exception {
        Path fixtures = Path.of(System.getProperty("ens.license.fixtures", "src/test/resources/ens-license-fixtures"));
        String[] key = Files.readString(fixtures.resolve("test-public-key.txt")).trim().split(" ");
        keyId = key[0];
        verifier = LicenseVerifier.trusting(key[1]);
        Matcher m = Pattern.compile("-----BEGIN ENS LICENSE-----(.*?)-----END ENS LICENSE-----", Pattern.DOTALL)
                .matcher(Files.readString(fixtures.resolve("subscription.lic")));
        assertTrue(m.find());
        String token = m.group(1).replaceAll("\\s+", "");
        payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        // The published test key: PKCS#8 for the Ed25519 seed 0x07 x 32.
        byte[] der = HexFormat.of().parseHex("302e020100300506032b657004220420" + "07".repeat(32));
        testKey = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** The subscription fixture with another id, revision or product, signed with the test key. */
    static String license(String id, int revision, String product) throws Exception {
        String json = payload
                .replace("\"lic_00000000000000000001\"", "\"" + id + "\"")
                .replace("\"revision\":3", "\"revision\":" + revision)
                .replace("\"product\":\"certalert\"", "\"product\":\"" + product + "\"");
        String body = keyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(testKey);
        sig.update(body.getBytes(StandardCharsets.US_ASCII));
        String token = body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        return "-----BEGIN ENS LICENSE-----\n" + token + "\n-----END ENS LICENSE-----\n";
    }

    static String token(String file) {
        return file.replace("-----BEGIN ENS LICENSE-----", "").replace("-----END ENS LICENSE-----", "").trim();
    }

    LicenseManager manager(String environment) {
        return new LicenseManager("certalert", verifier, dir.resolve("license.lic"), dir.resolve("state"),
                Optional.empty(), CLOCK, Optional.ofNullable(environment));
    }

    String installedId() throws Exception {
        return verifier.verify(Files.readString(dir.resolve("license.lic"))).id();
    }

    int installedRevision() throws Exception {
        return verifier.verify(Files.readString(dir.resolve("license.lic"))).revision();
    }

    @Test
    void installsWhenNoLicenseIsInstalled() throws Exception {
        LicenseManager m = manager(license("lic_env", 1, "certalert"));
        assertEquals(LicenseState.Mode.FULL, m.current().state().mode());
        assertEquals("lic_env", installedId());
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("was installed"));
    }

    @Test
    void acceptsTheBareTokenAndBase64() throws Exception {
        String file = license("lic_env", 1, "certalert");
        assertEquals(LicenseState.Mode.FULL, manager(token(file)).current().state().mode());

        Files.delete(dir.resolve("license.lic"));
        String b64 = Base64.getEncoder().encodeToString(("# a comment line\n" + file).getBytes(StandardCharsets.UTF_8));
        assertEquals(LicenseState.Mode.FULL, manager(b64).current().state().mode());

        Files.delete(dir.resolve("license.lic"));
        String wrapped = token(file).replaceAll("(.{64})", "$1\n");
        assertEquals(LicenseState.Mode.FULL, manager(wrapped).current().state().mode());
    }

    @Test
    void replacesAnOlderRevisionOfTheSameLicense() throws Exception {
        Files.writeString(dir.resolve("license.lic"), license("lic_env", 1, "certalert"));
        manager(null).current();
        manager(license("lic_env", 2, "certalert")).current();
        assertEquals(2, installedRevision());
    }

    @Test
    void keepsTheInstalledLicenseWhenTheVariableIsOlderOrTheSame() throws Exception {
        Files.writeString(dir.resolve("license.lic"), license("lic_env", 3, "certalert"));
        LicenseManager m = manager(license("lic_env", 2, "certalert"));
        assertEquals(LicenseState.Mode.FULL, m.current().state().mode());
        assertEquals(3, installedRevision());
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("already installed"));
    }

    @Test
    void keepsADifferentLicenseInstalledAnotherWay() throws Exception {
        Files.writeString(dir.resolve("license.lic"), license("lic_uploaded", 1, "certalert"));
        LicenseManager m = manager(license("lic_env", 5, "certalert"));
        m.current();
        assertEquals("lic_uploaded", installedId());
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("lic_uploaded is already installed"));
    }

    @Test
    void replacesAnInstalledFileThatIsNotUsable() throws Exception {
        Files.writeString(dir.resolve("license.lic"), "not a license");
        LicenseManager m = manager(license("lic_env", 1, "certalert"));
        assertEquals(LicenseState.Mode.FULL, m.current().state().mode());
        assertEquals("lic_env", installedId());
    }

    @Test
    void refusesALicenseForAnotherProductAndLeavesTheInstalledOneAlone() throws Exception {
        LicenseManager m = manager(license("lic_env", 1, "readyroom"));
        assertEquals(LicenseState.Mode.UNLICENSED, m.current().state().mode());
        assertFalse(Files.exists(dir.resolve("license.lic")));
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("was refused"));
    }

    @Test
    void refusesSomethingThatIsNotALicense() throws IOException {
        LicenseManager m = manager("hello");
        assertEquals(LicenseState.Mode.UNLICENSED, m.current().state().mode());
        assertTrue(m.environmentLicenseStatus().orElseThrow().contains("not a license"));
        assertFalse(Files.exists(dir.resolve("license.lic")));
    }

    @Test
    void refusesATamperedLicense() throws Exception {
        String file = license("lic_env", 1, "certalert");
        String tampered = file.replace(keyId + ".", keyId + ".X");
        LicenseManager m = manager(tampered);
        assertEquals(LicenseState.Mode.UNLICENSED, m.current().state().mode());
        assertTrue(m.environmentLicenseStatus().orElseThrow().startsWith("ENS_LICENSE was not installed"));
    }

    @Test
    void anUploadAfterStartIsNotUndoneByTheVariable() throws Exception {
        LicenseManager m = manager(license("lic_env", 1, "certalert"));
        m.current();
        m.install(license("lic_uploaded", 1, "certalert"));
        m.current();
        assertEquals("lic_uploaded", installedId());
    }

    @Test
    void anEmptyVariableIsIgnored() {
        LicenseManager m = manager("   ");
        assertEquals(LicenseState.Mode.UNLICENSED, m.current().state().mode());
        assertTrue(m.environmentLicenseStatus().isEmpty());
    }
}
