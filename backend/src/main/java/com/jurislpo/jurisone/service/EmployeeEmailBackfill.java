package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.EmployeeEmailEncryption;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The email step of the {@code encrypt-existing} command: fills (or repairs) {@code employees.email_enc} and {@code employees.email_blind_index}
 * for rows written before the dual-write switch was on. The plaintext column is never changed.
 * Repeatable and resumable: a row whose copy already decrypts to the plain email and whose index is already right is skipped; a row changed by someone else
 * meanwhile is left alone; every new copy is decrypted again and compared before it is written. {@code --jurisone.encrypt.dry-run=true} only counts.
 * Two rows whose addresses differ only in letter case (possible in old data, the plaintext index is case-sensitive) would get the same blind index:
 * the first keeps it, the other is reported as a conflict, stays without a blind index and keeps being found through the plaintext fallback.
 * Emails and indexes are never logged.
 */
@Service
public class EmployeeEmailBackfill {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEmailBackfill.class);

    private final EncryptExistingColumnsRepository repo;
    private final PiiCrypto piiCrypto;
    private final EmployeeEmailEncryption emailEncryption;
    private final boolean isDryRun;

    public EmployeeEmailBackfill(EncryptExistingColumnsRepository repo, PiiCrypto piiCrypto, EmployeeEmailEncryption emailEncryption,
                                 @Value("${jurisone.encrypt.dry-run:false}") boolean isDryRun) {
        this.repo = repo;
        this.piiCrypto = piiCrypto;
        this.emailEncryption = emailEncryption;
        this.isDryRun = isDryRun;
    }

    /** Counts per label: copies written, indexes written, conflicts, and how many employees still have no blind index (read after the run). */
    public Map<String, Integer> run() {
        if (!emailEncryption.isKeyConfigured()) {
            log.warn("employees.email skipped: jurisone.security.email-blind-index-key (EMAIL_BLIND_INDEX_KEY) is not set");
            return new LinkedHashMap<>(Map.of("employees.email_skipped_key_not_set", 1));
        }
        int copies = 0, indexes = 0, conflicts = 0;
        Map<String, Object> firstOwnerOfIndex = new HashMap<>();
        for (Map<String, Object> row : repo.findEmails()) {
            String plain = (String) row.get("plain"), stored = (String) row.get("stored"), blindIndex = (String) row.get("blind_index");
            String expectedIndex = emailEncryption.blindIndex(plain);
            if (expectedIndex == null) continue;
            Object owner = firstOwnerOfIndex.putIfAbsent(expectedIndex, row.get("id"));
            boolean isCopyCurrent = stored != null && PiiCrypto.isEncrypted(stored) && plain.equals(readable(stored));
            boolean isIndexCurrent = expectedIndex.equals(blindIndex);
            if (isCopyCurrent && isIndexCurrent) continue;
            if (owner != null) { // another row already owns this index
                conflicts++;
                continue;
            }
            String newStored = isCopyCurrent ? stored : piiCrypto.encrypt(plain);
            if (!plain.equals(piiCrypto.decrypt(newStored))) throw new IllegalStateException("Round trip failed for employees.email_enc; nothing more was written");
            if (isDryRun) {
                if (!isCopyCurrent) copies++;
                if (!isIndexCurrent) indexes++;
                continue;
            }
            try {
                if (repo.replaceEmailIfUnchanged(row.get("id"), plain, stored, blindIndex, newStored, expectedIndex) > 0) {
                    if (!isCopyCurrent) copies++;
                    if (!isIndexCurrent) indexes++;
                }
            } catch (DuplicateKeyException e) { // the index already belongs to a row this run did not reach in order
                conflicts++;
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("employees.email_enc", copies);
        counts.put("employees.email_blind_index", indexes);
        counts.put("employees.email_blind_index_conflicts", conflicts);
        counts.put("employees.email_blind_index_still_missing", repo.countEmailsWithoutBlindIndex());
        log.info("{} employees.email: {} copies, {} blind indexes, {} conflicts, {} still without blind index{}", isDryRun ? "Would encrypt" : "Encrypted", copies, indexes, conflicts,
                counts.get("employees.email_blind_index_still_missing"), isDryRun ? " (dry run)" : "");
        return counts;
    }

    /** The decrypted copy, or null when it cannot be read (so it is treated as needing a rewrite). */
    private String readable(String stored) {
        try {
            return piiCrypto.decrypt(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
