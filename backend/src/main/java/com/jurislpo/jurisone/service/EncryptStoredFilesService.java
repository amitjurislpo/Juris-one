package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.FileCrypto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Command {@code encrypt-files}: brings every file under the storage directory to "encrypted with the newest key" - old plaintext files are
 * encrypted, files encrypted with an older key (after a key rotation) are encrypted again with the newest one. A dry run (the default) only counts;
 * {@code --jurisone.encrypt.apply=true} writes. Safe to repeat and to stop at any point: each file is replaced atomically, only after the new
 * bytes were opened again and compared with the old content, and a file that changed meanwhile is left for the next run. Symbolic links are never followed.
 * Only counts are logged, never file names or contents.
 */
@Service
public class EncryptStoredFilesService {

    private static final Logger log = LoggerFactory.getLogger(EncryptStoredFilesService.class);

    private final Path storageDir;
    private final FileCrypto fileCrypto;
    private final boolean isApplying;

    @Autowired
    public EncryptStoredFilesService(JurisOneProperties properties, FileCrypto fileCrypto, @Value("${jurisone.encrypt.apply:false}") boolean isApplying) {
        this(Path.of(properties.storageDir()), fileCrypto, isApplying);
    }

    /** For tests and tools: an explicit folder and mode. */
    public EncryptStoredFilesService(Path storageDir, FileCrypto fileCrypto, boolean isApplying) {
        this.storageDir = storageDir.toAbsolutePath().normalize();
        this.fileCrypto = fileCrypto;
        this.isApplying = isApplying;
    }

    /**
     * Counts: {@code encrypted} (plaintext files turned into encrypted ones), {@code rotated} (older key to newest), {@code alreadyCurrent},
     * {@code changedMeanwhile} (left for the next run), {@code failed} (unreadable or failing authentication - needs a person) and
     * {@code leftoverTemporary} (half-written temporary files from an interrupted run; removed when applying). In a dry run the first two are "would be" counts.
     */
    public Map<String, Integer> run() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : List.of("encrypted", "rotated", "alreadyCurrent", "changedMeanwhile", "failed", "leftoverTemporary")) counts.put(name, 0);
        if (!Files.isDirectory(storageDir)) return counts;
        try (Stream<Path> walk = Files.walk(storageDir)) {
            for (Path path : walk.filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String outcome = handle(path);
                counts.merge(outcome, 1, Integer::sum);
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("Could not walk the storage directory", e);
        }
        log.info("Stored files {}: {}", isApplying ? "encrypted" : "(dry run, nothing written)", counts);
        return counts;
    }

    private String handle(Path path) {
        try {
            if (path.getFileName().toString().startsWith(FileCrypto.TEMPORARY_PREFIX)) {
                if (isApplying) Files.deleteIfExists(path);
                return "leftoverTemporary";
            }
            byte[] stored = Files.readAllBytes(path);
            if (fileCrypto.isOnNewestKey(stored)) return "alreadyCurrent";
            boolean wasSealed = FileCrypto.isSealed(stored);
            byte[] plain = fileCrypto.open(stored); // fails on a changed or unknown-key file
            if (!isApplying) return wasSealed ? "rotated" : "encrypted";
            byte[] sealed = fileCrypto.seal(plain);
            if (!Arrays.equals(plain, fileCrypto.open(sealed))) throw new IllegalStateException("Round trip failed");
            if (!Arrays.equals(stored, Files.readAllBytes(path))) return "changedMeanwhile";
            fileCrypto.writeSealed(path, sealed);
            return wasSealed ? "rotated" : "encrypted";
        } catch (IOException | RuntimeException e) {
            return "failed"; // no name or content in the log; the count tells the operator to look
        }
    }
}
