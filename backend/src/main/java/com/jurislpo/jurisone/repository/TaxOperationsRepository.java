package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.TaxCatalogue;
import com.jurislpo.jurisone.service.TaxOperationsService.ItemView;
import com.jurislpo.jurisone.service.TaxOperationsService.ProofItem;
import com.jurislpo.jurisone.service.TaxOperationsService.ProofRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code TaxOperationsService}: tax profiles, declaration items and proofs. */
@Repository
public class TaxOperationsRepository {

    private final JdbcTemplate jdbc;

    public TaxOperationsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── tax profiles ──

    /** The employee's tax profile for the Declare page (empty when none). */
    public List<Map<String, Object>> declareProfile(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT regime::text AS regime, regime_overridden, metro, parents_senior, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?",
                employeeId, financialYear);
    }

    /** Creates the default (new regime) profile unless one exists. */
    public void ensureProfile(UUID employeeId, String financialYear) {
        jdbc.update("INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at) "
                + "VALUES (?, ?, 'NEW', false, false, false, 0, 0, now()) ON CONFLICT (employee_id, fy) DO NOTHING", employeeId, financialYear);
    }

    /** regime, regime_overridden and landlord_pan of an existing profile. */
    public Map<String, Object> profileRegime(UUID employeeId, String financialYear) {
        return jdbc.queryForMap("SELECT regime::text AS regime, regime_overridden, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    public void updateRegime(String regime, UUID employeeId, String financialYear) {
        jdbc.update("UPDATE tax_profiles SET regime = ?::\"TaxRegime\", updated_at = now() WHERE employee_id = ? AND fy = ?", regime, employeeId, financialYear);
    }

    public void updateProfileDetails(Boolean metro, Boolean parentsSenior, String landlordPan, UUID employeeId, String financialYear) {
        jdbc.update("UPDATE tax_profiles SET metro = ?, parents_senior = ?, landlord_pan = ?, updated_at = now() WHERE employee_id = ? AND fy = ?",
                metro, parentsSenior, landlordPan, employeeId, financialYear);
    }

    /** regime, metro and landlord_pan for the proof drawer (empty when no profile). */
    public List<Map<String, Object>> proofProfile(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT regime::text AS regime, metro, landlord_pan FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    public List<String> regimeOf(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT regime::text FROM tax_profiles WHERE employee_id = ? AND fy = ?", String.class, employeeId, financialYear);
    }

    /** Finance sets the regime (creating the profile if needed) and marks it overridden. */
    public void upsertOverriddenRegime(UUID employeeId, String financialYear, String regime) {
        jdbc.update("""
            INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at)
            VALUES (?, ?, ?::"TaxRegime", true, false, false, 0, 0, now())
            ON CONFLICT (employee_id, fy) DO UPDATE SET regime = EXCLUDED.regime, regime_overridden = true, updated_at = now()""", employeeId, financialYear, regime);
    }

    public List<Map<String, Object>> previousEmployer(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    /** Amounts in paise. */
    public void upsertPreviousEmployer(UUID employeeId, String financialYear, long income, long tds) {
        jdbc.update("""
            INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at)
            VALUES (?, ?, 'NEW', false, false, false, ?, ?, now())
            ON CONFLICT (employee_id, fy) DO UPDATE SET prev_employer_income = EXCLUDED.prev_employer_income, prev_employer_tds = EXCLUDED.prev_employer_tds, updated_at = now()""",
                employeeId, financialYear, income, tds);
    }

    // ── declaration items ──

    /** The employee's declaration items for the Declare page (amounts in rupees). */
    public List<ItemView> itemViews(UUID employeeId, String financialYear) {
        return jdbc.query("SELECT id::text, item_key, amount, status::text, proof_name, reject_reason FROM declaration_items WHERE employee_id = ? AND fy = ?",
                (resultSet, i) -> new ItemView(resultSet.getString(1), resultSet.getString(2), resultSet.getLong(3) / 100.0, resultSet.getString(4), resultSet.getString(5), resultSet.getString(6)), employeeId, financialYear);
    }

    /** id, amount and status of one declared item (empty when none). */
    public List<Map<String, Object>> findItem(UUID employeeId, String financialYear, String itemKey) {
        return jdbc.queryForList("SELECT id, amount, status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ? AND item_key = ?",
                employeeId, financialYear, itemKey);
    }

    /** Saves a declared amount (paise); the item goes back to Draft. */
    public void upsertItemAmount(UUID id, UUID employeeId, String financialYear, String section, String itemKey, long amount) {
        jdbc.update("""
            INSERT INTO declaration_items (id, employee_id, fy, section, item_key, amount, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', now())
            ON CONFLICT (employee_id, fy, item_key) DO UPDATE SET amount = EXCLUDED.amount, status = 'DRAFT', decided_by = NULL, decided_at = NULL,
              reject_reason = NULL, updated_at = now()""", id, employeeId, financialYear, section, itemKey, amount);
    }

    /** Drafts with an amount (id, item_key, amount, proof_path). */
    public List<Map<String, Object>> draftsWithAmount(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT id, item_key, amount, proof_path FROM declaration_items WHERE employee_id = ? AND fy = ? AND status = 'DRAFT' AND amount > 0",
                employeeId, financialYear);
    }

    public void markPending(UUID[] ids) {
        jdbc.update("UPDATE declaration_items SET status = 'PENDING', updated_at = now() WHERE id = ANY (?)", (Object) ids);
    }

    /** status of one declared item (empty when none). */
    public List<Map<String, Object>> itemStatus(UUID employeeId, String financialYear, String itemKey) {
        return jdbc.queryForList("SELECT status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ? AND item_key = ?", employeeId, financialYear, itemKey);
    }

    /** Attaches a proof file to an item (creating it at ₹0); {@code reopen} sends a decided item back to Draft. */
    public void upsertItemProof(UUID id, UUID employeeId, String financialYear, String section, String itemKey, String proofPath, String proofName, boolean reopen) {
        jdbc.update("""
            INSERT INTO declaration_items (id, employee_id, fy, section, item_key, amount, status, proof_path, proof_name, updated_at)
            VALUES (?, ?, ?, ?, ?, 0, 'DRAFT', ?, ?, now())
            ON CONFLICT (employee_id, fy, item_key) DO UPDATE SET proof_path = EXCLUDED.proof_path, proof_name = EXCLUDED.proof_name, updated_at = now()"""
                + (reopen ? ", status = 'DRAFT', decided_by = NULL, decided_at = NULL, reject_reason = NULL" : ""),
                id, employeeId, financialYear, section, itemKey, proofPath, proofName);
    }

    /** employee_id, proof_path and proof_name of an item (empty when not found). */
    public List<Map<String, Object>> proofOf(String id) {
        return jdbc.queryForList("SELECT employee_id, proof_path, proof_name FROM declaration_items WHERE id = ?::uuid", id);
    }

    // ── proofs (Finance) ──

    /** One row per employee with declarations or proofs this FY. */
    public List<ProofRow> proofRows(String financialYear) {
        return jdbc.query("""
            SELECT e.id::text, e.full_name, e.code, d.name AS department_name, COALESCE((SELECT t.regime::text FROM tax_profiles t WHERE t.employee_id = e.id AND t.fy = ?), 'NEW') AS regime,
                   (SELECT COALESCE(sum(i.amount), 0) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ?) AS declared_amount,
                   (SELECT count(*) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND i.status = 'PENDING') AS pending_count,
                   (SELECT count(*) FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND i.status = 'APPROVED') AS approved_count
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE EXISTS (SELECT 1 FROM declaration_items i WHERE i.employee_id = e.id AND i.fy = ? AND (i.amount > 0 OR i.proof_path IS NOT NULL))""",
                (resultSet, i) -> new ProofRow(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4) == null ? "—" : resultSet.getString(4), resultSet.getString(5),
                        resultSet.getLong(6), resultSet.getInt(7), resultSet.getInt(8)), financialYear, financialYear, financialYear, financialYear, financialYear);
    }

    public Map<String, Object> employeeNameCode(UUID employeeId) {
        return jdbc.queryForMap("SELECT full_name, code FROM employees WHERE id = ?", employeeId);
    }

    /** Items with an amount or a proof, by section. */
    public List<ProofItem> proofItems(UUID employeeId, String financialYear) {
        return jdbc.query("""
            SELECT id::text, item_key, section, amount, status::text, proof_name, proof_path, reject_reason FROM declaration_items
            WHERE employee_id = ? AND fy = ? AND (amount > 0 OR proof_path IS NOT NULL) ORDER BY section ASC""",
                (resultSet, i) -> new ProofItem(resultSet.getString(1), resultSet.getString(2), TaxCatalogue.ITEM_LABEL.getOrDefault(resultSet.getString(2), resultSet.getString(2)),
                        TaxCatalogue.SECTION_OF_ITEM.getOrDefault(resultSet.getString(2), resultSet.getString(3)), resultSet.getLong(4), resultSet.getString(5), resultSet.getString(6),
                        resultSet.getString(7) != null, resultSet.getString(8)), employeeId, financialYear);
    }

    /** employee_id, item_key and amount of an item (empty when not found). */
    public List<Map<String, Object>> itemForDecision(UUID id) {
        return jdbc.queryForList("SELECT employee_id, item_key, amount FROM declaration_items WHERE id = ?", id);
    }

    /** Approves or rejects a pending item; returns 0 when it was already decided. */
    public int decideItem(String status, UUID decidedBy, String rejectReason, UUID id) {
        return jdbc.update("UPDATE declaration_items SET status = ?::\"DeclarationStatus\", decided_by = ?, decided_at = now(), reject_reason = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, rejectReason, id);
    }

    /** id, item_key and amount of the employee's pending items. */
    public List<Map<String, Object>> pendingItems(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT id, item_key, amount FROM declaration_items WHERE employee_id = ? AND fy = ? AND status = 'PENDING'", employeeId, financialYear);
    }

    public void approvePending(UUID decidedBy, UUID[] ids) {
        jdbc.update("UPDATE declaration_items SET status = 'APPROVED', decided_by = ?, decided_at = now(), reject_reason = NULL, updated_at = now() WHERE id = ANY (?) AND status = 'PENDING'",
                decidedBy, ids);
    }
}
