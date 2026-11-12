package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.crypto.FileCrypto;
import com.jurislpo.jurisone.crypto.Keyring;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The encrypt-files command on temporary folders only. */
class EncryptStoredFilesServiceTest {

    static final String KEY_A = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    static final String KEY_B = "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";
    static final byte[] PDF = "%PDF-1.4\nconfidential\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    @TempDir Path storage;
    final FileCrypto keyA = new FileCrypto(new Keyring(KEY_A, ""));
    final FileCrypto keyBWithA = new FileCrypto(new Keyring(KEY_B, KEY_A));
    Path plain, sealedWithA, sealedWithB;

    @BeforeEach
    void files() throws IOException {
        plain = storage.resolve("proofs").resolve("employee-1").resolve("a'; DROP TABLE x; --.pdf");
        sealedWithA = storage.resolve("payslips").resolve("business").resolve("2026-10").resolve("JC001.pdf");
        sealedWithB = storage.resolve("tasks").resolve("नाम.pdf");
        Files.createDirectories(plain.getParent());
        Files.write(plain, PDF);
        keyA.write(sealedWithA, PDF);
        keyBWithA.write(sealedWithB, PDF);
    }

    EncryptStoredFilesService service(boolean isApplying) {
        return new EncryptStoredFilesService(storage, keyBWithA, isApplying);
    }

    @Test
    void aDryRunOnlyCountsAndChangesNothing() throws IOException {
        byte[] beforePlain = Files.readAllBytes(plain), beforeA = Files.readAllBytes(sealedWithA);
        assertThat(service(false).run()).containsEntry("encrypted", 1).containsEntry("rotated", 1).containsEntry("alreadyCurrent", 1).containsEntry("failed", 0);
        assertThat(Files.readAllBytes(plain)).isEqualTo(beforePlain);
        assertThat(Files.readAllBytes(sealedWithA)).isEqualTo(beforeA);
    }

    @Test
    void applyingEncryptsPlaintextAndMovesOldKeyFilesToTheNewestKeyAndIsIdempotent() throws IOException {
        assertThat(service(true).run()).containsEntry("encrypted", 1).containsEntry("rotated", 1).containsEntry("alreadyCurrent", 1);
        FileCrypto onlyB = new FileCrypto(new Keyring(KEY_B, "")); // the old key is no longer needed
        for (Path file : new Path[] {plain, sealedWithA, sealedWithB}) assertThat(onlyB.read(file)).isEqualTo(PDF);
        assertThat(new String(Files.readAllBytes(plain), StandardCharsets.ISO_8859_1)).doesNotContain("confidential");
        byte[] afterFirst = Files.readAllBytes(plain);
        assertThat(service(true).run()).containsEntry("encrypted", 0).containsEntry("rotated", 0).containsEntry("alreadyCurrent", 3);
        assertThat(Files.readAllBytes(plain)).isEqualTo(afterFirst); // not encrypted twice
    }

    @Test
    void aTamperedFileIsReportedAndLeftAloneWhileTheOthersAreDone() throws IOException {
        byte[] damaged = Files.readAllBytes(sealedWithA);
        damaged[damaged.length - 1] ^= 1;
        Files.write(sealedWithA, damaged);
        assertThat(service(true).run()).containsEntry("failed", 1).containsEntry("encrypted", 1);
        assertThat(Files.readAllBytes(sealedWithA)).isEqualTo(damaged);
    }

    @Test
    void aFileFromAKeyThatIsNotInTheKeyringIsReportedNotTouched() throws IOException {
        Path stranger = storage.resolve("stranger.pdf");
        new FileCrypto(new Keyring("QEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXWFlaW1xdXl8=", "")).write(stranger, PDF);
        byte[] before = Files.readAllBytes(stranger);
        assertThat(service(true).run()).containsEntry("failed", 1);
        assertThat(Files.readAllBytes(stranger)).isEqualTo(before);
    }

    @Test
    void anInterruptedRunLeavesATemporaryFileThatIsCountedThenRemovedAndTheRerunFinishes() throws IOException {
        Path leftover = plain.getParent().resolve(FileCrypto.TEMPORARY_PREFIX + "interrupted");
        Files.write(leftover, new byte[] {1, 2, 3});
        assertThat(service(false).run()).containsEntry("leftoverTemporary", 1);
        assertThat(leftover).exists();
        assertThat(service(true).run()).containsEntry("leftoverTemporary", 1);
        assertThat(leftover).doesNotExist();
        assertThat(service(true).run()).containsEntry("leftoverTemporary", 0).containsEntry("encrypted", 0);
    }

    @Test
    void aMissingFolderIsAnEmptyRunAndSymbolicLinksAreNeverFollowed(@TempDir Path elsewhere) throws IOException {
        assertThat(new EncryptStoredFilesService(storage.resolve("none"), keyBWithA, true).run().values()).containsOnly(0);
        Path outside = elsewhere.resolve("outside.pdf");
        Files.write(outside, PDF);
        try {
            Files.createSymbolicLink(storage.resolve("link.pdf"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return; // creating links is not allowed on this machine (Windows without developer mode); the rest of the test cannot apply
        }
        service(true).run();
        assertThat(Files.readAllBytes(outside)).isEqualTo(PDF); // a file outside the storage folder was not encrypted through the link
    }

    @Test
    void theResultNeverContainsFileNames() {
        Map<String, Integer> counts = service(true).run();
        assertThat(counts.keySet()).containsExactly("encrypted", "rotated", "alreadyCurrent", "changedMeanwhile", "failed", "leftoverTemporary");
    }
}
