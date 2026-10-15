package com.winllc.dsp.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Checks an ENS Solutions license file against the vendor's Ed25519 public key.
 *
 * <p>A license is a properties file. Every property but {@code signature} is signed: the keys are
 * sorted, written as {@code key=value} lines joined by {@code \n}, and the UTF-8 bytes signed. The
 * public key is read from the classpath ({@value #PUBLIC_KEY_RESOURCE}) and is deliberately not
 * configurable at run time: a deployment that could name its own key could sign its own license.
 */
public final class LicenseVerifier {

    public static final String PUBLIC_KEY_RESOURCE = "license/ens-license-public.pem";

    private final PublicKey publicKey;
    private final String product;

    public LicenseVerifier(PublicKey publicKey, String product) {
        this.publicKey = publicKey;
        this.product = product;
    }

    /** A verifier using the public key built into the application. */
    public static LicenseVerifier forProduct(String product) {
        ClassLoader loader = LicenseVerifier.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("No license public key on the classpath at " + PUBLIC_KEY_RESOURCE);
            }
            String pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", ""));
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            return new LicenseVerifier(key, product);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not read the license public key: " + e.getMessage(), e);
        }
    }

    /**
     * Parses and checks a license. Throws {@link InvalidLicenseException} saying why when the
     * signature, product or fields are wrong. Expiry is not checked here: an expired license is
     * still a genuine one, and the caller decides what that means.
     */
    public License verify(String text) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(text));
        } catch (IOException | IllegalArgumentException e) {
            throw new InvalidLicenseException("the file is not a license file");
        }
        String signature = props.getProperty("signature");
        if (signature == null || signature.isBlank()) {
            throw new InvalidLicenseException("the file has no signature");
        }
        Map<String, String> fields = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.equals("signature")) {
                fields.put(key, props.getProperty(key).trim());
            }
        }
        StringBuilder canonical = new StringBuilder();
        fields.forEach((k, v) -> canonical.append(k).append('=').append(v).append('\n'));

        boolean valid;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            valid = verifier.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            valid = false;
        }
        if (!valid) {
            throw new InvalidLicenseException("the signature does not match; the file was altered or not issued by ENS Solutions");
        }

        String licensedProduct = required(fields, "product");
        if (!licensedProduct.equals(product)) {
            throw new InvalidLicenseException("the license is for " + licensedProduct + ", not " + product);
        }
        Set<String> features = Arrays.stream(required(fields, "features").split(","))
                .map(String::trim)
                .filter(f -> !f.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String expires = required(fields, "expires");
        return new License(
                required(fields, "licenseId"),
                licensedProduct,
                required(fields, "licensee"),
                date(required(fields, "issued")),
                expires.equalsIgnoreCase("never") ? null : date(expires),
                Set.copyOf(features));
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isEmpty()) {
            throw new InvalidLicenseException("the license has no " + name);
        }
        return value;
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidLicenseException("'" + value + "' is not a date (YYYY-MM-DD)");
        }
    }

    public static final class InvalidLicenseException extends RuntimeException {
        public InvalidLicenseException(String message) {
            super(message);
        }
    }
}
