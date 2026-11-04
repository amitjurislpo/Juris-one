package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads and rewrites the columns that are moved from plaintext to encrypted. Table and column names come only from the fixed
 * allow-lists below, never from input; values are always bound parameters. Called without a business scope, so every business is covered.
 */
@Repository
public class EncryptExistingColumnsRepository {

    /** A text column encrypted in place (the stored text becomes {@code v1.…}); {@code idColumn} is the table's single-column key. */
    public record TextColumn(String table, String idColumn, String column) {
        public String label() {
            return table + "." + column;
        }
    }

    /** A date moved to its own encrypted text column; the plain date column is emptied. */
    public record DateColumn(String table, String idColumn, String dateColumn, String encryptedColumn) {
        public String label() {
            return table + "." + dateColumn;
        }
    }

    /**
     * A plaintext column that keeps its value (stage 1: the encrypted copy is written beside it, reads still use the plain column).
     * The encrypted copy is empty or stale until the backfill fills it.
     */
    public record CopiedColumn(String table, String idColumn, String plainColumn, String encryptedColumn) {
        public String label() {
            return table + "." + encryptedColumn;
        }
    }

    public static final List<CopiedColumn> NAME_COLUMNS = List.of(
            new CopiedColumn("employees", "id", "full_name", "full_name_enc"), new CopiedColumn("employees", "id", "first_name", "first_name_enc"),
            new CopiedColumn("employees", "id", "last_name", "last_name_enc"), new CopiedColumn("employees", "id", "designation", "designation_enc"));

    public static final List<TextColumn> TEXT_COLUMNS = List.of(
            new TextColumn("employees", "id", "father_name"), new TextColumn("employees", "id", "emergency_name"),
            new TextColumn("employees", "id", "emergency_relation"), new TextColumn("employees", "id", "emergency_phone"),
            new TextColumn("employees", "id", "bank_name"), new TextColumn("employees", "id", "bank_ifsc"),
            new TextColumn("onboarding_statutory", "onboarding_id", "name_per_pan"), new TextColumn("onboarding_statutory", "onboarding_id", "name_per_aadhaar"),
            new TextColumn("onboarding_statutory", "onboarding_id", "passport_number"), new TextColumn("onboarding_statutory", "onboarding_id", "name_per_passport"));

    public static final List<DateColumn> DATE_COLUMNS = List.of(
            new DateColumn("employees", "id", "date_of_birth", "date_of_birth_enc"),
            new DateColumn("onboarding_personal", "onboarding_id", "date_of_birth", "date_of_birth_enc"));

    private final JdbcTemplate jdbc;

    public EncryptExistingColumnsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static TextColumn allowed(TextColumn target) {
        if (!TEXT_COLUMNS.contains(target)) throw new IllegalArgumentException("Not an encrypted column: " + target);
        return target;
    }

    private static DateColumn allowed(DateColumn target) {
        if (!DATE_COLUMNS.contains(target)) throw new IllegalArgumentException("Not an encrypted column: " + target);
        return target;
    }

    private static CopiedColumn allowed(CopiedColumn target) {
        if (!NAME_COLUMNS.contains(target)) throw new IllegalArgumentException("Not an encrypted column: " + target);
        return target;
    }

    // ── plaintext kept, encrypted copy beside it ──

    /** Id, plain value and stored encrypted copy of the rows that have a plain value. */
    public List<Map<String, Object>> findCopies(CopiedColumn target) {
        allowed(target);
        return jdbc.queryForList("SELECT " + target.idColumn() + " AS id, " + target.plainColumn() + " AS plain, " + target.encryptedColumn() + " AS stored FROM " + target.table()
                + " WHERE " + target.plainColumn() + " IS NOT NULL AND " + target.plainColumn() + " <> ''");
    }

    /** Sets the encrypted copy only if the row still has the plain value and the copy that were read. Returns rows changed. */
    public int replaceCopyIfUnchanged(CopiedColumn target, Object id, String expectedPlain, String expectedStored, String newStored) {
        allowed(target);
        return jdbc.update("UPDATE " + target.table() + " SET " + target.encryptedColumn() + " = ? WHERE " + target.idColumn() + " = ? AND " + target.plainColumn() + " = ? AND "
                + target.encryptedColumn() + " IS NOT DISTINCT FROM ?", newStored, id, expectedPlain, expectedStored);
    }

    // ── text columns ──

    /** Id and stored value of the rows that have a value in the column. */
    public List<Map<String, Object>> findValues(TextColumn target) {
        allowed(target);
        return jdbc.queryForList("SELECT " + target.idColumn() + " AS id, " + target.column() + " AS stored FROM " + target.table() + " WHERE " + target.column() + " IS NOT NULL");
    }

    /** Replaces the stored value only if it still is the one that was read (so a concurrent edit is never overwritten). Returns rows changed. */
    public int replaceIfUnchanged(TextColumn target, Object id, String expectedStored, String newStored) {
        allowed(target);
        return jdbc.update("UPDATE " + target.table() + " SET " + target.column() + " = ? WHERE " + target.idColumn() + " = ? AND " + target.column() + " = ?", newStored, id, expectedStored);
    }

    // ── dates ──

    /** Id and the date (as text) of the rows whose date is still plain. */
    public List<Map<String, Object>> findPlainDates(DateColumn target) {
        allowed(target);
        return jdbc.queryForList("SELECT " + target.idColumn() + " AS id, " + target.dateColumn() + "::text AS stored FROM " + target.table()
                + " WHERE " + target.dateColumn() + " IS NOT NULL AND " + target.encryptedColumn() + " IS NULL");
    }

    public int moveDateIfUnchanged(DateColumn target, Object id, String expectedDate, String encrypted) {
        allowed(target);
        return jdbc.update("UPDATE " + target.table() + " SET " + target.encryptedColumn() + " = ?, " + target.dateColumn() + " = NULL WHERE " + target.idColumn() + " = ? AND "
                + target.dateColumn() + "::text = ? AND " + target.encryptedColumn() + " IS NULL", encrypted, id, expectedDate);
    }

    // ── salary structures ──

    /** Id and the four plain amounts of the structures not yet encrypted. */
    public List<Map<String, Object>> findPlainSalaryStructures() {
        return jdbc.queryForList("SELECT id, basic, hra, lta, special FROM salary_structures WHERE amounts_enc IS NULL AND basic IS NOT NULL AND hra IS NOT NULL AND lta IS NOT NULL AND special IS NOT NULL");
    }

    public int moveSalaryStructureIfUnchanged(Object id, long basic, long hra, long lta, long special, String encrypted) {
        return jdbc.update("UPDATE salary_structures SET amounts_enc = ?, basic = NULL, hra = NULL, lta = NULL, special = NULL WHERE id = ? AND amounts_enc IS NULL AND basic = ? AND hra = ? AND lta = ? AND special = ?",
                encrypted, id, basic, hra, lta, special);
    }

    // ── payslip snapshots ──

    public List<Map<String, Object>> findPlainPayslips() {
        return jdbc.queryForList("SELECT run_id, employee_id, snapshot::text AS stored FROM payslips WHERE snapshot_enc IS NULL AND snapshot IS NOT NULL");
    }

    public int movePayslipIfUnchanged(Object runId, Object employeeId, String expectedSnapshot, String encrypted) {
        return jdbc.update("UPDATE payslips SET snapshot_enc = ?, snapshot = NULL WHERE run_id = ? AND employee_id = ? AND snapshot_enc IS NULL AND snapshot::text = ?",
                encrypted, runId, employeeId, expectedSnapshot);
    }
}
