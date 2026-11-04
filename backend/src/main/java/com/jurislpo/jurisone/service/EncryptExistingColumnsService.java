package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.PayslipSnapshotCrypto;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.crypto.SalaryAmountsCrypto;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository.CopiedColumn;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository.DateColumn;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository.TextColumn;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One-off, repeatable backfill ({@code encrypt-existing} command): encrypts the old plaintext values of the columns that are now
 * stored encrypted (text columns in place; dates, salary amounts and payslip snapshots into their new encrypted columns, clearing the plain ones).
 * Safe to run again at any time: values already in the {@code v1.} format are skipped (never encrypted twice), every new value is decrypted
 * again and compared before it is written, and a row changed by someone else meanwhile is left alone.
 * With {@code --jurisone.encrypt.dry-run=true} nothing is written; the counts show what would change. Values are never logged.
 */
@Service
public class EncryptExistingColumnsService {

    private static final Logger log = LoggerFactory.getLogger(EncryptExistingColumnsService.class);

    private final EncryptExistingColumnsRepository repo;
    private final PiiCrypto piiCrypto;
    private final SalaryAmountsCrypto salaryAmounts;
    private final PayslipSnapshotCrypto snapshots;
    private final boolean isDryRun;

    public EncryptExistingColumnsService(EncryptExistingColumnsRepository repo, PiiCrypto piiCrypto, SalaryAmountsCrypto salaryAmounts, PayslipSnapshotCrypto snapshots,
                                         @Value("${jurisone.encrypt.dry-run:false}") boolean isDryRun) {
        this.repo = repo;
        this.piiCrypto = piiCrypto;
        this.salaryAmounts = salaryAmounts;
        this.snapshots = snapshots;
        this.isDryRun = isDryRun;
    }

    /** Per column ("table.column"): how many rows were (or, in a dry run, would be) encrypted. */
    public Map<String, Integer> run() {
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (TextColumn target : EncryptExistingColumnsRepository.TEXT_COLUMNS) record(changed, target.label(), () -> encryptText(target));
        for (DateColumn target : EncryptExistingColumnsRepository.DATE_COLUMNS) record(changed, target.label(), () -> encryptDate(target));
        for (CopiedColumn target : EncryptExistingColumnsRepository.NAME_COLUMNS) record(changed, target.label(), () -> encryptCopy(target));
        record(changed, "salary_structures.amounts", this::encryptSalaryStructures);
        record(changed, "payslips.snapshot", this::encryptPayslips);
        return changed;
    }

    private void record(Map<String, Integer> changed, String label, Supplier<Integer> work) {
        int count = TenantContext.call(null, work); // no business scope: every business
        changed.put(label, count);
        log.info("{} {}: {} rows{}", isDryRun ? "Would encrypt" : "Encrypted", label, count, isDryRun ? " (dry run)" : "");
    }

    private String checked(String column, String plain) {
        String encrypted = piiCrypto.encrypt(plain);
        if (!plain.equals(piiCrypto.decrypt(encrypted))) throw new IllegalStateException("Round trip failed for " + column + "; nothing more was written");
        return encrypted;
    }

    private int encryptText(TextColumn target) {
        int count = 0;
        for (Map<String, Object> row : repo.findValues(target)) {
            String stored = (String) row.get("stored");
            if (stored.isEmpty() || PiiCrypto.isEncrypted(stored)) continue;
            String encrypted = checked(target.label(), stored);
            count += isDryRun ? 1 : repo.replaceIfUnchanged(target, row.get("id"), stored, encrypted);
        }
        return count;
    }

    /**
     * Fills (or repairs) the encrypted copy of a plaintext column that stays in place. A copy that already decrypts to the plain value is skipped;
     * a missing copy, or one that does not match (a row edited while the dual-write switch was off), is written again.
     */
    private int encryptCopy(CopiedColumn target) {
        int count = 0;
        for (Map<String, Object> row : repo.findCopies(target)) {
            String plain = (String) row.get("plain"), stored = (String) row.get("stored");
            if (stored != null && PiiCrypto.isEncrypted(stored) && plain.equals(readable(stored))) continue;
            String encrypted = checked(target.label(), plain);
            count += isDryRun ? 1 : repo.replaceCopyIfUnchanged(target, row.get("id"), plain, stored, encrypted);
        }
        return count;
    }

    /** The decrypted copy, or null when it cannot be read (so it is treated as needing a rewrite). */
    private String readable(String stored) {
        try {
            return piiCrypto.decrypt(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int encryptDate(DateColumn target) {
        int count = 0;
        for (Map<String, Object> row : repo.findPlainDates(target)) {
            String date = (String) row.get("stored");
            String encrypted = checked(target.label(), date);
            count += isDryRun ? 1 : repo.moveDateIfUnchanged(target, row.get("id"), date, encrypted);
        }
        return count;
    }

    private int encryptSalaryStructures() {
        int count = 0;
        for (Map<String, Object> row : repo.findPlainSalaryStructures()) {
            long basic = ((Number) row.get("basic")).longValue(), hra = ((Number) row.get("hra")).longValue(), lta = ((Number) row.get("lta")).longValue(), special = ((Number) row.get("special")).longValue();
            String encrypted = salaryAmounts.encode(basic, hra, lta, special);
            long[] back = salaryAmounts.amounts(encrypted, null, null, null, null);
            if (back[0] != basic || back[1] != hra || back[2] != lta || back[3] != special) throw new IllegalStateException("Round trip failed for salary_structures; nothing more was written");
            count += isDryRun ? 1 : repo.moveSalaryStructureIfUnchanged(row.get("id"), basic, hra, lta, special, encrypted);
        }
        return count;
    }

    private int encryptPayslips() {
        int count = 0;
        for (Map<String, Object> row : repo.findPlainPayslips()) {
            String json = (String) row.get("stored");
            String encrypted = snapshots.encode(json);
            if (!json.equals(snapshots.read(null, encrypted))) throw new IllegalStateException("Round trip failed for payslips; nothing more was written");
            count += isDryRun ? 1 : repo.movePayslipIfUnchanged(row.get("run_id"), row.get("employee_id"), json, encrypted);
        }
        return count;
    }
}
