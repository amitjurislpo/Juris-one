package com.jurislpo.jurisone.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final Keyring keyring;

    @Autowired
    public PiiCrypto(Keyring keyring) {
        this.keyring = keyring;
    }

    /** One key, no older ones (tests and tools). */
    public PiiCrypto(String rawKey) {
        this(new Keyring(rawKey, ""));
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return null;
        try {
            byte[] initializationVector = new byte[12];
            RANDOM.nextBytes(initializationVector);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyring.newest().secret(), new GCMParameterSpec(128, initializationVector));
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
        byte[] initializationVector, cipherWithTag;
        try {
            initializationVector = B64URL_DEC.decode(parts[1]);
            byte[] tag = B64URL_DEC.decode(parts[2]), cipherText = B64URL_DEC.decode(parts[3]);
            cipherWithTag = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, cipherWithTag, 0, cipherText.length);
            System.arraycopy(tag, 0, cipherWithTag, cipherText.length, tag.length);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unrecognised PII ciphertext", e);
        }
        // the newest key first; after a key rotation older values still open with a previous key
        for (Keyring.Key key : keyring.all()) {
            byte[] plain = tryDecrypt(key, initializationVector, cipherWithTag);
            if (plain != null) return new String(plain, StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("PII ciphertext failed authentication");
    }

    private static byte[] tryDecrypt(Keyring.Key key, byte[] initializationVector, byte[] cipherWithTag) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.secret(), new GCMParameterSpec(128, initializationVector));
            return cipher.doFinal(cipherWithTag);
        } catch (GeneralSecurityException | RuntimeException e) { // RuntimeException: the JDK throws ProviderException for a ciphertext of the wrong size
            return null;
        }
    }

    /** True when the value opens with the newest key (nothing to rotate). A value that opens with no key is reported as an error. */
    public boolean isOnNewestKey(String stored) {
        if (stored == null || stored.isEmpty()) return true;
        String[] parts = stored.split("\\.", -1);
        if (!isEncrypted(stored)) throw new IllegalArgumentException("Unrecognised PII ciphertext");
        try {
            byte[] tag = B64URL_DEC.decode(parts[2]), cipherText = B64URL_DEC.decode(parts[3]);
            byte[] cipherWithTag = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, cipherWithTag, 0, cipherText.length);
            System.arraycopy(tag, 0, cipherWithTag, cipherText.length, tag.length);
            if (tryDecrypt(keyring.newest(), B64URL_DEC.decode(parts[1]), cipherWithTag) != null) return true;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unrecognised PII ciphertext", e);
        }
        decrypt(stored); // throws when no key opens it
        return false;
    }

    /** The same value encrypted again with the newest key. */
    public String rotate(String stored) {
        return encrypt(decrypt(stored));
    }

    /** True when the value is in this class's stored format (so it was written by {@link #encrypt}). */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith("v1.") && stored.chars().filter(c -> c == '.').count() == 3;
    }

    /**
     * Reads a column that is being moved from plaintext to encrypted: a value in the stored format is decrypted, anything
     * else is an old plaintext row and is returned as it is. The backfill ({@code EncryptExistingColumns}) encrypts those rows.
     */
    public String decryptOrLegacy(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        return isEncrypted(stored) ? decrypt(stored) : stored;
    }

    /** Encrypts a value unless it already is encrypted (never encrypt twice). */
    public String encryptOnce(String plain) {
        return isEncrypted(plain) ? plain : encrypt(plain);
    }

    /** Last four significant characters (digits/letters), for masked display. */
    public static String last4(String value) {
        if (value == null || value.isEmpty()) return null;
        String clean = value.replaceAll("[^0-9A-Za-z]", "");
        return clean.isEmpty() ? null : clean.substring(Math.max(0, clean.length() - 4));
    }
}
