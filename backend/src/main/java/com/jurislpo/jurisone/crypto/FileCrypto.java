package com.jurislpo.jurisone.crypto;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * Encryption at rest for files kept under the storage directory (payslip PDFs, investment proofs, onboarding documents,
 * task attachments, policy documents, regularization attachments).
 *
 * <p>Stored layout of an encrypted file: {@code "JOEF"} (4 bytes magic), version (1 byte, currently 1), key id (4 bytes, see {@link Keyring}),
 * IV (12 bytes), then AES-256-GCM ciphertext with its 16-byte tag. The first 9 bytes (magic, version, key id) are the GCM associated data,
 * so a changed header is detected as well as a changed body. A file without this magic is an old plaintext file and is returned as it is,
 * so the rollout needs no downtime; {@code EncryptStoredFilesService} encrypts those files later. File contents and names are never logged here.
 */
@Component
public class FileCrypto {

    static final byte[] MAGIC = {'J', 'O', 'E', 'F'};
    static final byte VERSION = 1;
    static final int HEADER_LENGTH = MAGIC.length + 1 + Keyring.KEY_ID_LENGTH;
    static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Prefix of the temporary file written beside the real one (so a crash never leaves a half-written file under the real name). */
    public static final String TEMPORARY_PREFIX = ".enc-tmp-";

    private final Keyring keyring;

    public FileCrypto(Keyring keyring) {
        this.keyring = keyring;
    }

    /** True when the bytes start with this class's header (so they were written by {@link #seal}). */
    public static boolean isSealed(byte[] stored) {
        if (stored == null || stored.length < HEADER_LENGTH + IV_LENGTH + TAG_BITS / 8 || stored[MAGIC.length] != VERSION) return false;
        for (int i = 0; i < MAGIC.length; i++) if (stored[i] != MAGIC[i]) return false;
        return true;
    }

    /** Encrypts with the newest key. */
    public byte[] seal(byte[] plain) {
        try {
            Keyring.Key key = keyring.newest();
            byte[] header = ByteBuffer.allocate(HEADER_LENGTH).put(MAGIC).put(VERSION).put(key.id()).array();
            byte[] initializationVector = new byte[IV_LENGTH];
            RANDOM.nextBytes(initializationVector);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, initializationVector));
            cipher.updateAAD(header);
            byte[] cipherText = cipher.doFinal(plain);
            return ByteBuffer.allocate(header.length + IV_LENGTH + cipherText.length).put(header).put(initializationVector).put(cipherText).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("File encryption failed", e);
        }
    }

    /** The plain bytes: decrypted when the file is sealed, returned as they are when it is an old plaintext file. Fails on a changed, truncated or unknown-key file. */
    public byte[] open(byte[] stored) {
        if (!isSealed(stored)) return stored;
        byte[] keyId = Arrays.copyOfRange(stored, MAGIC.length + 1, HEADER_LENGTH);
        Keyring.Key key = keyring.byId(keyId);
        if (key == null) throw new IllegalStateException("The stored file was encrypted with a key that is not in the keyring");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.secret(), new GCMParameterSpec(TAG_BITS, stored, HEADER_LENGTH, IV_LENGTH));
            cipher.updateAAD(stored, 0, HEADER_LENGTH);
            return cipher.doFinal(stored, HEADER_LENGTH + IV_LENGTH, stored.length - HEADER_LENGTH - IV_LENGTH);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("The stored file failed authentication (it was changed or damaged)", e);
        }
    }

    /** True when the file needs no change: sealed with the newest key. */
    public boolean isOnNewestKey(byte[] stored) {
        return isSealed(stored) && Arrays.equals(Arrays.copyOfRange(stored, MAGIC.length + 1, HEADER_LENGTH), keyring.newest().id());
    }

    /** Encrypts and writes the file; the whole file appears at once or not at all. Creates missing folders. */
    public void write(Path path, byte[] plain) throws IOException {
        writeSealed(path, seal(plain));
    }

    /** Writes bytes that are already in stored form, replacing the file atomically. */
    public void writeSealed(Path path, byte[] stored) throws IOException {
        Path directory = path.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve(TEMPORARY_PREFIX + UUID.randomUUID());
        try {
            Files.write(temporary, stored);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Reads and, when the file is encrypted, decrypts it. */
    public byte[] read(Path path) throws IOException {
        return open(Files.readAllBytes(path));
    }
}
