package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.ModuleGrantService.Grant;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Modules granted to a business outside its plan ({@code business_modules}). */
@Repository
public class ModuleGrantRepository {

    private final JdbcTemplate jdbc;

    public ModuleGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The business's grants, unrevoked first, newest start first; each one's state is judged against {@code today}. */
    public List<Grant> findByBusiness(UUID businessId, LocalDate today) {
        return jdbc.query("""
            SELECT id::text, module, source, price_paise, starts_on, ends_on, notes, created_at, revoked_at
            FROM business_modules WHERE business_id = ? ORDER BY revoked_at IS NOT NULL, starts_on DESC, created_at DESC""",
                (rs, i) -> {
                    LocalDate starts = rs.getDate(5).toLocalDate();
                    LocalDate ends = rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate();
                    String state = rs.getTimestamp(9) != null ? "REVOKED" : starts.isAfter(today) ? "UPCOMING" : ends != null && ends.isBefore(today) ? "ENDED" : "ACTIVE";
                    return new Grant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), starts.toString(), ends == null ? null : ends.toString(),
                            rs.getString(7), Format.jsIso(rs.getTimestamp(8).toInstant()),
                            rs.getTimestamp(9) == null ? null : Format.jsIso(rs.getTimestamp(9).toInstant()), state);
                }, businessId);
    }

    /** Whether another unrevoked grant of the module overlaps the dates ({@code editing}: the grant being changed, or null). */
    public Boolean overlaps(UUID businessId, String module, UUID editing, LocalDate starts, LocalDate ends) {
        return jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM business_modules WHERE business_id = ? AND module = ? AND revoked_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid)
              AND starts_on <= COALESCE(?::date, 'infinity'::date) AND COALESCE(ends_on, 'infinity'::date) >= ?::date)""",
                Boolean.class, businessId, module, editing, editing, ends == null ? null : Date.valueOf(ends), Date.valueOf(starts));
    }

    /** Whether a customer (non-platform) business with this id exists. */
    public boolean customerBusinessExists(UUID businessId) {
        return !jdbc.queryForList("SELECT 1 FROM businesses WHERE id = ? AND NOT is_platform", businessId).isEmpty();
    }

    public void insert(UUID id, UUID businessId, String module, String source, long pricePaise, LocalDate starts, LocalDate ends, String notes, UUID by) {
        jdbc.update("""
            INSERT INTO business_modules (id, business_id, module, source, price_paise, starts_on, ends_on, notes, granted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, businessId, module, source, pricePaise, Date.valueOf(starts), ends == null ? null : Date.valueOf(ends), notes, by);
    }

    public void update(UUID id, String module, String source, long pricePaise, LocalDate starts, LocalDate ends, String notes) {
        jdbc.update("UPDATE business_modules SET module = ?, source = ?, price_paise = ?, starts_on = ?, ends_on = ?, notes = ? WHERE id = ?",
                module, source, pricePaise, Date.valueOf(starts), ends == null ? null : Date.valueOf(ends), notes, id);
    }

    public void revoke(UUID id) {
        jdbc.update("UPDATE business_modules SET revoked_at = now() WHERE id = ?", id);
    }

    /** business_id and revoked_at of a grant (zero or one row). */
    public List<Map<String, Object>> findState(UUID id) {
        return jdbc.queryForList("SELECT business_id, revoked_at FROM business_modules WHERE id = ?", id);
    }
}
