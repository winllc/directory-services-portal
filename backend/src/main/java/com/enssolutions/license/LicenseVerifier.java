// Copied unchanged from products-page licensing/java 1.2.0 (the ENS license verifier library).
package com.enssolutions.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks that a license file was signed by ENS Solutions and returns what it
 * says. Uses the JDK's own Ed25519 and ECDSA; no network, no dependencies.
 *
 * <p>The file format, in short (the full description is in the site's
 * src/lib/licensing/license.ts):
 * <pre>
 * # readable summary lines, ignored
 * -----BEGIN ENS LICENSE-----
 * &lt;key id&gt;.&lt;payload base64url&gt;.&lt;signature base64url&gt;
 * -----END ENS LICENSE-----
 * </pre>
 * The signature is over the ASCII bytes "&lt;key id&gt;.&lt;payload&gt;". The trusted
 * key the id names decides the algorithm, never the file: Ed25519, or ECDSA
 * P-256 with SHA-256 (DER-encoded, as {@code SHA256withECDSA} produces).
 *
 * <p>A file may carry the same payload signed with several keys, one armored
 * block each, Ed25519 first. The license is accepted if any block verifies
 * with a trusted key whose algorithm this Java runtime provides, trying
 * Ed25519 first. A runtime in FIPS mode (Red Hat's OpenJDK on a FIPS host,
 * or BouncyCastle FIPS in approved-only mode before 2.1.1) has no Ed25519, so
 * there the ECDSA P-256 block is the one that counts. Versions of this
 * library before 1.1.0 read only the first block, so they still accept a
 * dual-signed file through its Ed25519 signature.
 *
 * <p>Applications build the ENS public keys in (from /api/licensing/keys), so
 * a license can only ever be accepted if ENS signed it.
 */
public final class LicenseVerifier {
    public static final String FORMAT = "ens-license/1";

    private static final String BEGIN = "-----BEGIN ENS LICENSE-----";
    private static final String END = "-----END ENS LICENSE-----";
    /**
     * The longest file text read, in characters. A license is about 1.5 kB, or 3 kB signed twice; anything
     * much longer is not one, and is refused before it is parsed.
     */
    public static final int MAX_FILE_CHARS = 64 * 1024;
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]+");

    /** The algorithms a license may be signed with, most preferred first. */
    public enum Algorithm {
        ED25519("Ed25519", "Ed25519", "Ed25519",
                // SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING (33 bytes) }, then the 32-byte key
                "302a300506032b6570032100", 32),
        ECDSA_P256("ECDSA P-256", "EC", "SHA256withECDSA",
                // SEQUENCE { SEQUENCE { OID ecPublicKey, OID prime256v1 }, BIT STRING (66 bytes) }, then the
                // uncompressed point: 04, x, y
                "3059301306072a8648ce3d020106082a8648ce3d030107034200" + "04", 64);

        private final String displayName;
        private final String keyFactory;
        private final String signature;
        private final byte[] spkiPrefix;
        private final int spkiLength;

        Algorithm(String displayName, String keyFactory, String signature, String spkiPrefixHex, int keyBytes) {
            this.displayName = displayName;
            this.keyFactory = keyFactory;
            this.signature = signature;
            this.spkiPrefix = HexFormat.of().parseHex(spkiPrefixHex);
            this.spkiLength = spkiPrefix.length + keyBytes;
        }

        @Override
        public String toString() {
            return displayName;
        }

        static Algorithm of(byte[] spki) {
            for (Algorithm a : values()) {
                if (spki.length == a.spkiLength && Arrays.equals(spki, 0, a.spkiPrefix.length, a.spkiPrefix, 0, a.spkiPrefix.length)) {
                    return a;
                }
            }
            return null;
        }
    }

    /**
     * Where the verifier gets its JCA objects. The default asks the installed
     * providers, as {@code KeyFactory.getInstance} does; tests pass one that
     * lacks an algorithm, to act like a runtime in FIPS mode.
     */
    interface Jca {
        KeyFactory keyFactory(String algorithm) throws NoSuchAlgorithmException;

        Signature signature(String algorithm) throws NoSuchAlgorithmException;

        Jca INSTALLED_PROVIDERS = new Jca() {
            @Override
            public KeyFactory keyFactory(String algorithm) throws NoSuchAlgorithmException {
                return KeyFactory.getInstance(algorithm);
            }

            @Override
            public Signature signature(String algorithm) throws NoSuchAlgorithmException {
                return Signature.getInstance(algorithm);
            }
        };
    }

    private record TrustedKey(String id, Algorithm algorithm, byte[] spki) {}

    private record Token(String keyId, String payload, String signature) {}

    private final Map<String, TrustedKey> trusted;
    private final Jca jca;

    private LicenseVerifier(Map<String, TrustedKey> trusted, Jca jca) {
        if (trusted.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted public key is required");
        }
        this.trusted = Map.copyOf(trusted);
        this.jca = jca;
    }

    /**
     * A verifier trusting these public keys, each base64 SPKI DER as published
     * at /api/licensing/keys: Ed25519 or ECDSA P-256. Key ids are worked out
     * from the keys themselves. A key whose algorithm this runtime lacks is
     * still accepted here; licenses signed only with it are refused when
     * verified, saying why.
     */
    public static LicenseVerifier trusting(String... publicKeysBase64) {
        Map<String, TrustedKey> keys = new LinkedHashMap<>();
        for (String b64 : publicKeysBase64) {
            byte[] spki;
            try {
                spki = Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Not a base64 public key: " + e.getMessage(), e);
            }
            Algorithm algorithm = Algorithm.of(spki);
            if (algorithm == null) {
                throw new IllegalArgumentException("Not an Ed25519 or ECDSA P-256 public key (base64 SPKI DER, as /api/licensing/keys publishes)");
            }
            String id = keyId(spki);
            keys.put(id, new TrustedKey(id, algorithm, spki));
        }
        return new LicenseVerifier(keys, Jca.INSTALLED_PROVIDERS);
    }

    /** The same keys, with JCA objects from {@code jca}. For tests. */
    LicenseVerifier using(Jca jca) {
        return new LicenseVerifier(trusted, jca);
    }

    /** The algorithm of each trusted key, by key id: for a license screen or a startup log line. */
    public Map<String, Algorithm> trustedKeys() {
        Map<String, Algorithm> keys = new LinkedHashMap<>();
        trusted.values().forEach(k -> keys.put(k.id(), k.algorithm()));
        return keys;
    }

    /**
     * Whether this Java runtime can check signatures made with {@code algorithm},
     * e.g. false for Ed25519 on a runtime in FIPS mode.
     */
    public boolean isSupported(Algorithm algorithm) {
        try {
            jca.keyFactory(algorithm.keyFactory);
            jca.signature(algorithm.signature);
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** First 16 hex digits of SHA-256 over the key's SPKI encoding. */
    public static String keyId(PublicKey key) {
        return keyId(key.getEncoded());
    }

    private static String keyId(byte[] spki) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(spki);
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies the file's signature and reads it. Says nothing about dates; see {@link LicensePolicy}. */
    public EnsLicense verify(String fileText) throws LicenseException {
        List<Token> tokens = tokens(fileText);
        String payload = tokens.get(0).payload();
        for (Token t : tokens) {
            if (!t.payload().equals(payload)) {
                throw new LicenseException(LicenseException.Reason.MALFORMED,
                        "The file holds blocks for different licenses; use the file as it was issued");
            }
        }
        List<Token> candidates = new ArrayList<>();
        for (Token t : tokens) {
            if (trusted.containsKey(t.keyId())) candidates.add(t);
        }
        if (candidates.isEmpty()) {
            List<String> ids = new ArrayList<>();
            tokens.forEach(t -> ids.add(t.keyId()));
            throw new LicenseException(LicenseException.Reason.UNKNOWN_KEY, "Signed with an unknown key (" + String.join(", ", ids) + ")");
        }
        candidates.sort(Comparator.comparing((Token t) -> trusted.get(t.keyId()).algorithm()));

        boolean mismatch = false;
        Set<Algorithm> unavailable = new LinkedHashSet<>();
        String unavailableBecause = null;
        for (Token t : candidates) {
            TrustedKey key = trusted.get(t.keyId());
            PublicKey publicKey;
            Signature sig;
            try {
                publicKey = jca.keyFactory(key.algorithm().keyFactory).generatePublic(new X509EncodedKeySpec(key.spki()));
                sig = jca.signature(key.algorithm().signature);
                sig.initVerify(publicKey);
            } catch (GeneralSecurityException | ProviderException e) {
                // No such algorithm here (FIPS mode), or the provider can't take the key: try the next block.
                unavailable.add(key.algorithm());
                if (unavailableBecause == null) unavailableBecause = e.toString();
                continue;
            }
            boolean ok;
            try {
                sig.update((t.keyId() + "." + t.payload()).getBytes(StandardCharsets.US_ASCII));
                ok = sig.verify(Base64.getUrlDecoder().decode(t.signature()));
            } catch (SignatureException | IllegalArgumentException e) {
                ok = false; // not even a well-formed signature
            } catch (ProviderException e) {
                unavailable.add(key.algorithm());
                if (unavailableBecause == null) unavailableBecause = e.toString();
                continue;
            }
            if (ok) {
                return read(t.keyId(), new String(decodePayload(payload), StandardCharsets.UTF_8));
            }
            mismatch = true;
        }
        if (mismatch) {
            throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE,
                    "The signature does not match; the file was changed or damaged");
        }
        List<String> names = new ArrayList<>();
        unavailable.forEach(a -> names.add(a.toString()));
        throw new LicenseException(LicenseException.Reason.UNSUPPORTED_ALGORITHM,
                "The file is signed only with " + String.join(" and ", names) + ", which this Java runtime does not provide"
                        + (unavailable.contains(Algorithm.ED25519) ? " (Java in FIPS mode has no Ed25519)" : "")
                        + "; ask ENS Solutions for a license file with an ECDSA P-256 signature (" + unavailableBecause + ")");
    }

    /**
     * One token per armored block, or the whole text as one bare token. Blocks are found by scanning once,
     * left to right, so the time taken grows with the text's length and no worse.
     */
    private static List<Token> tokens(String fileText) throws LicenseException {
        if (fileText.length() > MAX_FILE_CHARS) {
            throw new LicenseException(LicenseException.Reason.MALFORMED,
                    "Not an ENS license: longer than " + MAX_FILE_CHARS + " characters");
        }
        List<String> raw = new ArrayList<>();
        int from = 0;
        while (true) {
            int begin = fileText.indexOf(BEGIN, from);
            if (begin < 0) break;
            int end = fileText.indexOf(END, begin + BEGIN.length());
            if (end < 0) break;
            raw.add(fileText.substring(begin + BEGIN.length(), end));
            from = end + END.length();
        }
        if (raw.isEmpty()) raw.add(fileText);
        List<Token> tokens = new ArrayList<>();
        for (String r : raw) {
            String[] parts = r.replaceAll("\\s+", "").split("\\.", -1);
            if (parts.length != 3 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches()
                    || !PART.matcher(parts[2]).matches()) {
                throw new LicenseException(LicenseException.Reason.MALFORMED, "Not an ENS license");
            }
            tokens.add(new Token(parts[0], parts[1], parts[2]));
        }
        return tokens;
    }

    private static byte[] decodePayload(String payload) throws LicenseException {
        try {
            return Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "The payload is not base64url");
        }
    }

    @SuppressWarnings("unchecked")
    private static EnsLicense read(String keyId, String json) throws LicenseException {
        Map<String, Object> p;
        try {
            p = (Map<String, Object>) Json.parse(json);
        } catch (RuntimeException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "The payload is not valid JSON");
        }
        if (!FORMAT.equals(p.get("format"))) {
            throw new LicenseException(LicenseException.Reason.UNSUPPORTED_FORMAT, "Unsupported license format " + p.get("format"));
        }
        try {
            Map<String, Object> licensee = (Map<String, Object>) p.get("licensee");
            Map<String, Object> limit = (Map<String, Object>) p.get("limit");
            Set<String> features = new LinkedHashSet<>();
            for (Object f : (List<Object>) p.get("features")) features.add((String) f);
            return new EnsLicense(
                    (String) p.get("id"),
                    ((Number) p.get("revision")).intValue(),
                    keyId,
                    (String) p.get("issuer"),
                    (String) p.get("product"),
                    (String) p.get("edition"),
                    (String) licensee.get("name"),
                    (String) licensee.get("organization"),
                    (String) licensee.get("email"),
                    ((Number) limit.get("quantity")).longValue(),
                    (String) limit.get("unit"),
                    Set.copyOf(features),
                    (String) p.get("support"),
                    Instant.parse((String) p.get("issuedAt")),
                    Instant.parse((String) p.get("notBefore")),
                    optionalInstant(p.get("expiresAt")),
                    ((Number) p.get("graceDays")).intValue(),
                    optionalInstant(p.get("maintenanceUntil")),
                    Optional.ofNullable((String) p.get("installationId")),
                    Boolean.TRUE.equals(p.get("watermark")));
        } catch (RuntimeException e) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "The payload is missing fields: " + e.getMessage());
        }
    }

    private static Optional<Instant> optionalInstant(Object v) {
        return v == null ? Optional.empty() : Optional.of(Instant.parse((String) v));
    }
}
