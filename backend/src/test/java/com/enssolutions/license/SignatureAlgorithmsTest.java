// Ported from products-page licensing/java 1.2.0 (the ENS license verifier library's own test);
// the fixtures are copied into src/test/resources/ens-license-fixtures.
package com.enssolutions.license;

import static org.junit.jupiter.api.Assertions.*;

import com.enssolutions.license.LicenseVerifier.Algorithm;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ed25519 and ECDSA P-256 signatures, and what happens when this Java runtime has no Ed25519, as
 * in FIPS mode. The fixtures are signed by the site's TypeScript code with the published test keys
 * (licensing/fixtures, made by src/lib/licensing/fixtures.test.ts).
 *
 * <p>"No Ed25519" is simulated per verifier through {@link LicenseVerifier.Jca}, never by changing
 * the installed providers, so nothing leaks into other tests. The real thing is checked in CI by
 * {@link FipsCheck}, on Red Hat's OpenJDK in FIPS mode and on BouncyCastle FIPS.
 */
class SignatureAlgorithmsTest {
    static Path fixtures;
    static String ed25519Key;
    static String ed25519Id;
    static String p256Key;
    static String p256Id;
    static LicenseVerifier both;

    @BeforeAll
    static void load() throws IOException {
        fixtures = Path.of(System.getProperty("ens.license.fixtures", "src/test/resources/ens-license-fixtures"));
        String[] ed = Files.readString(fixtures.resolve("test-public-key.txt")).trim().split(" ");
        String[] ec = Files.readString(fixtures.resolve("test-public-key-p256.txt")).trim().split(" ");
        ed25519Id = ed[0];
        ed25519Key = ed[1];
        p256Id = ec[0];
        p256Key = ec[1];
        both = LicenseVerifier.trusting(ed25519Key, p256Key);
    }

    static String fixture(String name) throws IOException {
        return Files.readString(fixtures.resolve(name));
    }

    /* ---- Runtimes without Ed25519 ------------------------------------------------ */

    /** Asks the installed providers, except that {@code missing} isn't there. */
    record Without(boolean keyFactory, boolean signature) implements LicenseVerifier.Jca {
        @Override
        public KeyFactory keyFactory(String algorithm) throws NoSuchAlgorithmException {
            if (keyFactory && algorithm.startsWith("Ed")) throw new NoSuchAlgorithmException(algorithm + " KeyFactory not available");
            return KeyFactory.getInstance(algorithm);
        }

        @Override
        public Signature signature(String algorithm) throws NoSuchAlgorithmException {
            if (signature && algorithm.startsWith("Ed")) throw new NoSuchAlgorithmException(algorithm + " Signature not available");
            return Signature.getInstance(algorithm);
        }
    }

    /** Red Hat's OpenJDK in FIPS mode: SunEC keeps its Ed25519 KeyFactory, but there is no Ed25519 Signature. */
    static final LicenseVerifier.Jca RED_HAT_FIPS = new Without(false, true);
    /** BouncyCastle FIPS 2.0.x and 2.1.0 in approved-only mode, as the only provider: no Ed25519 at all. */
    static final LicenseVerifier.Jca BC_FIPS_APPROVED = new Without(true, true);

    /** An Ed25519 provider that fails when used, as a broken PKCS#11 token would. */
    static final LicenseVerifier.Jca BROKEN_ED25519 = new LicenseVerifier.Jca() {
        @Override
        public KeyFactory keyFactory(String algorithm) throws NoSuchAlgorithmException {
            return KeyFactory.getInstance(algorithm);
        }

        @Override
        public Signature signature(String algorithm) throws NoSuchAlgorithmException {
            if (!algorithm.equals("Ed25519")) return Signature.getInstance(algorithm);
            return new Signature("Ed25519") {
                @Override
                protected void engineInitVerify(PublicKey publicKey) {
                    throw new ProviderException("CKR_DEVICE_ERROR");
                }

                @Override
                protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
                    throw new InvalidKeyException();
                }

                @Override
                protected void engineUpdate(byte b) {}

                @Override
                protected void engineUpdate(byte[] b, int off, int len) {}

                @Override
                protected byte[] engineSign() {
                    return new byte[0];
                }

                @Override
                protected boolean engineVerify(byte[] sigBytes) {
                    return false;
                }

                @Override
                @Deprecated
                protected void engineSetParameter(String param, Object value) {}

                @Override
                @Deprecated
                protected Object engineGetParameter(String param) {
                    return null;
                }
            };
        }
    };

    /* ---- What existed before ECDSA keeps working ------------------------------------- */

    @Test
    void ed25519OnlyFilesVerifyAsBefore() throws Exception {
        for (String name : new String[] {"subscription.lic", "evaluation-bound.lic", "perpetual.lic"}) {
            assertEquals(ed25519Id, both.verify(fixture(name)).keyId(), name);
            assertEquals(ed25519Id, LicenseVerifier.trusting(ed25519Key).verify(fixture(name)).keyId(), name);
        }
        assertEquals(LicenseException.Reason.BAD_SIGNATURE,
                assertThrows(LicenseException.class, () -> both.verify(fixture("tampered.lic"))).reason());
    }

    @Test
    void theFirstBlockIsWhatVerifiersBeforeECDSARead() throws Exception {
        // ens-license 1.0.0: the first BEGIN..END block, which must hold exactly kid.payload.signature.
        Matcher m = ARMOR.matcher(fixture("dual-signed.lic"));
        assertTrue(m.find());
        String[] parts = m.group(1).replaceAll("\\s+", "").split("\\.", -1);
        assertEquals(3, parts.length);
        assertEquals(ed25519Id, parts[0]);
        // What an application embedding only today's Ed25519 key sees:
        EnsLicense l = LicenseVerifier.trusting(ed25519Key).verify(fixture("dual-signed.lic"));
        assertEquals(ed25519Id, l.keyId());
        assertEquals("lic_00000000000000000004", l.id());
    }

    /* ---- Dual-signed and P-256-only ------------------------------------------------- */

    @Test
    void dualSignedPrefersEd25519() throws Exception {
        EnsLicense l = both.verify(fixture("dual-signed.lic"));
        assertEquals(ed25519Id, l.keyId());
        assertEquals("lic_00000000000000000004", l.id());
        assertEquals("certalert", l.product());
        assertEquals(10_000, l.limitQuantity());
    }

    @Test
    void dualSignedFallsBackToP256WithoutEd25519() throws Exception {
        EnsLicense withEd25519 = both.verify(fixture("dual-signed.lic"));
        for (LicenseVerifier.Jca fips : new LicenseVerifier.Jca[] {RED_HAT_FIPS, BC_FIPS_APPROVED, BROKEN_ED25519}) {
            EnsLicense l = both.using(fips).verify(fixture("dual-signed.lic"));
            assertEquals(p256Id, l.keyId(), fips.toString());
            // The same license either way, short of which key vouched for it.
            assertEquals(withEd25519, new EnsLicense(l.id(), l.revision(), ed25519Id, l.issuer(), l.product(), l.edition(),
                    l.licenseeName(), l.licenseeOrganization(), l.licenseeEmail(), l.limitQuantity(), l.limitUnit(), l.features(),
                    l.support(), l.issuedAt(), l.notBefore(), l.expiresAt(), l.graceDays(), l.maintenanceUntil(), l.installationId(),
                    l.watermark()));
        }
    }

    @Test
    void keysOfAnAlgorithmTheRuntimeLacksAreStillAccepted() {
        // On BouncyCastle FIPS 2.0 even the Ed25519 KeyFactory is missing; trusting() must not fail at start-up.
        LicenseVerifier v = assertDoesNotThrow(() -> LicenseVerifier.trusting(ed25519Key, p256Key).using(BC_FIPS_APPROVED));
        assertFalse(v.isSupported(Algorithm.ED25519));
        assertTrue(v.isSupported(Algorithm.ECDSA_P256));
        assertFalse(both.using(RED_HAT_FIPS).isSupported(Algorithm.ED25519));
        assertTrue(both.isSupported(Algorithm.ED25519));
        assertEquals(Map.of(ed25519Id, Algorithm.ED25519, p256Id, Algorithm.ECDSA_P256), both.trustedKeys());
    }

    @Test
    void p256OnlyVerifies() throws Exception {
        for (LicenseVerifier v : new LicenseVerifier[] {both, both.using(RED_HAT_FIPS), both.using(BC_FIPS_APPROVED),
                LicenseVerifier.trusting(p256Key)}) {
            EnsLicense l = v.verify(fixture("p256-only.lic"));
            assertEquals(p256Id, l.keyId());
            assertEquals("mfa-portal", l.product());
            assertEquals(5_000, l.limitQuantity());
        }
    }

    @Test
    void refusesATamperedP256File() {
        for (LicenseVerifier v : new LicenseVerifier[] {both, both.using(RED_HAT_FIPS), both.using(BC_FIPS_APPROVED)}) {
            LicenseException e = assertThrows(LicenseException.class, () -> v.verify(fixture("tampered-p256.lic")));
            assertEquals(LicenseException.Reason.BAD_SIGNATURE, e.reason());
        }
    }

    @Test
    void refusesAnAlteredP256SignatureWithoutEd25519() throws Exception {
        String file = withSignature(fixture("dual-signed.lic"), 1, s -> flip(s, 20));
        LicenseException e = assertThrows(LicenseException.class, () -> both.using(RED_HAT_FIPS).verify(file));
        assertEquals(LicenseException.Reason.BAD_SIGNATURE, e.reason());
        // Not even DER any more.
        String garbage = withSignature(fixture("dual-signed.lic"), 1, s -> "AAAA" + s.substring(4));
        assertEquals(LicenseException.Reason.BAD_SIGNATURE,
                assertThrows(LicenseException.class, () -> both.using(RED_HAT_FIPS).verify(garbage)).reason());
        // With Ed25519 the file still verifies: either good signature from a trusted key will do.
        assertEquals(ed25519Id, both.verify(file).keyId());
    }

    @Test
    void anyGoodSignatureFromATrustedKeyWillDo() throws Exception {
        String file = withSignature(fixture("dual-signed.lic"), 0, s -> flip(s, 20));
        assertEquals(p256Id, both.verify(file).keyId());
        assertEquals(LicenseException.Reason.BAD_SIGNATURE,
                assertThrows(LicenseException.class, () -> LicenseVerifier.trusting(ed25519Key).verify(file)).reason());
    }

    @Test
    void anEd25519OnlyFileWithoutEd25519SaysWhatToDo() {
        for (LicenseVerifier.Jca fips : new LicenseVerifier.Jca[] {RED_HAT_FIPS, BC_FIPS_APPROVED, BROKEN_ED25519}) {
            LicenseException e = assertThrows(LicenseException.class, () -> both.using(fips).verify(fixture("subscription.lic")));
            assertEquals(LicenseException.Reason.UNSUPPORTED_ALGORITHM, e.reason());
            assertTrue(e.getMessage().contains("ECDSA P-256"), e.getMessage());
        }
    }

    @Test
    void usesOnlyTheKeysItTrusts() throws Exception {
        assertEquals(p256Id, LicenseVerifier.trusting(p256Key).verify(fixture("dual-signed.lic")).keyId());
        assertEquals(LicenseException.Reason.UNKNOWN_KEY,
                assertThrows(LicenseException.class, () -> LicenseVerifier.trusting(p256Key).verify(fixture("subscription.lic"))).reason());
        LicenseException e = assertThrows(LicenseException.class, () -> LicenseVerifier.trusting(ed25519Key).verify(fixture("p256-only.lic")));
        assertEquals(LicenseException.Reason.UNKNOWN_KEY, e.reason());
        assertTrue(e.getMessage().contains(p256Id));
    }

    @Test
    void refusesBlocksFromDifferentLicenses() throws Exception {
        LicenseException e = assertThrows(LicenseException.class,
                () -> both.verify(fixture("subscription.lic") + fixture("p256-only.lic")));
        assertEquals(LicenseException.Reason.MALFORMED, e.reason());
        assertEquals(LicenseException.Reason.MALFORMED,
                assertThrows(LicenseException.class, () -> both.verify(fixture("dual-signed.lic")
                        .replaceFirst("-----END ENS LICENSE-----", "-----END ENS LICENSE-----\n-----BEGIN ENS LICENSE-----\nnot.a-token\n-----END ENS LICENSE-----"))).reason());
    }

    @Test
    void refusesKeysOfOtherAlgorithms() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPairGenerator p384 = KeyPairGenerator.getInstance("EC");
        p384.initialize(new ECGenParameterSpec("secp384r1"));
        for (KeyPairGenerator g : new KeyPairGenerator[] {rsa, p384}) {
            String b64 = Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
            assertThrows(IllegalArgumentException.class, () -> LicenseVerifier.trusting(b64));
        }
        assertThrows(IllegalArgumentException.class, () -> LicenseVerifier.trusting("not base64!"));
    }

    @Test
    void keyIdsAreThePortals() throws Exception {
        PublicKey p256 = KeyFactory.getInstance("EC").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(p256Key)));
        assertEquals(p256Id, LicenseVerifier.keyId(p256));
        assertTrue(both.trustedKeys().containsKey(p256Id));
    }

    /* ---- The application's path, on a runtime without Ed25519 ------------------------ */

    @Test
    void anApplicationInFipsModeIsLicensedByADualSignedFile(@TempDir Path dir) throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
        LicenseVerifier fips = both.using(RED_HAT_FIPS);
        LicenseManager m = new LicenseManager("certalert", fips, dir.resolve("a.lic"), dir.resolve("a-state"), Optional.empty(), clock);
        // An Ed25519-only file can't be checked here: refused on upload, unlicensed if put in place.
        assertEquals(LicenseException.Reason.UNSUPPORTED_ALGORITHM,
                assertThrows(LicenseException.class, () -> m.install(fixture("subscription.lic"))).reason());
        Files.writeString(dir.resolve("a.lic"), fixture("subscription.lic"));
        assertEquals(LicenseState.INVALID, m.current().state());
        assertTrue(m.current().message().contains("ECDSA P-256"), m.current().message());
        // The dual-signed file is accepted.
        assertEquals(LicenseState.VALID, m.install(fixture("dual-signed.lic")).state());
        assertEquals(LicenseState.Mode.FULL, m.current().mode());

        // ENS_LICENSE as the token of each block, one per line.
        String tokens = String.join("\n", tokensOf(fixture("dual-signed.lic")));
        LicenseManager env = new LicenseManager("certalert", fips, dir.resolve("b.lic"), dir.resolve("b-state"), Optional.empty(),
                clock, Optional.of(tokens));
        assertEquals(LicenseState.VALID, env.current().state());
        assertTrue(env.environmentLicenseStatus().orElseThrow().contains("was installed"));
    }

    /* ---- Helpers ------------------------------------------------------------------- */

    static final Pattern ARMOR = Pattern.compile("-----BEGIN ENS LICENSE-----(.*?)-----END ENS LICENSE-----", Pattern.DOTALL);

    static java.util.List<String> tokensOf(String file) {
        return ARMOR.matcher(file).results().map(r -> r.group(1).replaceAll("\\s+", "")).toList();
    }

    /** The file with the signature of block {@code index} changed by {@code change}. */
    static String withSignature(String file, int index, java.util.function.UnaryOperator<String> change) {
        Matcher m = ARMOR.matcher(file);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (m.find()) {
            String token = m.group(1).replaceAll("\\s+", "");
            if (i++ == index) {
                int dot = token.lastIndexOf('.');
                token = token.substring(0, dot + 1) + change.apply(token.substring(dot + 1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement("-----BEGIN ENS LICENSE-----\n" + token + "\n-----END ENS LICENSE-----"));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String flip(String s, int at) {
        char c = s.charAt(at);
        return s.substring(0, at) + (c == 'A' ? 'B' : 'A') + s.substring(at + 1);
    }
}
