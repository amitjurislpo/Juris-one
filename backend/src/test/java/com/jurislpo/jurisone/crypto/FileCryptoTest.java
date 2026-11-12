package com.jurislpo.jurisone.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("File encryption at rest and key rotation")
class FileCryptoTest {

    static final String KEY_A = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String KEY_B = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    static final String KEY_C = "QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=";
    static final byte[] PDF = "%PDF-1.4\npayslip of Asha Verma, net 52,000\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    final FileCrypto crypto = new FileCrypto(new Keyring(KEY_A, ""));

    @Test
    void roundTripsAndNeverStoresThePlainBytes() {
        byte[] stored = crypto.seal(PDF);
        assertThat(FileCrypto.isSealed(stored)).isTrue();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("%PDF").doesNotContain("Asha");
        assertThat(crypto.open(stored)).isEqualTo(PDF);
        assertThat(crypto.seal(PDF)).isNotEqualTo(stored); // a fresh IV every time
    }

    @Test
    void emptyAndLargeFilesRoundTrip() {
        assertThat(crypto.open(crypto.seal(new byte[0]))).isEmpty();
        byte[] large = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(large);
        assertThat(crypto.open(crypto.seal(large))).isEqualTo(large);
    }

    @Test
    void anOldPlaintextFileIsReturnedAsItIs() {
        assertThat(crypto.open(PDF)).isEqualTo(PDF);
        assertThat(crypto.open(new byte[0])).isEmpty();
        assertThat(FileCrypto.isSealed(PDF)).isFalse();
        assertThat(crypto.isOnNewestKey(PDF)).isFalse();
        byte[] looksAlmostSealed = Arrays.copyOf("JOEF".getBytes(StandardCharsets.US_ASCII), 12); // magic but too short and no version
        assertThat(crypto.open(looksAlmostSealed)).isEqualTo(looksAlmostSealed);
    }

    @Test
    void anyChangeToTheFileIsDetected() {
        byte[] stored = crypto.seal(PDF);
        for (int position : List.of(0 + 5, 8, 9, 20, 21, stored.length / 2, stored.length - 1)) { // key id, IV, body, tag (the magic itself makes it a plaintext file)
            byte[] changed = stored.clone();
            changed[position] ^= 0x01;
            assertThat(opens(changed)).as("changed byte " + position).isFalse();
        }
        assertThat(opens(Arrays.copyOf(stored, stored.length - 1))).as("truncated").isFalse();
        assertThat(opens(Arrays.copyOf(stored, 30))).as("cut inside the body").isFalse();
        byte[] extended = Arrays.copyOf(stored, stored.length + 1);
        assertThat(opens(extended)).as("appended").isFalse();
    }

    @Test
    void aHeaderChangeIsDetectedEvenWhenTheKeyStillExists() {
        FileCrypto twoKeys = new FileCrypto(new Keyring(KEY_A, KEY_B));
        byte[] storedWithB = new FileCrypto(new Keyring(KEY_B, "")).seal(PDF);
        assertThat(twoKeys.open(storedWithB)).isEqualTo(PDF);
        byte[] swapped = storedWithB.clone(); // claim the file belongs to key A instead
        System.arraycopy(twoKeys.seal(new byte[1]), 5, swapped, 5, Keyring.KEY_ID_LENGTH);
        assertThatThrownBy(() -> twoKeys.open(swapped)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aWrongOrMissingKeyFailsWithoutRevealingAnything() {
        byte[] stored = crypto.seal(PDF);
        FileCrypto other = new FileCrypto(new Keyring(KEY_C, ""));
        assertThatThrownBy(() -> other.open(stored)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("Asha").hasMessageContaining("not in the keyring");
        assertThatThrownBy(() -> new FileCrypto(new Keyring("", "")).seal(PDF)).isInstanceOf(IllegalStateException.class).hasMessageContaining("32-byte");
        assertThatThrownBy(() -> new Keyring(KEY_A, "not-a-key").all()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void afterARotationNewFilesUseTheNewestKeyAndOldFilesStillRead() {
        byte[] oldFile = crypto.seal(PDF);
        FileCrypto rotated = new FileCrypto(new Keyring(KEY_B, KEY_A));
        assertThat(rotated.open(oldFile)).isEqualTo(PDF);
        assertThat(rotated.isOnNewestKey(oldFile)).isFalse();
        byte[] newFile = rotated.seal(PDF);
        assertThat(rotated.isOnNewestKey(newFile)).isTrue();
        assertThat(crypto.isOnNewestKey(newFile)).isFalse();
        // the new file is unreadable without key B: nothing falls back to the old key
        assertThatThrownBy(() -> crypto.open(newFile)).isInstanceOf(IllegalStateException.class);
        // the same key listed twice, or as both newest and previous, is one key
        assertThat(new Keyring(KEY_B, KEY_B + "," + KEY_A + "," + KEY_A).all()).hasSize(2);
    }

    @Test
    void writeIsAtomicLeavesNoTemporaryFileAndReplacesAnOldFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("deep").resolve("er").resolve("payslip.pdf");
        crypto.write(file, PDF);
        assertThat(crypto.read(file)).isEqualTo(PDF);
        assertThat(FileCrypto.isSealed(Files.readAllBytes(file))).isTrue();
        crypto.write(file, "second".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(crypto.read(file), StandardCharsets.UTF_8)).isEqualTo("second");
        try (Stream<Path> files = Files.list(file.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("payslip.pdf");
        }
    }

    @Test
    void hostileFileNamesAreJustNamesAndPathsOutsideTheFolderAreTheCallersToRefuse(@TempDir Path directory) throws IOException {
        for (String name : List.of("a'; DROP TABLE employees; --.pdf", "$(rm -rf x) && `id`.pdf", "नाम फ़ाइल.pdf", "with space and %00.pdf")) {
            Path file = directory.resolve(name);
            crypto.write(file, PDF);
            assertThat(crypto.read(file)).isEqualTo(PDF);
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertThat(files.count()).isEqualTo(4);
        }
    }

    boolean opens(byte[] stored) {
        try {
            byte[] plain = crypto.open(stored);
            return Arrays.equals(plain, PDF);
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
