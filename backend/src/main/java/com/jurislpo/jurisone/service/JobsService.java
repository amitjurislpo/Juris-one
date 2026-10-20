package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.engine.Num;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.repository.JobsRepository;
import com.jurislpo.jurisone.repository.JobsRepository.CompOffRow;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scheduled jobs, same order and idempotency (job_runs), each job committing as it goes,
 * run business by business. One run at a time across instances: advisory lock 74202, taken and released on one dedicated connection
 * (the Node app took and released it through a pool, which could leak the lock).
 */
@Service
public class JobsService {

    private static final Logger log = LoggerFactory.getLogger(JobsService.class);
    static final String IST = "Asia/Kolkata";
    static final long LOCK_ID = 74202;

    private final JobsRepository repo;
    private final AttendanceService att;
    private final SettingsService settings;
    private final AuditService audit;
    private final SubscriptionService subscriptions;
    private final com.jurislpo.jurisone.service.NotificationService notifications;
    private final com.jurislpo.jurisone.service.MailService mail;
    private final String appUrl;

    public JobsService(JobsRepository repo, AttendanceService att, SettingsService settings, AuditService audit, SubscriptionService subscriptions,
                       com.jurislpo.jurisone.service.NotificationService notifications, com.jurislpo.jurisone.service.MailService mail,
                       com.jurislpo.jurisone.config.JurisOneProperties props) {
        this.repo = repo;
        this.att = att;
        this.settings = settings;
        this.audit = audit;
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.mail = mail;
        this.appUrl = props.frontendOrigin();
    }

    static LocalDate today(Instant now) {
        return AttendanceEngine.localParts(now, IST).date();
    }

    /** Runs every job once; returns a summary, or {@code {skipped:true}} when another instance holds the lock. */
    public Map<String, Object> runJobs(Instant now) {
        return repo.withAdvisoryLock(LOCK_ID, () -> {
            // each business with its own policies; rows written are tagged with that business
            Map<String, Object> all = new LinkedHashMap<>();
            List<Map<String, Object>> businesses = TenantContext.call(null, repo::findActiveBusinesses);
            for (Map<String, Object> b : businesses) {
                all.put((String) b.get("code"), TenantContext.call((UUID) b.get("id"), () -> runForBusiness(now)));
            }
            return all;
        }).orElseGet(() -> Map.of("skipped", true));
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
        r.put("subscription", jobSubscriptionWarnings());
        return r;
    }

    /**
     * Tells the owners (bell + email) that the subscription ends in 7 days, ends tomorrow / today, or has ended —
     * each warning once per subscription end date.
     */
    public Map<String, Object> jobSubscriptionWarnings() {
        SubscriptionService.Current c = subscriptions.current(null);
        String kind = null;
        if ("ACTIVE".equals(c.state()) && c.daysLeft() != null) kind = c.daysLeft() <= 1 ? "d1" : c.daysLeft() <= 7 ? "d7" : null;
        else if ("EXPIRED".equals(c.state())) kind = "ended";
        if (kind == null) return Map.of("sent", 0);
        String key = c.endsOn() + ":" + kind;
        Integer done = repo.countSubscriptionWarningRuns(key);
        if (done != null && done > 0) return Map.of("sent", 0);
        int days = "ended".equals(kind) ? 0 : Math.max(1, c.daysLeft());
        String business = repo.currentBusinessName();
        String text = "ended".equals(kind) ? "Your " + c.planName() + " subscription has ended — only the employee export works until it is renewed"
                : "Your " + c.planName() + " subscription ends " + (c.daysLeft() == 0 ? "today" : c.daysLeft() == 1 ? "tomorrow" : "in " + c.daysLeft() + " days")
                        + " (" + c.endsOn() + ") — contact JurisOne to renew";
        List<Map<String, Object>> owners = subscriptions.owners();
        for (Map<String, Object> o : owners) {
            notifications.notify((UUID) o.get("id"), text, "/subscription");
            mail.send((String) o.get("email"), com.jurislpo.jurisone.service.MailTemplates.subscription((String) o.get("full_name"), business, c.planName(),
                    c.endsOn(), days, appUrl + "/subscription"));
        }
        repo.insertSubscriptionWarningRun(key, "{\"owners\":" + owners.size() + "}");
        return Map.of("sent", owners.size(), "kind", kind);
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
            Integer done = repo.countLockRuns(ym.toString());
            if (done != null && done > 0) continue;
            LocalDate from = ym.atDay(1).isBefore(p.trackingStart()) ? p.trackingStart() : ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            List<AttEmployee> emps = att.loadAttEmployees("e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)", Date.valueOf(to), Date.valueOf(from));
            int written = att.materialize(emps, from, to, true, now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("employees", emps.size());
            detail.put("rows", written);
            repo.insertLockRun(ym.toString(), "{\"employees\":" + emps.size() + ",\"rows\":" + written + "}");
            audit.log(null, "attendance.month_locked", "attendance", ym.toString(), null, detail, null);
            locked.add(ym.toString());
        }
        return Map.of("locked", locked);
    }

    /** Onboarding → Probation on the joining date → Active after the probation period. */
    public Map<String, Object> jobEmployeeStatus(Instant now) {
        int months = settings.employee().probationMonths();
        LocalDate today = today(now);
        List<Map<String, Object>> emps = repo.findOnboardingOrProbation();
        int changed = 0;
        for (Map<String, Object> e : emps) {
            EmployeeStatus cur = EmployeeStatus.valueOf((String) e.get("status"));
            EmployeeStatus next = PeopleRules.derivedStatus(cur, ((Date) e.get("date_of_joining")).toLocalDate(), today, months);
            if (next == cur) continue;
            repo.updateEmployeeStatus(e.get("id"), next.name());
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
        List<Map<String, Object>> types = repo.findAccruingTypes();
        List<Map<String, Object>> emps = repo.findAccrualEmployees(Date.valueOf(YearMonth.parse(ym).atEndOfMonth()));
        Set<String> done = repo.findAccrualKeys();
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
                        repo.insertAccrual(e.get("id"), t.get("code"), BigDecimal.valueOf(amount), refId, "Accrual " + month);
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
        List<CompOffRow> credits = repo.findCompOffCredits();
        Map<UUID, List<CompOffRow>> byEmp = new LinkedHashMap<>();
        for (CompOffRow c : credits) byEmp.computeIfAbsent(c.employeeId(), k -> new ArrayList<>()).add(c);
        int expired = 0;
        for (Map.Entry<UUID, List<CompOffRow>> en : byEmp.entrySet()) {
            List<CompOffRow> rows = repo.findCompOffDebits(en.getKey());
            double used = -rows.stream().filter(r -> !"EXPIRED".equals(r.reason())).mapToDouble(CompOffRow::delta).sum();
            List<LeaveRules.Credit> cs = new ArrayList<>();
            for (CompOffRow c : en.getValue()) {
                double exp = -rows.stream().filter(r -> "EXPIRED".equals(r.reason()) && c.id().toString().equals(r.refId())).mapToDouble(CompOffRow::delta).sum();
                cs.add(new LeaveRules.Credit(c.id().toString(), c.delta(), c.expiresOn() != null ? c.expiresOn() : LocalDate.of(9999, 12, 31), exp));
            }
            for (LeaveRules.Expiry x : LeaveRules.compOffExpiries(cs, used, today)) {
                repo.insertCompOffExpiry(en.getKey(), BigDecimal.valueOf(-x.amount()), x.id());
                expired++;
            }
        }
        return Map.of("expired", expired);
    }

    /** On 1 January: casual leave lapses, earned leave carries forward up to its cap. Once per year. */
    public Map<String, Object> jobLeaveYearEnd(Instant now) {
        String prev = String.valueOf(today(now).getYear() - 1);
        Integer ran = repo.countYearEndRuns(prev);
        if (ran != null && ran > 0) return Map.of("lapsed", 0);
        String firstYear = String.valueOf(att.policy().trackingStart().getYear());
        if (prev.compareTo(firstYear) < 0) return Map.of("lapsed", 0);
        List<Map<String, Object>> types = repo.findYearEndTypes();
        Timestamp end = Timestamp.from(LocalDate.parse(prev + "-12-31").atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
        int lapsed = 0;
        for (Map<String, Object> t : types) {
            BigDecimal cap = (BigDecimal) t.get("carry_forward_cap");
            List<Map<String, Object>> sums = repo.sumBalancesUntil(t.get("code"), end);
            for (Map<String, Object> s : sums) {
                BigDecimal sum = (BigDecimal) s.get("s");
                double amount = LeaveRules.yearEndLapse(Num.round2(sum == null ? 0 : sum.doubleValue()), cap.doubleValue());
                if (amount > 0) {
                    // Prisma Decimal.toString(): "0" for zero
                    String note = cap.compareTo(BigDecimal.ZERO) == 0 ? "Lapsed at year end" : "Above carry-forward cap";
                    repo.insertYearEndLapse(s.get("employee_id"), t.get("code"), BigDecimal.valueOf(-amount), "year_end:" + prev, note);
                    lapsed++;
                }
            }
        }
        repo.insertYearEndRun(prev, "{\"lapsed\":" + lapsed + "}");
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
