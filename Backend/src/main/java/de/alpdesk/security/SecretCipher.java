package de.alpdesk.security;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts secrets (the AI providers' API keys) for storage with AES-256-GCM. Output format:
 * {@code enc:v1:<base64(12-byte IV | ciphertext+tag)>} - the version prefix leaves room for a
 * later key rotation. GCM authenticates the ciphertext, so a tampered or foreign value fails to
 * decrypt instead of yielding garbage.
 */
@Singleton
public class SecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secret-key}") String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.secret-key (APP_SECRET_KEY) is not valid base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("app.secret-key (APP_SECRET_KEY) must be 32 bytes (base64 of `openssl rand -base64 32`), got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt secret", e);
        }
    }

    /**
     * @throws IllegalStateException if the value is not in the expected format or was encrypted
     *                               with a different key
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Value is not an encrypted secret");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            return new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt secret - was APP_SECRET_KEY changed?", e);
        }
    }
}
