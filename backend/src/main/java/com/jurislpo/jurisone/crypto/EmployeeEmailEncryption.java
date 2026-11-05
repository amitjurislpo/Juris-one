package com.jurislpo.jurisone.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Employee work email (the sign-in name) next to its plaintext column on {@code employees}: an encrypted copy ({@code email_enc}, the
 * {@link PiiCrypto} format) and a blind index ({@code email_blind_index}): HMAC-SHA256, as 64 hex characters, of the normalised address
 * (trimmed, lower-case, exactly what the plaintext column holds; no Unicode folding, so look-alike characters stay different addresses),
 * made with its own key ({@code jurisone.security.email-blind-index-key}, EMAIL_BLIND_INDEX_KEY: 32 random bytes, base64, never the PII key).
 * The index is only an equality lookup: it cannot be turned back into the address without the key, and the same address always gives the same value.
 * <p>
 * Every switch defaults to off, so with the defaults nothing changes in the application:
 * <ul>
 * <li>{@code jurisone.security.encrypt-email.dual-write}: writers also store the encrypted copy and the blind index (off: stored as NULL, never stale).</li>
 * <li>{@code jurisone.security.encrypt-email.lookup-by-blind-index}: sign-in and "email already used" find the employee by the blind index.</li>
 * <li>{@code jurisone.security.encrypt-email.plaintext-fallback} (default true): while on, an address not found by blind index is looked up in the plaintext column
 * too (rows not yet filled by the backfill). Turn it off only when {@code encrypt-existing} reports no row still missing.</li>
 * <li>{@code jurisone.security.encrypt-email.read-encrypted}: readers prefer the decrypted copy (plaintext when the copy is empty or unreadable).</li>
 * </ul>
 */
@Component
public class EmployeeEmailEncryption {

    private static final String DOMAIN = "jurisone-employee-email-v1:";

    private final PiiCrypto piiCrypto;
    private final String rawKey;
    private final boolean isDualWriteEnabled;
    private final boolean isLookupByBlindIndex;
    private final boolean isPlaintextFallback;
    private final boolean isReadEncrypted;

    public EmployeeEmailEncryption(PiiCrypto piiCrypto, @Value("${jurisone.security.email-blind-index-key:}") String rawKey,
                                   @Value("${jurisone.security.encrypt-email.dual-write:false}") boolean isDualWriteEnabled,
                                   @Value("${jurisone.security.encrypt-email.lookup-by-blind-index:false}") boolean isLookupByBlindIndex,
                                   @Value("${jurisone.security.encrypt-email.plaintext-fallback:true}") boolean isPlaintextFallback,
                                   @Value("${jurisone.security.encrypt-email.read-encrypted:false}") boolean isReadEncrypted) {
        this.piiCrypto = piiCrypto;
        this.rawKey = rawKey == null ? "" : rawKey;
        this.isDualWriteEnabled = isDualWriteEnabled;
        this.isLookupByBlindIndex = isLookupByBlindIndex;
        this.isPlaintextFallback = isPlaintextFallback;
        this.isReadEncrypted = isReadEncrypted;
        if (isDualWriteEnabled || isLookupByBlindIndex) key(); // fail at start-up, not at the first sign-in
        if (isLookupByBlindIndex && !isPlaintextFallback && !isDualWriteEnabled) {
            throw new IllegalStateException("encrypt-email.plaintext-fallback=false needs encrypt-email.dual-write=true: new addresses would otherwise get no blind index and could not sign in");
        }
    }

    private SecretKeySpec key() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(rawKey.trim());
        } catch (IllegalArgumentException e) {
            keyBytes = new byte[0];
        }
        if (keyBytes.length != 32) throw new IllegalStateException("EMAIL_BLIND_INDEX_KEY (jurisone.security.email-blind-index-key) must be 32 bytes, base64-encoded");
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /** Whether a valid blind index key is set (the backfill skips the email step without one). */
    public boolean isKeyConfigured() {
        try {
            key();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** The address the way it is stored and looked up: trimmed and lower-case; null when empty. */
    public static String normalise(String email) {
        if (email == null) return null;
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        return normalised.isEmpty() ? null : normalised;
    }

    /** The blind index of the address (64 hex characters), whatever the switches say; null for an empty address. */
    public String blindIndex(String email) {
        String normalised = normalise(email);
        if (normalised == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key());
            return HexFormat.of().formatHex(mac.doFinal((DOMAIN + normalised).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Email blind index failed", e);
        }
    }

    /** The encrypted copy to store with the address, or null when dual-write is off or the address is empty. */
    public String encryptForWrite(String email) {
        return isDualWriteEnabled ? piiCrypto.encrypt(email) : null;
    }

    /** The blind index to store with the address, or null when dual-write is off or the address is empty. */
    public String blindIndexForWrite(String email) {
        return isDualWriteEnabled ? blindIndex(email) : null;
    }

    /** The address of an employee entity: the decrypted copy when reading encrypted values is on, else the plaintext column. */
    public String emailOf(com.jurislpo.jurisone.entity.Employee employee) {
        return read(employee.getEmail(), employee.getEmailEnc());
    }

    public boolean isLookupByBlindIndex() {
        return isLookupByBlindIndex;
    }

    public boolean isPlaintextFallback() {
        return isPlaintextFallback;
    }

    /** The address to use: the decrypted copy when reading encrypted values is on and the copy is readable, otherwise the plaintext column. */
    public String read(String plain, String encrypted) {
        if (!isReadEncrypted || encrypted == null || encrypted.isEmpty()) return plain;
        try {
            return piiCrypto.decrypt(encrypted);
        } catch (IllegalArgumentException e) {
            return plain;
        }
    }
}
