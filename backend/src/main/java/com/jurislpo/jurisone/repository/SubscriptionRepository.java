package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.SubscriptionService.Current;
import com.jurislpo.jurisone.service.SubscriptionService.Plan;
import com.jurislpo.jurisone.service.SubscriptionService.Subscription;
import java.sql.Array;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Subscription plans ({@code subscription_plans}) and the subscriptions of businesses ({@code business_subscriptions}). */
@Repository
public class SubscriptionRepository {

    private final JdbcTemplate jdbc;

    public SubscriptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static List<String> modules(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    // ── current business ──

    /** Employees of the business who haven't exited. */
    public long countActiveEmployees(UUID businessId) {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ? AND status <> 'EXITED'", Long.class, businessId);
    }

    /** The active subscription covering {@code today} that ends last (zero or one), as ACTIVE. */
    public List<Current> findCovering(UUID businessId, LocalDate today, long employees) {
        return jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.starts_on <= ? AND s.ends_on >= ? ORDER BY s.ends_on DESC LIMIT 1""",
                (resultSet, i) -> {
                    LocalDate ends = resultSet.getDate(3).toLocalDate();
                    return new Current("ACTIVE", true, resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), ends.toString(),
                            (int) ChronoUnit.DAYS.between(today, ends), (Integer) resultSet.getObject(4), modules(resultSet.getArray(5)), employees);
                }, businessId, Date.valueOf(today), Date.valueOf(today));
    }

    /** The next active subscription starting after {@code today} (zero or one), as UPCOMING. */
    public List<Current> findUpcoming(UUID businessId, LocalDate today, long employees) {
        return jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.starts_on > ? ORDER BY s.starts_on LIMIT 1""",
                (resultSet, i) -> new Current("UPCOMING", false, resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getDate(3).toLocalDate().toString(), null,
                        (Integer) resultSet.getObject(4), modules(resultSet.getArray(5)), employees), businessId, Date.valueOf(today));
    }

    /** The latest active subscription that ended before {@code today} (zero or one), as EXPIRED. */
    public List<Current> findLastExpired(UUID businessId, LocalDate today, long employees) {
        return jdbc.query("""
            SELECT p.name, s.starts_on, s.ends_on, p.max_employees, p.modules FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.business_id = ? AND s.status = 'ACTIVE' AND s.ends_on < ? ORDER BY s.ends_on DESC LIMIT 1""",
                (resultSet, i) -> new Current("EXPIRED", false, resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getDate(3).toLocalDate().toString(), 0,
                        (Integer) resultSet.getObject(4), modules(resultSet.getArray(5)), employees), businessId, Date.valueOf(today));
    }

    /** Modules granted to the business on their own that cover {@code day} (not revoked). */
    public List<String> findGrantedModules(UUID businessId, LocalDate day) {
        return jdbc.queryForList("""
            SELECT DISTINCT module FROM business_modules
            WHERE business_id = ? AND revoked_at IS NULL AND starts_on <= ? AND (ends_on IS NULL OR ends_on >= ?)""",
                String.class, businessId, Date.valueOf(day), Date.valueOf(day));
    }

    /** The business's is_platform flag; throws when it does not exist. */
    public Boolean isPlatform(UUID businessId) {
        return jdbc.queryForObject("SELECT is_platform FROM businesses WHERE id = ?", Boolean.class, businessId);
    }

    /** Whether an active subscription of the business covers {@code day}. */
    public Boolean hasCovering(UUID businessId, LocalDate day) {
        return jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM business_subscriptions WHERE business_id = ? AND status = 'ACTIVE' AND starts_on <= ? AND ends_on >= ?)""",
                Boolean.class, businessId, Date.valueOf(day), Date.valueOf(day));
    }

    /** Every subscription of the business, newest start first. */
    public List<Subscription> findHistory(UUID businessId) {
        return jdbc.query("""
            SELECT s.id::text, s.plan_id::text, p.name, s.starts_on, s.ends_on, s.price_paise, s.status, s.notes, s.created_at
            FROM business_subscriptions s JOIN subscription_plans p ON p.id = s.plan_id WHERE s.business_id = ? ORDER BY s.starts_on DESC, s.created_at DESC""",
                (resultSet, i) -> new Subscription(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getDate(4).toLocalDate().toString(), resultSet.getDate(5).toLocalDate().toString(),
                        resultSet.getLong(6), resultSet.getString(7), resultSet.getString(8), Format.isoTimestamp(resultSet.getTimestamp(9).toInstant())), businessId);
    }

    // ── plans ──

    /** Every plan with the number of businesses on it whose active subscription hasn't ended by {@code today}. */
    public List<Plan> findPlans(LocalDate today) {
        return jdbc.query("""
            SELECT p.id::text, p.name, p.description, p.price_paise, p.period_months, p.max_employees, p.modules, p.active,
                   (SELECT count(DISTINCT s.business_id) FROM business_subscriptions s WHERE s.plan_id = p.id AND s.status = 'ACTIVE' AND s.ends_on >= ?) AS businesses
            FROM subscription_plans p ORDER BY p.active DESC, p.price_paise, p.name""",
                (resultSet, i) -> new Plan(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getLong(4), resultSet.getInt(5), (Integer) resultSet.getObject(6), modules(resultSet.getArray(7)),
                        resultSet.getBoolean(8), resultSet.getLong(9)), Date.valueOf(today));
    }

    public void insertPlan(UUID id, String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, String[] modules, boolean active) {
        jdbc.update("""
            INSERT INTO subscription_plans (id, name, description, price_paise, period_months, max_employees, modules, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                statement -> {
                    statement.setObject(1, id);
                    statement.setString(2, name);
                    statement.setString(3, description);
                    statement.setLong(4, pricePaise);
                    statement.setInt(5, periodMonths);
                    statement.setObject(6, maxEmployees);
                    statement.setArray(7, statement.getConnection().createArrayOf("text", modules));
                    statement.setBoolean(8, active);
                });
    }

    /** Returns the number of rows changed (0 = no such plan). */
    public int updatePlan(UUID id, String name, String description, long pricePaise, int periodMonths, Integer maxEmployees, String[] modules, boolean active) {
        return jdbc.update("""
            UPDATE subscription_plans SET name = ?, description = ?, price_paise = ?, period_months = ?, max_employees = ?, modules = ?, active = ?, updated_at = now()
            WHERE id = ?""",
                statement -> {
                    statement.setString(1, name);
                    statement.setString(2, description);
                    statement.setLong(3, pricePaise);
                    statement.setInt(4, periodMonths);
                    statement.setObject(5, maxEmployees);
                    statement.setArray(6, statement.getConnection().createArrayOf("text", modules));
                    statement.setBoolean(7, active);
                    statement.setObject(8, id);
                });
    }

    // ── subscriptions ──

    /** period_months, price_paise and active of a plan (zero or one row). */
    public List<Map<String, Object>> findPlanTerms(UUID planId) {
        return jdbc.queryForList("SELECT period_months, price_paise, active FROM subscription_plans WHERE id = ?", planId);
    }

    public void insertSubscription(UUID id, UUID businessId, UUID planId, LocalDate starts, LocalDate ends, long pricePaise, String notes, UUID by) {
        jdbc.update("""
            INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise, notes, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, businessId, planId, Date.valueOf(starts), Date.valueOf(ends), pricePaise, notes, by);
    }

    /** business_id, starts_on, ends_on, status and notes of a subscription (zero or one row). */
    public List<Map<String, Object>> findSubscription(UUID id) {
        return jdbc.queryForList("SELECT business_id, starts_on, ends_on, status, notes FROM business_subscriptions WHERE id = ?", id);
    }

    /** Sets dates, status and notes; {@code cancelled_at} is set once on cancelling and cleared otherwise. */
    public void updateSubscription(UUID id, LocalDate starts, LocalDate ends, String status, String notes) {
        jdbc.update("""
            UPDATE business_subscriptions SET starts_on = ?, ends_on = ?, status = ?, notes = ?,
              cancelled_at = CASE WHEN ? = 'CANCELLED' THEN COALESCE(cancelled_at, now()) ELSE NULL END WHERE id = ?""",
                Date.valueOf(starts), Date.valueOf(ends), status, notes, status, id);
    }

    /** Owners of the current business with portal access who haven't exited (id, full_name, email), by name. */
    public List<Map<String, Object>> findOwners() {
        return jdbc.queryForList("""
            SELECT e.id, e.full_name, e.email FROM employees e JOIN roles r ON r.id = e.role_id
            WHERE r.key = 'owner' AND e.status <> 'EXITED' AND e.portal_access ORDER BY e.full_name""");
    }
}
