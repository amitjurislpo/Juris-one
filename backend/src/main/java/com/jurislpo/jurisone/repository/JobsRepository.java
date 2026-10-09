package com.jurislpo.jurisone.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Statements of the scheduled jobs: the run lock, job_runs, employee status, leave accrual / expiry. */
@Repository
public class JobsRepository {

    private final JdbcTemplate jdbc;

    public JobsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A comp-off ledger row ({@code expiresOn} only read for credits). */
    public record CompOffRow(UUID id, UUID employeeId, double delta, String reason, String refId, LocalDate expiresOn) {}

    /**
     * Runs {@code body} while holding the session advisory lock {@code lockId}, taken and released on one dedicated
     * connection. Empty when another session holds the lock.
     */
    public <T> Optional<T> withAdvisoryLock(long lockId, Supplier<T> body) {
        javax.sql.DataSource ds = jdbc.getDataSource();
        try (java.sql.Connection lockConn = ds.getConnection()) {
            lockConn.setAutoCommit(true);
            boolean ok;
            try (java.sql.PreparedStatement st = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                st.setLong(1, lockId);
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    ok = rs.next() && rs.getBoolean(1);
                }
            }
            if (!ok) return Optional.empty();
            try {
                return Optional.of(body.get());
            } finally {
                try (java.sql.PreparedStatement st = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    st.setLong(1, lockId);
                    st.execute();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("jobs lock", e);
        }
    }

    /** id and code of every active business, by code. */
    public List<Map<String, Object>> findActiveBusinesses() {
        return jdbc.queryForList("SELECT id, code FROM businesses WHERE status = 'ACTIVE' ORDER BY code");
    }

    public Integer countSubscriptionWarningRuns(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'subscription.warning' AND key = ?", Integer.class, key);
    }

    /** The current business's name. */
    public String currentBusinessName() {
        return jdbc.queryForObject("SELECT name FROM businesses WHERE id = jurisone_tenant_id()", String.class);
    }

    public void insertSubscriptionWarningRun(String key, String detailJson) {
        jdbc.update("INSERT INTO job_runs (id, name, key, ran_at, ok, detail) VALUES (?, 'subscription.warning', ?, now(), true, ?::jsonb)",
                UUID.randomUUID(), key, detailJson);
    }

    public Integer countLockRuns(String month) {
        return jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock' AND key = ?", Integer.class, month);
    }

    public void insertLockRun(String month, String detailJson) {
        jdbc.update("INSERT INTO job_runs (id, name, key, ran_at, ok, detail) VALUES (?, 'attendance.lock', ?, now(), true, ?::jsonb)",
                UUID.randomUUID(), month, detailJson);
    }

    /** id, status and date_of_joining of employees still onboarding or on probation. */
    public List<Map<String, Object>> findOnboardingOrProbation() {
        return jdbc.queryForList("SELECT id, status::text AS status, date_of_joining FROM employees WHERE status IN ('ONBOARDING', 'PROBATION')");
    }

    public void updateEmployeeStatus(Object employeeId, String status) {
        jdbc.update("UPDATE employees SET status = ?::\"EmployeeStatus\", updated_at = now() WHERE id = ?", status, employeeId);
    }

    /** code and accrual_per_month of active leave types that accrue. */
    public List<Map<String, Object>> findAccruingTypes() {
        return jdbc.queryForList("SELECT code, accrual_per_month FROM leave_types WHERE active AND accrual_per_month > 0");
    }

    /** id and date_of_joining of current self-service employees who joined by {@code joinedBy}. */
    public List<Map<String, Object>> findAccrualEmployees(Date joinedBy) {
        return jdbc.queryForList("""
            SELECT e.id, e.date_of_joining FROM employees e
            WHERE EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = e.role_id AND rp.permission_key = 'self')
              AND e.status <> 'EXITED' AND e.date_of_joining <= ?""", joinedBy);
    }

    /** "employeeId|refId" of every accrual already credited. */
    public Set<String> findAccrualKeys() {
        Set<String> done = new HashSet<>();
        jdbc.query("SELECT employee_id, ref_id FROM leave_ledger WHERE reason = 'ACCRUAL' AND ref_id LIKE 'accrual:%'",
                rs -> { done.add(rs.getObject(1) + "|" + rs.getString(2)); });
        return done;
    }

    public void insertAccrual(Object employeeId, Object leaveType, BigDecimal delta, String refId, String note) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, ?, ?, 'ACCRUAL', ?, ?, now())",
                UUID.randomUUID(), employeeId, leaveType, delta, refId, note);
    }

    /** Every comp-off credit. */
    public List<CompOffRow> findCompOffCredits() {
        return jdbc.query("SELECT id, employee_id, delta, reason::text, ref_id, expires_on FROM leave_ledger WHERE leave_type = 'CO' AND reason = 'COMP_OFF_CREDIT'",
                (rs, i) -> new CompOffRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getBigDecimal(3).doubleValue(), rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate()));
    }

    /** The employee's comp-off usage, cancellations, expiries and adjustments ({@code expiresOn} null). */
    public List<CompOffRow> findCompOffDebits(UUID employeeId) {
        return jdbc.query("SELECT id, employee_id, delta, reason::text, ref_id, expires_on FROM leave_ledger WHERE employee_id = ? AND leave_type = 'CO' "
                + "AND reason IN ('USED', 'CANCELLED', 'EXPIRED', 'ADJUSTMENT')",
                (rs, i) -> new CompOffRow((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getBigDecimal(3).doubleValue(), rs.getString(4), rs.getString(5), null), employeeId);
    }

    /** Expires part of a comp-off credit ({@code creditId} is the ledger row it comes from). */
    public void insertCompOffExpiry(UUID employeeId, BigDecimal delta, String creditId) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, 'CO', ?, 'EXPIRED', ?, 'Comp-off expired', now())",
                UUID.randomUUID(), employeeId, delta, creditId);
    }

    public Integer countYearEndRuns(String year) {
        return jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'leave.year_end' AND key = ?", Integer.class, year);
    }

    /** code and carry_forward_cap of active balance-tracked leave types other than comp-off. */
    public List<Map<String, Object>> findYearEndTypes() {
        return jdbc.queryForList("SELECT code, carry_forward_cap FROM leave_types WHERE active AND needs_balance AND code <> 'CO'");
    }

    /** employee_id and balance (s) of the leave type up to {@code end}. */
    public List<Map<String, Object>> sumBalancesUntil(Object leaveType, Timestamp end) {
        return jdbc.queryForList("SELECT employee_id, sum(delta) AS s FROM leave_ledger WHERE leave_type = ? AND at <= ? GROUP BY employee_id",
                leaveType, end);
    }

    public void insertYearEndLapse(Object employeeId, Object leaveType, BigDecimal delta, String refId, String note) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, ?, ?, 'EXPIRED', ?, ?, now())",
                UUID.randomUUID(), employeeId, leaveType, delta, refId, note);
    }

    public void insertYearEndRun(String year, String detailJson) {
        jdbc.update("INSERT INTO job_runs (id, name, key, ran_at, ok, detail) VALUES (?, 'leave.year_end', ?, now(), true, ?::jsonb)",
                UUID.randomUUID(), year, detailJson);
    }
}
