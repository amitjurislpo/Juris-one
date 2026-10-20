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
        byte[] k;
        try {
            k = Base64.getDecoder().decode(rawKey.trim());
        } catch (IllegalArgumentException e) {
            k = new byte[0];
        }
        if (k.length != 32) throw new IllegalStateException("PII_ENCRYPTION_KEY must be 32 bytes, base64-encoded");
        return new SecretKeySpec(k, "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8)); // ciphertext ‖ tag
            byte[] ct = Arrays.copyOfRange(out, 0, out.length - 16);
            byte[] tag = Arrays.copyOfRange(out, out.length - 16, out.length);
            return "v1." + B64URL.encodeToString(iv) + "." + B64URL.encodeToString(tag) + "." + B64URL.encodeToString(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PII encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        String[] p = stored.split("\\.", -1);
        if (p.length < 4 || !"v1".equals(p[0]) || p[1].isEmpty() || p[2].isEmpty() || p[3].isEmpty()) {
            throw new IllegalArgumentException("Unrecognised PII ciphertext");
        }
        try {
            byte[] iv = B64URL_DEC.decode(p[1]), tag = B64URL_DEC.decode(p[2]), ct = B64URL_DEC.decode(p[3]);
            byte[] in = new byte[ct.length + tag.length];
            System.arraycopy(ct, 0, in, 0, ct.length);
            System.arraycopy(tag, 0, in, ct.length, tag.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(in), StandardCharsets.UTF_8);
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
