package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Modules the super admin grants to a business on their own, independent of its plan: complimentary (free, optionally
 * open-ended) or paid (price and an end date). Revoking keeps the row for the record. Runs in system mode.
 */
@Service
public class ModuleGrantService {

    public static final List<String> SOURCES = List.of("COMPLIMENTARY", "PAID");

    /** {@code state}: ACTIVE (covers today), UPCOMING, ENDED or REVOKED. */
    public record Grant(String id, String module, String source, long pricePaise, String startsOn, String endsOn, String notes, String createdAt,
                        String revokedAt, String state) {}

    public record GrantInput(String module, String source, Double price, String startsOn, String endsOn, String notes) {}

    private final JdbcTemplate jdbc;

    public ModuleGrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Grant> list(UUID businessId) {
        LocalDate today = SubscriptionService.today();
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

    record Valid(String module, String source, long pricePaise, LocalDate starts, LocalDate ends, String notes) {}

    Valid validate(GrantInput in, UUID businessId, UUID editing) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Grant details are missing.");
        String module = in.module() == null || in.module().isBlank() ? "JURIS_TRACK" : Check.oneOf(in.module(), SubscriptionService.MODULES);
        String source = Check.oneOf(in.source(), SOURCES);
        LocalDate starts = LocalDate.parse(Check.isoDate(in.startsOn() == null || in.startsOn().isBlank() ? SubscriptionService.today().toString() : in.startsOn(),
                "Pick a start date."));
        LocalDate ends = in.endsOn() == null || in.endsOn().isBlank() ? null : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends != null && ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        long price = 0;
        if ("PAID".equals(source)) {
            if (in.price() == null || in.price() <= 0) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the price paid (more than ₹0).");
            if (in.price() > 100_000_000 || in.price() * 100 != Math.rint(in.price() * 100)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid price in rupees.");
            if (ends == null) throw new UserException(HttpStatus.BAD_REQUEST, "A paid grant needs an end date.");
            price = Math.round(in.price() * 100);
        }
        String notes = in.notes() == null ? null : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        Boolean overlaps = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM business_modules WHERE business_id = ? AND module = ? AND revoked_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid)
              AND starts_on <= COALESCE(?::date, 'infinity'::date) AND COALESCE(ends_on, 'infinity'::date) >= ?::date)""",
                Boolean.class, businessId, module, editing, editing, ends == null ? null : Date.valueOf(ends), Date.valueOf(starts));
        if (Boolean.TRUE.equals(overlaps)) throw new UserException("This business already has a grant for these dates. Change or revoke that one first.");
        return new Valid(module, source, price, starts, ends, notes == null || notes.isEmpty() ? null : notes);
    }

    public String grant(UUID businessId, GrantInput in, UUID by) {
        if (jdbc.queryForList("SELECT 1 FROM businesses WHERE id = ? AND NOT is_platform", businessId).isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        Valid v = validate(in, businessId, null);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO business_modules (id, business_id, module, source, price_paise, starts_on, ends_on, notes, granted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, businessId, v.module(), v.source(), v.pricePaise(), Date.valueOf(v.starts()), v.ends() == null ? null : Date.valueOf(v.ends()), v.notes(), by);
        return id.toString();
    }

    /** Changes a grant that hasn't been revoked. Returns its business. */
    public UUID update(String idRaw, GrantInput in) {
        UUID id = Check.uuid(idRaw);
        UUID business = existing(id);
        Valid v = validate(in, business, id);
        jdbc.update("UPDATE business_modules SET module = ?, source = ?, price_paise = ?, starts_on = ?, ends_on = ?, notes = ? WHERE id = ?",
                v.module(), v.source(), v.pricePaise(), Date.valueOf(v.starts()), v.ends() == null ? null : Date.valueOf(v.ends()), v.notes(), id);
        return business;
    }

    /** Ends a grant at once (kept for the record). Returns its business. */
    public UUID revoke(String idRaw) {
        UUID id = Check.uuid(idRaw);
        UUID business = existing(id);
        jdbc.update("UPDATE business_modules SET revoked_at = now() WHERE id = ?", id);
        return business;
    }

    private UUID existing(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT business_id, revoked_at FROM business_modules WHERE id = ?", id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Grant not found.");
        if (rows.get(0).get("revoked_at") != null) throw new UserException("This grant has already been revoked.");
        return (UUID) rows.get(0).get("business_id");
    }
}
