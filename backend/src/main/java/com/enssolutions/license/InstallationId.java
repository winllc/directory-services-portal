// Copied unchanged from products-page licensing/java (the ENS license verifier library).
package com.enssolutions.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;

/**
 * This installation's id, made once and kept in the state directory: a
 * product prefix and eight random characters, e.g. "CA-7F3K-29QD". The
 * application shows it on its license screen; a customer enters it in their
 * account to bind a license to this installation.
 */
public final class InstallationId {
    // Crockford base32 without I, L, O, U: nothing to misread when typing it in.
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Map<String, String> PREFIX = Map.of(
            "certalert", "CA",
            "mfa-portal", "RP",
            "readyroom", "RR",
            "directory-portal", "DP");

    private InstallationId() {}

    public static String loadOrCreate(Path stateDir, String product) throws IOException {
        Path file = stateDir.resolve("installation-id");
        if (Files.exists(file)) {
            String id = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (!id.isEmpty()) return id;
        }
        Files.createDirectories(stateDir);
        String id = generate(product, new SecureRandom());
        Path tmp = Files.createTempFile(stateDir, "installation-id", ".tmp");
        Files.writeString(tmp, id + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return id;
    }

    static String generate(String product, SecureRandom random) {
        StringBuilder b = new StringBuilder(PREFIX.getOrDefault(product, "EN")).append('-');
        for (int i = 0; i < 8; i++) {
            if (i == 4) b.append('-');
            b.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return b.toString();
    }
}
