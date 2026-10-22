package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
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
    private final AttendanceService attendanceService;
    private final SettingsService settings;
    private final AuditService audit;
    private final SubscriptionService subscriptions;
    private final com.jurislpo.jurisone.service.NotificationService notifications;
    private final com.jurislpo.jurisone.service.MailService mail;
    private final String appUrl;

    public JobsService(JobsRepository repo, AttendanceService attendance, SettingsService settings, AuditService audit, SubscriptionService subscriptions,
                       com.jurislpo.jurisone.service.NotificationService notifications, com.jurislpo.jurisone.service.MailService mail,
                       com.jurislpo.jurisone.config.JurisOneProperties props) {
        this.repo = repo;
        this.attendanceService = attendance;
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
            for (Map<String, Object> business : businesses) {
                all.put((String) business.get("code"), TenantContext.call((UUID) business.get("id"), () -> runForBusiness(now)));
            }
            return all;
        }).orElseGet(() -> Map.of("skipped", true));
    }

    /** Every job, in order, for the current business. */
    public Map<String, Object> runForBusiness(Instant now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", jobEmployeeStatus(now));
        view.put("lock", jobLockMonths(now));
        view.put("finalize", jobFinalizeDays(now));
        view.put("yearEnd", jobLeaveYearEnd(now));
        view.put("accrual", jobLeaveAccrual(now));
        view.put("compOff", jobCompOffExpiry(now));
        view.put("subscription", jobSubscriptionWarnings());
        return view;
    }

    /**
     * Tells the owners (bell + email) that the subscription ends in 7 days, ends tomorrow / today, or has ended —
     * each warning once per subscription end date.
     */
    public Map<String, Object> jobSubscriptionWarnings() {
        SubscriptionService.Current current = subscriptions.current(null);
        String kind = null;
        if ("ACTIVE".equals(current.state()) && current.daysLeft() != null) kind = current.daysLeft() <= 1 ? "d1" : current.daysLeft() <= 7 ? "d7" : null;
        else if ("EXPIRED".equals(current.state())) kind = "ended";
        if (kind == null) return Map.of("sent", 0);
        String key = current.endsOn() + ":" + kind;
        Integer done = repo.countSubscriptionWarningRuns(key);
        if (done != null && done > 0) return Map.of("sent", 0);
        int days = "ended".equals(kind) ? 0 : Math.max(1, current.daysLeft());
        String business = repo.currentBusinessName();
        String text = "ended".equals(kind) ? "Your " + current.planName() + " subscription has ended — only the employee export works until it is renewed"
                : "Your " + current.planName() + " subscription ends " + (current.daysLeft() == 0 ? "today" : current.daysLeft() == 1 ? "tomorrow" : "in " + current.daysLeft() + " days")
                        + " (" + current.endsOn() + ") — contact JurisOne to renew";
        List<Map<String, Object>> owners = subscriptions.owners();
        for (Map<String, Object> owner : owners) {
            notifications.notify((UUID) owner.get("id"), text, "/subscription");
            mail.send((String) owner.get("email"), com.jurislpo.jurisone.service.MailTemplates.subscription((String) owner.get("full_name"), business, current.planName(),
                    current.endsOn(), days, appUrl + "/subscription"));
        }
        repo.insertSubscriptionWarningRun(key, "{\"owners\":" + owners.size() + "}");
        return Map.of("sent", owners.size(), "kind", kind);
    }

    /**
     * Daily absence marking: persists finished days (shift end + buffer passed) for the last week,
     * so a working day with no check-in and no approved leave is recorded as Absent (LOP).
     */
    public Map<String, Object> jobFinalizeDays(Instant now) {
        AttendanceService.ResolvedAttendancePolicy resolvedAttendancePolicy = attendanceService.policy();
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.status <> 'EXITED' OR e.exit_date >= ?", Timestamp.from(now.minusSeconds(10L * 86400)));
        LocalDate today = today(now);
        LocalDate from = today.minusDays(7).isAfter(resolvedAttendancePolicy.trackingStart()) ? today.minusDays(7) : resolvedAttendancePolicy.trackingStart();
        return Map.of("written", attendanceService.materialize(employees, from, today, false, now));
    }

    /** Locks each month at the configured time (default: 1st of next month, 10:00 IST). Idempotent. */
    public Map<String, Object> jobLockMonths(Instant now) {
        AttendanceService.ResolvedAttendancePolicy resolvedAttendancePolicy = attendanceService.policy();
        List<String> locked = new ArrayList<>();
        for (YearMonth yearMonth = YearMonth.from(resolvedAttendancePolicy.trackingStart()); !attendanceService.lockInstant(yearMonth.toString(), resolvedAttendancePolicy).isAfter(now); yearMonth = yearMonth.plusMonths(1)) {
            Integer done = repo.countLockRuns(yearMonth.toString());
            if (done != null && done > 0) continue;
            LocalDate from = yearMonth.atDay(1).isBefore(resolvedAttendancePolicy.trackingStart()) ? resolvedAttendancePolicy.trackingStart() : yearMonth.atDay(1);
            LocalDate to = yearMonth.atEndOfMonth();
            List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)", Date.valueOf(to), Date.valueOf(from));
            int written = attendanceService.materialize(employees, from, to, true, now);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("employees", employees.size());
            detail.put("rows", written);
            repo.insertLockRun(yearMonth.toString(), "{\"employees\":" + employees.size() + ",\"rows\":" + written + "}");
            audit.log(null, "attendance.month_locked", "attendance", yearMonth.toString(), null, detail, null);
            locked.add(yearMonth.toString());
        }
        return Map.of("locked", locked);
    }

    /** Onboarding → Probation on the joining date → Active after the probation period. */
    public Map<String, Object> jobEmployeeStatus(Instant now) {
        int months = settings.employee().probationMonths();
        LocalDate today = today(now);
        List<Map<String, Object>> employees = repo.findOnboardingOrProbation();
        int changed = 0;
        for (Map<String, Object> employee : employees) {
            EmployeeStatus currentStatus = EmployeeStatus.valueOf((String) employee.get("status"));
            EmployeeStatus next = PeopleRules.derivedStatus(currentStatus, ((Date) employee.get("date_of_joining")).toLocalDate(), today, months);
            if (next == currentStatus) continue;
            repo.updateEmployeeStatus(employee.get("id"), next.name());
            audit.log(null, "employee.status_auto", "employee", employee.get("id").toString(), Map.of("status", currentStatus.name()), Map.of("status", next.name()), null);
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
        String yearMonth = today(now).toString().substring(0, 7);
        String year = yearMonth.substring(0, 4);
        List<Map<String, Object>> types = repo.findAccruingTypes();
        List<Map<String, Object>> employees = repo.findAccrualEmployees(Date.valueOf(YearMonth.parse(yearMonth).atEndOfMonth()));
        Set<String> done = repo.findAccrualKeys();
        int credited = 0;
        for (Map<String, Object> employee : employees) {
            LocalDate dateOfJoining = ((Date) employee.get("date_of_joining")).toLocalDate();
            for (int monthNumber = 1; monthNumber <= Integer.parseInt(yearMonth.substring(5)); monthNumber++) {
                String month = year + "-" + String.format(java.util.Locale.ROOT, "%02d", monthNumber);
                for (Map<String, Object> type : types) {
                    String refId = "accrual:" + type.get("code") + ":" + month;
                    if (done.contains(employee.get("id") + "|" + refId)) continue;
                    double amount = LeaveRules.accrualFor(YearMonth.parse(month), dateOfJoining, ((BigDecimal) type.get("accrual_per_month")).doubleValue());
                    if (amount > 0) {
                        repo.insertAccrual(employee.get("id"), type.get("code"), BigDecimal.valueOf(amount), refId, "Accrual " + month);
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
        Map<UUID, List<CompOffRow>> byEmployee = new LinkedHashMap<>();
        for (CompOffRow credit : credits) byEmployee.computeIfAbsent(credit.employeeId(), k -> new ArrayList<>()).add(credit);
        int expired = 0;
        for (Map.Entry<UUID, List<CompOffRow>> entry : byEmployee.entrySet()) {
            List<CompOffRow> rows = repo.findCompOffDebits(entry.getKey());
            double used = -rows.stream().filter(r -> !"EXPIRED".equals(r.reason())).mapToDouble(CompOffRow::delta).sum();
            List<LeaveRules.Credit> creditList = new ArrayList<>();
            for (CompOffRow compOffRow : entry.getValue()) {
                double expiredDays = -rows.stream().filter(r -> "EXPIRED".equals(r.reason()) && compOffRow.id().toString().equals(r.refId())).mapToDouble(CompOffRow::delta).sum();
                creditList.add(new LeaveRules.Credit(compOffRow.id().toString(), compOffRow.delta(), compOffRow.expiresOn() != null ? compOffRow.expiresOn() : LocalDate.of(9999, 12, 31), expiredDays));
            }
            for (LeaveRules.Expiry expiry : LeaveRules.compOffExpiries(creditList, used, today)) {
                repo.insertCompOffExpiry(entry.getKey(), BigDecimal.valueOf(-expiry.amount()), expiry.id());
                expired++;
            }
        }
        return Map.of("expired", expired);
    }

    /** On 1 January: casual leave lapses, earned leave carries forward up to its cap. Once per year. */
    public Map<String, Object> jobLeaveYearEnd(Instant now) {
        String previous = String.valueOf(today(now).getYear() - 1);
        Integer runCount = repo.countYearEndRuns(previous);
        if (runCount != null && runCount > 0) return Map.of("lapsed", 0);
        String firstYear = String.valueOf(attendanceService.policy().trackingStart().getYear());
        if (previous.compareTo(firstYear) < 0) return Map.of("lapsed", 0);
        List<Map<String, Object>> types = repo.findYearEndTypes();
        Timestamp end = Timestamp.from(LocalDate.parse(previous + "-12-31").atTime(23, 59, 59).toInstant(ZoneOffset.UTC));
        int lapsed = 0;
        for (Map<String, Object> type : types) {
            BigDecimal cap = (BigDecimal) type.get("carry_forward_cap");
            List<Map<String, Object>> sums = repo.sumBalancesUntil(type.get("code"), end);
            for (Map<String, Object> sumRow : sums) {
                BigDecimal sum = (BigDecimal) sumRow.get("total_delta");
                double amount = LeaveRules.yearEndLapse(Num.round2(sum == null ? 0 : sum.doubleValue()), cap.doubleValue());
                if (amount > 0) {
                    // Prisma Decimal.toString(): "0" for zero
                    String note = cap.compareTo(BigDecimal.ZERO) == 0 ? "Lapsed at year end" : "Above carry-forward cap";
                    repo.insertYearEndLapse(sumRow.get("employee_id"), type.get("code"), BigDecimal.valueOf(-amount), "year_end:" + previous, note);
                    lapsed++;
                }
            }
        }
        repo.insertYearEndRun(previous, "{\"lapsed\":" + lapsed + "}");
        return Map.of("lapsed", lapsed);
    }

    void logSummary(Map<String, Object> row) {
        if (row.containsKey("skipped")) return;
        @SuppressWarnings("unchecked")
        List<String> locked = (List<String>) ((Map<String, Object>) row.get("lock")).get("locked");
        log.info("[jobs] status {} · days {}{}", ((Map<?, ?>) row.get("status")).get("changed"), ((Map<?, ?>) row.get("finalize")).get("written"),
                locked.isEmpty() ? "" : " · locked " + String.join(", ", locked));
    }
}
