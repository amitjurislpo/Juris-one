package com.jurislpo.jurisone.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Application-level encryption for PII (PAN, Aadhaar, UAN, phone, address, bank account) — the same
 * format, so data written by either side reads on the other:
 * AES-256-GCM, 12-byte IV, 16-byte tag, no AAD, stored as {@code v1.<iv>.<tag>.<ciphertext>} (base64url, no padding).
 */
@Component
public class PiiCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

    private final String rawKey;

    public PiiCrypto(@Value("${jurisone.security.pii-encryption-key:}") String rawKey) {
        this.rawKey = rawKey;
    }

    private SecretKeySpec key() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(rawKey.trim());
        } catch (IllegalArgumentException e) {
            keyBytes = new byte[0];
        }
        if (keyBytes.length != 32) throw new IllegalStateException("PII_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
        return new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] initializationVector = new byte[12];
            RANDOM.nextBytes(initializationVector);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, initializationVector));
            byte[] out = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)); // ciphertext ‖ tag
            byte[] cipherText = Arrays.copyOfRange(out, 0, out.length - 16);
            byte[] tag = Arrays.copyOfRange(out, out.length - 16, out.length);
            return "v1." + B64URL.encodeToString(initializationVector) + "." + B64URL.encodeToString(tag) + "." + B64URL.encodeToString(cipherText);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        String[] parts = stored.split("\\.", -1);
        if (parts.length < 4 || !"v1".equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) {
            throw new IllegalArgumentException("Unrecognised PII ciphertext");
        }
        try {
            byte[] initializationVector = B64URL_DEC.decode(parts[1]), tag = B64URL_DEC.decode(parts[2]), cipherText = B64URL_DEC.decode(parts[3]);
            byte[] cipherWithTag = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, cipherWithTag, 0, cipherText.length);
            System.arraycopy(tag, 0, cipherWithTag, cipherText.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, initializationVector));
            return new String(cipher.doFinal(cipherWithTag), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("PII ciphertext failed authentication", e);
        }
    }

    /** Last four significant characters (digits/letters), for masked display. */
    public static String last4(String value) {
        if (value == null || value.isEmpty()) return null;
        String clean = value.replaceAll("[^0-9A-Za-z]", "");
        return clean.isEmpty() ? null : clean.substring(Math.max(0, clean.length() - 4));
    }
}
