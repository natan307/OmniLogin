package dev.omnilogin.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 (HmacSHA256) password hashing using only the JDK. Format: $pbkdf2$iterations$salt$hash
 */
public final class PasswordHasher {

    private static final int ITERATIONS = 65536;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int PARTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
        // utility class
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        return "$pbkdf2$" + ITERATIONS + "$" + encode(salt) + "$" + encode(hash);
    }

    public static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != PARTS || !"pbkdf2".equals(parts[1])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("PBKDF2 is not available", ex);
        }
    }

    private static String encode(byte[] data) {
        return new String(Base64.getEncoder().encode(data), StandardCharsets.US_ASCII);
    }
}
