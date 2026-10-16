// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a license file was signed by ENS Solutions and returns what it
 * says. Uses the JDK's own Ed25519; no network, no dependencies.
 *
 * <p>The file format, in short (the full description is in the site's
 * src/lib/licensing/license.ts):
 * <pre>
 * # readable summary lines, ignored
 * -----BEGIN ENS LICENSE-----
 * &lt;key id&gt;.&lt;payload base64url&gt;.&lt;signature base64url&gt;
 * -----END ENS LICENSE-----
 * </pre>
 * The signature is Ed25519 over the ASCII bytes "&lt;key id&gt;.&lt;payload&gt;".
 *
 * <p>Applications build the ENS public keys in (from /api/licensing/keys), so
 * a license can only ever be accepted if ENS signed it.
 */
public final class LicenseVerifier {
    public static final String FORMAT = "ens-license/1";

    private static final Pattern ARMOR =
            Pattern.compile("-----BEGIN ENS LICENSE-----(.*?)-----END ENS LICENSE-----", Pattern.DOTALL);
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9_-]+");

    private final Map<String, PublicKey> trusted;

    private LicenseVerifier(Map<String, PublicKey> trusted) {
        if (trusted.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted public key is required");
        }
        this.trusted = Map.copyOf(trusted);
    }

    /**
     * A verifier trusting these public keys, each base64 SPKI DER as published
     * at /api/licensing/keys. Key ids are worked out from the keys themselves.
     */
    public static LicenseVerifier trusting(String... publicKeysBase64) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String b64 : publicKeysBase64) {
            PublicKey key = publicKey(b64);
            keys.put(keyId(key), key);
        }
        return new LicenseVerifier(keys);
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Not an Ed25519 public key: " + e.getMessage(), e);
        }
    }

    /** First 16 hex digits of SHA-256 over the key's SPKI encoding. */
    public static String keyId(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Verifies the file's signature and reads it. Says nothing about dates; see {@link LicensePolicy}. */
    public EnsLicense verify(String fileText) throws LicenseException {
        Matcher m = ARMOR.matcher(fileText);
        String token = (m.find() ? m.group(1) : fileText).replaceAll("\\s+", "");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches()
                || !PART.matcher(parts[2]).matches()) {
            throw new LicenseException(LicenseException.Reason.MALFORMED, "Not an ENS license");
        }
        PublicKey key = trusted.get(parts[0]);
        if (key == null) {
            throw new LicenseException(LicenseException.Reason.UNKNOWN_KEY, "Signed with an unknown key (" + parts[0] + ")");
        }
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE,
                        "The signature does not match; the file was changed or damaged");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new LicenseException(LicenseException.Reason.BAD_SIGNATURE, "The signature could not be checked: " + e.getMessage());
        }
        return read(parts[0], new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
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
