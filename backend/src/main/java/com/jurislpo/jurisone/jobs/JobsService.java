package com.jurislpo.jurisone.jobs;

import com.jurislpo.jurisone.attendance.AttendanceService;
import com.jurislpo.jurisone.attendance.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.engine.Num;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.platform.SettingsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Scheduled jobs — src/lib/jobs.ts, same order and idempotency (job_runs), each job committing as it goes,
 * run business by business. One run at a time across instances: advisory lock 74202, taken and released on one dedicated connection
 * (the Node app took and released it through a pool, which could leak the lock).
 */
@Service
public class JobsService {

    private static final Logger log = LoggerFactory.getLogger(JobsService.class);
    static final String IST = "Asia/Kolkata";
    static final long LOCK_ID = 74202;

    private final JdbcTemplate jdbc;
    private final AttendanceService att;
    private final SettingsService settings;
    private final AuditService audit;

    public JobsService(JdbcTemplate jdbc, AttendanceService att, SettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.att = att;
        this.settings = settings;
        this.audit = audit;
    }

    static LocalDate today(Instant now) {
        return AttendanceEngine.localParts(now, IST).date();
    }

    /** Runs every job once; returns a summary, or {@code {skipped:true}} when another instance holds the lock. */
    public Map<String, Object> runJobs(Instant now) {
        javax.sql.DataSource ds = jdbc.getDataSource();
        try (java.sql.Connection lockConn = ds.getConnection()) {
            lockConn.setAutoCommit(true);
            boolean ok;
            try (java.sql.PreparedStatement st = lockConn.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                st.setLong(1, LOCK_ID);
                try (java.sql.ResultSet rs = st.executeQuery()) {
                    ok = rs.next() && rs.getBoolean(1);
                }
            }
            if (!ok) return Map.of("skipped", true);
            try {
                // each business with its own policies; rows written are tagged with that business
                Map<String, Object> all = new LinkedHashMap<>();
                List<Map<String, Object>> businesses = TenantContext.call(null,
                        () -> jdbc.queryForList("SELECT id, code FROM businesses WHERE status = 'ACTIVE' ORDER BY code"));
                for (Map<String, Object> b : businesses) {
                    all.put((String) b.get("code"), TenantContext.call((UUID) b.get("id"), () -> runForBusiness(now)));
                }
                return all;
            } finally {
                try (java.sql.PreparedStatement st = lockConn.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    st.setLong(1, LOCK_ID);
                    st.execute();
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("jobs lock", e);
        }
    }

    /** Every job, in order, for the current business. */
    public Map<String, Object> runForBusiness(Instant now) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", jobEmployeeStatus(now));
        r.put("lock", jobLockMonths(now));
        r.put("finalize", jobFinalizeDays(now));
        r.put("yearEnd", jobLeaveYearEnd(now));
        r.put("accrual", jobLeaveAccrual(now));
        r.put("compOff", jobCompOffExpiry(now));
        return r;
    }

    /**
     * Daily absence marking: persists finished days (shift end + buffer passed) for the last week,
     * so a working day with no check-in and no approved leave is recorded as Absent (LOP).
     */
    public Map<String, Object> jobFinalizeDays(Instant now) {
        AttendanceService.Pol p = att.policy();
        List<AttEmployee> emps = att.loadAttEmployees("e.status <> 'EXITED' OR e.exit_date >= ?", Timestamp.from(now.minusSeconds(10L * 86400)));
        LocalDate today = today(now);
        LocalDate from = today.minusDays(7).isAfter(p.trackingStart()) ? today.minusDays(7) : p.trackingStart();
        return Map.of("written", att.materialize(emps, from, today, false, now));
    }

    /** Locks each month at the configured time (default: 1st of next month, 10:00 IST). Idempotent. */
    public Map<String, Object> jobLockMonths(Instant now) {
        AttendanceService.Pol p = att.policy();
        List<String> locked = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(p.trackingStart()); !att.lockInstant(ym.toString(), p).isAfter(now); ym = ym.plusMonths(1)) {
            Integer done = jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock' AND key = ?", Integer.class, ym.toString());
            if (done != null && done > 0) continue;
            LocalDate from = ym.atDay(1).isBefore(p.trackingStart()) ? p.trackingStart() : ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            List<AttEmployee> emps = att.loadAttEmployees("e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)", Date.valueOf(to), Date.valueOf(from));
            int written = att.materialize(emps, from, to, true, now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("employees", emps.size());
            detail.put("rows", written);
            jdbc.update("INSERT INTO job_runs (id, name, key, ran_at, ok, detail) VALUES (?, 'attendance.lock', ?, now(), true, ?::jsonb)",
                    UUID.randomUUID(), ym.toString(), "{\"employees\":" + emps.size() + ",\"rows\":" + written + "}");
            audit.log(null, "attendance.month_locked", "attendance", ym.toString(), null, detail, null);
            locked.add(ym.toString());
        }
        return Map.of("locked", locked);
    }

    /** Onboarding → Probation on the joining date → Active after the probation period. */
    public Map<String, Object> jobEmployeeStatus(Instant now) {
        int months = settings.employee().probationMonths();
        LocalDate today = today(now);
        List<Map<String, Object>> emps = jdbc.queryForList("SELECT id, status::text AS status, date_of_joining FROM employees WHERE status IN ('ONBOARDING', 'PROBATION')");
        int changed = 0;
        for (Map<String, Object> e : emps) {
            EmployeeStatus cur = EmployeeStatus.valueOf((String) e.get("status"));
            EmployeeStatus next = PeopleRules.derivedStatus(cur, ((Date) e.get("date_of_joining")).toLocalDate(), today, months);
            if (next == cur) continue;
            jdbc.update("UPDATE employees SET status = ?::\"EmployeeStatus\", updated_at = now() WHERE id = ?", next.name(), e.get("id"));
            audit.log(null, "employee.status_auto", "employee", e.get("id").toString(), Map.of("status", cur.name()), Map.of("status", next.name()), null);
            changed++;
        }
        return Map.of("changed", changed);
    }

    /**
     * Monthly leave accrual (CL 1, EL 1.5 …) credited at the start of each month of the leave year, pro-rata in the
     * joining month. Backfills any month not yet credited; one ledger row per employee, type and month
     * (refId "accrual:&lt;type&gt;:&lt;yyyy-mm&gt;").
     */
    public Map<String, Object> jobLeaveAccrual(Instant now) {
        String ym = today(now).toString().substring(0, 7);
        String year = ym.substring(0, 4);
        List<Map<String, Object>> types = jdbc.queryForList("SELECT code, accrual_per_month FROM leave_types WHERE active AND accrual_per_month > 0");
        List<Map<String, Object>> emps = jdbc.queryForList("""
            SELECT e.id, e.date_of_joining FROM employees e
            WHERE EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = e.role_id AND rp.permission_key = 'self')
              AND e.status <> 'EXITED' AND e.date_of_joining <= ?""", Date.valueOf(YearMonth.parse(ym).atEndOfMonth()));
        Set<String> done = new HashSet<>();
        jdbc.query("SELECT employee_id, ref_id FROM leave_ledger WHERE reason = 'ACCRUAL' AND ref_id LIKE 'accrual:%'",
                rs -> { done.add(rs.getObject(1) + "|" + rs.getString(2)); });
        int credited = 0;
        for (Map<String, Object> e : emps) {
            LocalDate doj = ((Date) e.get("date_of_joining")).toLocalDate();
            for (int m = 1; m <= Integer.parseInt(ym.substring(5)); m++) {
                String month = year + "-" + String.format(java.util.Locale.ROOT, "%02d", m);
                for (Map<String, Object> t : types) {
                    String refId = "accrual:" + t.get("code") + ":" + month;
                    if (done.contains(e.get("id") + "|" + refId)) continue;
                    double amount = LeaveRules.accrualFor(YearMonth.parse(month), doj, ((BigDecimal) t.get("accrual_per_month")).doubleValue());
                    if (amount > 0) {
                        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, ?, ?, 'ACCRUAL', ?, ?, now())",
                                UUID.randomUUID(), e.get("id"), t.get("code"), BigDecimal.valueOf(amount), refId, "Accrual " + month);
                        credited++;
                    }
                }
            }
        }
        return Map.of("credited", credited);
    }

    /** Comp-offs expire 60 days after the work date; usage comes out of the oldest credits first. */
    public Map<String, Object> jobCompOffExpiry(Instant now) {
        LocalDate today = today(now);
        record Row(UUID id, UUID employeeId, double delta, String reason, String refId, LocalDate expiresOn) {}
        List<Row> credits = jdbc.query("SELECT id, employee_id, delta, reason::text, ref_id, expires_on FROM leave_ledger WHERE leave_type = 'CO' AND reason = 'COMP_OFF_CREDIT'",
                (rs, i) -> new Row((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getBigDecimal(3).doubleValue(), rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate()));
        Map<UUID, List<Row>> byEmp = new LinkedHashMap<>();
        for (Row c : credits) byEmp.computeIfAbsent(c.employeeId(), k -> new ArrayList<>()).add(c);
        int expired = 0;
        for (Map.Entry<UUID, List<Row>> en : byEmp.entrySet()) {
            List<Row> rows = jdbc.query("SELECT id, employee_id, delta, reason::text, ref_id, expires_on FROM leave_ledger WHERE employee_id = ? AND leave_type = 'CO' "
                    + "AND reason IN ('USED', 'CANCELLED', 'EXPIRED', 'ADJUSTMENT')",
                    (rs, i) -> new Row((UUID) rs.getObject(1), (UUID) rs.getObject(2), rs.getBigDecimal(3).doubleValue(), rs.getString(4), rs.getString(5), null), en.getKey());
            double used = -rows.stream().filter(r -> !"EXPIRED".equals(r.reason())).mapToDouble(Row::delta).sum();
            List<LeaveRules.Credit> cs = new ArrayList<>();
            for (Row c : en.getValue()) {
                double exp = -rows.stream().filter(r -> "EXPIRED".equals(r.reason()) && c.id().toString().equals(r.refId())).mapToDouble(Row::delta).sum();
                cs.add(new LeaveRules.Credit(c.id().toString(), c.delta(), c.expiresOn() != null ? c.expiresOn() : LocalDate.of(9999, 12, 31), exp));
            }
            for (LeaveRules.Expiry x : LeaveRules.compOffExpiries(cs, used, today)) {
                jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, 'CO', ?, 'EXPIRED', ?, 'Comp-off expired', now())",
                        UUID.randomUUID(), en.getKey(), BigDecimal.valueOf(-x.amount()), x.id());
                expired++;
            }
        }
        return Map.of("expired", expired);
    }

    /** On 1 January: casual leave lapses, earned leave carries forward up to its cap. Once per year. */
    public Map<String, Object> jobLeaveYearEnd(Instant now) {
        String prev = String.valueOf(today(now).getYear() - 1);
        Integer ran = jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'leave.year_end' AND key = ?", Integer.class, prev);
        if (ran != null && ran > 0) return Map.of("lapsed", 0);
        String firstYear = String.valueOf(att.policy().trackingStart().getYear());
        if (prev.compareTo(firstYear) < 0) return Map.of("lapsed", 0);
        List<Map<String, Object>> types = jdbc.queryForList("SELECT code, carry_forward_cap FROM leave_types WHERE active AND needs_balance AND code <> 'CO'");
        Timestamp end = Timestamp.from(LocalDate.parse(prev + "-12-31").atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
        int lapsed = 0;
        for (Map<String, Object> t : types) {
            BigDecimal cap = (BigDecimal) t.get("carry_forward_cap");
            List<Map<String, Object>> sums = jdbc.queryForList("SELECT employee_id, sum(delta) AS s FROM leave_ledger WHERE leave_type = ? AND at <= ? GROUP BY employee_id",
                    t.get("code"), end);
            for (Map<String, Object> s : sums) {
                BigDecimal sum = (BigDecimal) s.get("s");
                double amount = LeaveRules.yearEndLapse(Num.round2(sum == null ? 0 : sum.doubleValue()), cap.doubleValue());
                if (amount > 0) {
                    // Prisma Decimal.toString(): "0" for zero
                    String note = cap.compareTo(BigDecimal.ZERO) == 0 ? "Lapsed at year end" : "Above carry-forward cap";
                    jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, ?, ?, 'EXPIRED', ?, ?, now())",
                            UUID.randomUUID(), s.get("employee_id"), t.get("code"), BigDecimal.valueOf(-amount), "year_end:" + prev, note);
                    lapsed++;
                }
            }
        }
        jdbc.update("INSERT INTO job_runs (id, name, key, ran_at, ok, detail) VALUES (?, 'leave.year_end', ?, now(), true, ?::jsonb)",
                UUID.randomUUID(), prev, "{\"lapsed\":" + lapsed + "}");
        return Map.of("lapsed", lapsed);
    }

    void logSummary(Map<String, Object> r) {
        if (r.containsKey("skipped")) return;
        @SuppressWarnings("unchecked")
        List<String> locked = (List<String>) ((Map<String, Object>) r.get("lock")).get("locked");
        log.info("[jobs] status {} · days {}{}", ((Map<?, ?>) r.get("status")).get("changed"), ((Map<?, ?>) r.get("finalize")).get("written"),
                locked.isEmpty() ? "" : " · locked " + String.join(", ", locked));
    }
}
