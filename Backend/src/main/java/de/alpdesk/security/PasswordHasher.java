package de.alpdesk.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes the tenant users' passwords with PBKDF2-HMAC-SHA256 (600 000 iterations, OWASP's recommendation) - part of
 * the JDK, no library needed. Stored form: {@code pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>}, so the
 * iterations can be raised later without breaking stored hashes.
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Verified against when a username is unknown, so the answer takes as long as for a wrong password. */
    private static final String DUMMY_HASH = hash("no-such-user");

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        Base64.Encoder base64 = Base64.getEncoder();
        return PREFIX + "$" + ITERATIONS + "$" + base64.encodeToString(salt) + "$"
                + base64.encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean matches(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (password == null || parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        Base64.Decoder base64 = Base64.getDecoder();
        byte[] expected = base64.decode(parts[3]);
        return MessageDigest.isEqual(expected, derive(password, base64.decode(parts[2]), Integer.parseInt(parts[1])));
    }

    /** Spends the time of a verification without a user - see {@link #DUMMY_HASH}. */
    public static void matchNothing(String password) {
        matches(password == null ? "" : password, DUMMY_HASH);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
