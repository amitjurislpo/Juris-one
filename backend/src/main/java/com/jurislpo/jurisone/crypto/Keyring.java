package com.jurislpo.jurisone.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The encryption keys: the newest one ({@code jurisone.security.pii-encryption-key}, used for every new write) and any older ones
 * ({@code jurisone.security.pii-encryption-previous-keys}, comma-separated, only used to read what was written before a rotation).
 * Keys come from the environment only. Each key has an id (the first 4 bytes of its SHA-256) that is stored in the header of an
 * encrypted file, so a file names the key it needs without revealing it. Parsed on first use, so a missing key only fails where encryption is used.
 */
@Component
public class Keyring {

    /** One key and its id. */
    public record Key(byte[] id, SecretKeySpec secret) {
        public boolean hasId(byte[] other) {
            return Arrays.equals(id, other);
        }
    }

    public static final int KEY_ID_LENGTH = 4;

    private final String newestKey;
    private final String previousKeys;
    private volatile List<Key> keys;

    public Keyring(@Value("${jurisone.security.pii-encryption-key:}") String newestKey, @Value("${jurisone.security.pii-encryption-previous-keys:}") String previousKeys) {
        this.newestKey = newestKey == null ? "" : newestKey;
        this.previousKeys = previousKeys == null ? "" : previousKeys;
    }

    /** The key every new write uses. */
    public Key newest() {
        return all().get(0);
    }

    /** All keys, the newest first. */
    public List<Key> all() {
        List<Key> loaded = keys;
        if (loaded == null) {
            List<Key> parsed = new ArrayList<>();
            parsed.add(parse(newestKey, "PII_ENCRYPTION_KEY"));
            for (String previous : previousKeys.split(",")) {
                if (previous.isBlank()) continue;
                Key key = parse(previous, "PII_ENCRYPTION_PREVIOUS_KEYS");
                if (parsed.stream().noneMatch(existing -> existing.hasId(key.id()))) parsed.add(key);
            }
            keys = loaded = List.copyOf(parsed);
        }
        return loaded;
    }

    /** The key with this id, or null when it is not in the keyring. */
    public Key byId(byte[] id) {
        return all().stream().filter(key -> key.hasId(id)).findFirst().orElse(null);
    }

    /** True when the base64 text is a 32-byte key. */
    public static boolean isValidKey(String base64) {
        try {
            return Base64.getDecoder().decode(base64.trim()).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Key parse(String base64, String variableName) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length != 32) throw new IllegalStateException(variableName + " must hold 32-byte keys, base64-encoded");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return new Key(Arrays.copyOf(digest, KEY_ID_LENGTH), new SecretKeySpec(bytes, "AES"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
