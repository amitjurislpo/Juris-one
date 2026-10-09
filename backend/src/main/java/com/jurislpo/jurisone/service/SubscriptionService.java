package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Subscription plans (the super admin's catalogue) and the subscriptions of businesses. A business can be used only
 * while one of its subscriptions covers today (India time); its plan can also cap the number of employees.
 */
@Service
public class SubscriptionService {

    public static final String ZONE = "Asia/Kolkata";
    public static final List<String> MODULES = List.of("JURIS_TRACK");

    public record Plan(String id, String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, List<String> modules, boolean active,
                       long businesses) {}

    public record Subscription(String id, String planId, String planName, String startsOn, String endsOn, long pricePaise, String status, String notes,
                               String createdAt) {}

    /**
     * The business's subscription today. {@code state}: ACTIVE (covers today), UPCOMING (starts later, none today),
     * EXPIRED (the latest has ended) or NONE (never had one). {@code daysLeft}: whole days until the end date (0 = ends today).
     */
    public record Current(String state, boolean active, String planName, String startsOn, String endsOn, Integer daysLeft, Integer maxEmployees,
                          List<String> modules, long employees) {}

    private final JdbcTemplate jdbc;

    public SubscriptionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static LocalDate today() {
        return Format.todayIn(ZONE);
    }

    static List<String> modules(Array a) throws java.sql.SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    // ───────────────────────── current business ─────────────────────────

    /** Current subscription of the business in {@link TenantContext} (or of {@code businessId} in system mode). */
    public Current current(UUID businessId) {
        UUID b = businessId != null ? businessId : TenantContext.current();
        if (b == null) throw new IllegalStateException("No business");
        LocalDate today = today();
        long employees = jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ? AND status <> 'EXITED'", Long.class, b);
        if (isPlatform(b)) return new Current("ACTIVE", true, "Platform company", null, null, null, null, MODULES, employees); // never needs a plan
        List<Current> now = jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.starts_on <= ? AND s.ends_on >= ? ORDER BY s.ends_on DESC LIMIT 1""",
                (rs, i) -> {
                    LocalDate ends = rs.getDate(3).toLocalDate();
                    return new Current("ACTIVE", true, rs.getString(1), rs.getDate(2).toLocalDate().toString(), ends.toString(),
                            (int) ChronoUnit.DAYS.between(today, ends), (Integer) rs.getObject(4), modules(rs.getArray(5)), employees);
                }, b, Date.valueOf(today), Date.valueOf(today));
        if (!now.isEmpty()) {
            // the plan's modules plus modules granted on their own (complimentary or paid) that cover today
            Current c = now.get(0);
            java.util.TreeSet<String> all = new java.util.TreeSet<>(c.modules());
            all.addAll(grantedModules(b, today));
            return new Current(c.state(), c.active(), c.planName(), c.startsOn(), c.endsOn(), c.daysLeft(), c.maxEmployees(), List.copyOf(all), c.employees());
        }
        List<Current> later = jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.starts_on > ? ORDER BY s.starts_on LIMIT 1""",
                (rs, i) -> new Current("UPCOMING", false, rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getDate(3).toLocalDate().toString(), null,
                        (Integer) rs.getObject(4), modules(rs.getArray(5)), employees), b, Date.valueOf(today));
        if (!later.isEmpty()) return later.get(0);
        List<Current> past = jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.ends_on < ? ORDER BY s.ends_on DESC LIMIT 1""",
                (rs, i) -> new Current("EXPIRED", false, rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getDate(3).toLocalDate().toString(), 0,
                        (Integer) rs.getObject(4), modules(rs.getArray(5)), employees), b, Date.valueOf(today));
        return past.isEmpty() ? new Current("NONE", false, null, null, null, null, null, List.of(), employees) : past.get(0);
    }

    /** Modules granted to the business on their own that cover {@code day} (not revoked). */
    List<String> grantedModules(UUID businessId, LocalDate day) {
        return jdbc.queryForList("""
            SELECT DISTINCT module FROM business_modules
            WHERE business_id = ? AND revoked_at IS NULL AND starts_on <= ? AND (ends_on IS NULL OR ends_on >= ?)""",
                String.class, businessId, Date.valueOf(day), Date.valueOf(day));
    }

    /** Modules the business can use today: none while its subscription isn't active. */
    public java.util.Set<String> activeModules(UUID businessId) {
        if (businessId == null) return java.util.Set.of();
        Current c = current(businessId);
        return c.active() ? java.util.Set.copyOf(c.modules()) : java.util.Set.of();
    }

    /** The JurisOne operator's own business (needs no subscription). */
    boolean isPlatform(UUID businessId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_platform FROM businesses WHERE id = ?", Boolean.class, businessId));
    }

    public boolean isActive(UUID businessId) {
        if (isPlatform(businessId)) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM business_subscriptions WHERE business_id = ? AND status = 'ACTIVE' AND starts_on <= ? AND ends_on >= ?)""",
                Boolean.class, businessId, Date.valueOf(today()), Date.valueOf(today())));
    }

    /** The plan's employee limit: adding (or re-enabling) one more employee must stay within it. */
    public void checkRoomForOneMore() {
        UUID b = TenantContext.current();
        if (b == null) return;
        Current c = current(b);
        if (c.maxEmployees() != null && c.employees() + 1 > c.maxEmployees()) {
            throw new UserException("Your plan (" + c.planName() + ") allows up to " + c.maxEmployees() + " employees. Contact JurisOne to upgrade.");
        }
    }

    public List<Subscription> history(UUID businessId) {
        return jdbc.query("""
            SELECT s.id::text, s.plan_id::text, p.name, s.starts_on, s.ends_on, s.price_paise, s.status, s.notes, s.created_at
            FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id WHERE s.business_id = ? ORDER BY s.starts_on DESC, s.created_at DESC""",
                (rs, i) -> new Subscription(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDate(4).toLocalDate().toString(), rs.getDate(5).toLocalDate().toString(),
                        rs.getLong(6), rs.getString(7), rs.getString(8), Format.jsIso(rs.getTimestamp(9).toInstant())), businessId);
    }

    // ───────────────────────── plans (super admin, system mode) ─────────────────────────

    public List<Plan> plans() {
        return jdbc.query("""
            SELECT p.id::text, p.name, p.description, p.price_paise, p.period_months, p.max_employees, p.modules, p.active,
                   (SELECT count(DISTINCT s.business_id) FROM business_subscriptions s WHERE s.plan_id = p.id AND s.status = 'ACTIVE' AND s.ends_on >= ?) AS businesses
            FROM subscription_plans p ORDER BY p.active DESC, p.price_paise, p.name""",
                (rs, i) -> new Plan(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getInt(5), (Integer) rs.getObject(6), modules(rs.getArray(7)),
                        rs.getBoolean(8), rs.getLong(9)), Date.valueOf(today()));
    }

    public record PlanInput(String name, String description, Double price, Integer periodMonths, Integer maxEmployees, List<String> modules, Boolean active) {}

    record ValidPlan(String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, String[] modules, boolean active) {}

    static ValidPlan validate(PlanInput in) {
        String name = Check.trimmed(in.name(), 2, "Give the plan a name.", 60, "Keep the name under 60 characters.");
        String desc = in.description() == null ? null : in.description().trim();
        if (desc != null && desc.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 300 characters.");
        if (in.price() == null || in.price() < 0 || in.price() > 100_000_000) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a price in rupees (0 or more).");
        if (in.price() * 100 != Math.rint(in.price() * 100)) throw new UserException(HttpStatus.BAD_REQUEST, "Price can have at most 2 decimals.");
        if (in.periodMonths() == null || in.periodMonths() < 1 || in.periodMonths() > 60) throw new UserException(HttpStatus.BAD_REQUEST, "The period is 1 to 60 months.");
        if (in.maxEmployees() != null && (in.maxEmployees() < 1 || in.maxEmployees() > 100_000)) {
            throw new UserException(HttpStatus.BAD_REQUEST, "The employee limit is 1 to 100,000 (or empty for no limit).");
        }
        List<String> mods = in.modules() == null ? List.of() : in.modules();
        for (String m : mods) if (!MODULES.contains(m)) throw new UserException(HttpStatus.BAD_REQUEST, "Unknown module " + m + ".");
        return new ValidPlan(name, desc == null || desc.isEmpty() ? null : desc, Math.round(in.price() * 100), in.periodMonths(), in.maxEmployees(),
                mods.stream().distinct().toArray(String[]::new), in.active() == null || in.active());
    }

    public String createPlan(PlanInput in) {
        ValidPlan p = validate(in);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO subscription_plans (id, name, description, price_paise, period_months, max_employees, modules, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, p.name());
                    ps.setString(3, p.description());
                    ps.setLong(4, p.pricePaise());
                    ps.setInt(5, p.periodMonths());
                    ps.setObject(6, p.maxEmployees());
                    ps.setArray(7, ps.getConnection().createArrayOf("text", p.modules()));
                    ps.setBoolean(8, p.active());
                });
        return id.toString();
    }

    public void updatePlan(String idRaw, PlanInput in) {
        UUID id = Check.uuid(idRaw);
        ValidPlan p = validate(in);
        int n = jdbc.update("""
            UPDATE subscription_plans SET name = ?, description = ?, price_paise = ?, period_months = ?, max_employees = ?, modules = ?, active = ?, updated_at = now()
            WHERE id = ?""",
                ps -> {
                    ps.setString(1, p.name());
                    ps.setString(2, p.description());
                    ps.setLong(3, p.pricePaise());
                    ps.setInt(4, p.periodMonths());
                    ps.setObject(5, p.maxEmployees());
                    ps.setArray(6, ps.getConnection().createArrayOf("text", p.modules()));
                    ps.setBoolean(7, p.active());
                    ps.setObject(8, id);
                });
        if (n == 0) throw new UserException(HttpStatus.NOT_FOUND, "Plan not found.");
    }

    // ───────────────────────── subscriptions (super admin, system mode) ─────────────────────────

    public record SubscriptionInput(String planId, String startsOn, String endsOn, Double price, String notes) {}

    /** Assigns / renews a plan for a business. End date defaults to the plan's period; price defaults to the plan's price. */
    public String addSubscription(UUID businessId, SubscriptionInput in, UUID by) {
        if (isPlatform(businessId)) throw new UserException(HttpStatus.NOT_FOUND, "Business not found.");
        UUID planId = Check.uuid(in.planId(), "Choose a plan.");
        List<Map<String, Object>> plan = jdbc.queryForList("SELECT period_months, price_paise, active FROM subscription_plans WHERE id = ?", planId);
        if (plan.isEmpty()) throw new UserException("Plan not found.");
        if (!Boolean.TRUE.equals(plan.get(0).get("active"))) throw new UserException("That plan is no longer offered.");
        LocalDate starts = LocalDate.parse(Check.isoDate(in.startsOn() == null || in.startsOn().isBlank() ? today().toString() : in.startsOn(), "Pick a start date."));
        int months = ((Number) plan.get(0).get("period_months")).intValue();
        LocalDate ends = in.endsOn() == null || in.endsOn().isBlank() ? starts.plusMonths(months).minusDays(1)
                : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        long price = in.price() == null ? ((Number) plan.get(0).get("price_paise")).longValue() : Math.round(in.price() * 100);
        if (price < 0) throw new UserException(HttpStatus.BAD_REQUEST, "The price can't be negative.");
        String notes = in.notes() == null ? null : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, businessId, planId, Date.valueOf(starts), Date.valueOf(ends), price, notes == null || notes.isEmpty() ? null : notes, by);
        return id.toString();
    }

    public record SubscriptionUpdate(String startsOn, String endsOn, String status, String notes) {}

    /** Changes dates / notes, or cancels a subscription (cancelled ones no longer give access). */
    public UUID updateSubscription(String idRaw, SubscriptionUpdate in) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT business_id, starts_on, ends_on, status, notes FROM business_subscriptions WHERE id = ?", id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Subscription not found.");
        Map<String, Object> r = rows.get(0);
        LocalDate starts = in.startsOn() == null ? ((Date) r.get("starts_on")).toLocalDate() : LocalDate.parse(Check.isoDate(in.startsOn(), "Pick a start date."));
        LocalDate ends = in.endsOn() == null ? ((Date) r.get("ends_on")).toLocalDate() : LocalDate.parse(Check.isoDate(in.endsOn(), "Pick an end date."));
        if (ends.isBefore(starts)) throw new UserException("The end date can't be before the start date.");
        String status = in.status() == null ? (String) r.get("status") : Check.oneOf(in.status(), List.of("ACTIVE", "CANCELLED"));
        String notes = in.notes() == null ? (String) r.get("notes") : in.notes().trim();
        if (notes != null && notes.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the notes under 500 characters.");
        jdbc.update("""
            UPDATE business_subscriptions SET starts_on = ?, ends_on = ?, status = ?, notes = ?,
              cancelled_at = CASE WHEN ? = 'CANCELLED' THEN COALESCE(cancelled_at, now()) ELSE NULL END WHERE id = ?""",
                Date.valueOf(starts), Date.valueOf(ends), status, notes == null || notes.isEmpty() ? null : notes, status, id);
        return (UUID) r.get("business_id");
    }

    /** Owners of the current business who should hear about the subscription (portal access on). */
    public List<Map<String, Object>> owners() {
        return new ArrayList<>(jdbc.queryForList("""
            SELECT e.id, e.full_name, e.email FROM employees e JOIN roles r ON r.id = e.role_id
            WHERE r.key = 'owner' AND e.status <> 'EXITED' AND e.portal_access ORDER BY e.full_name"""));
    }
}
