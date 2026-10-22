package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.repository.ApprovalsRepository;
import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approvals (REQUIREMENTS §5.6/§5.4): leave by HR (appr.leave), regularizations by HR, Finance or
 * Admin (appr.reg). The first decision wins — an atomic {@code UPDATE … WHERE status = 'PENDING'}.
 */
@Service
public class ApprovalsService {

    static final Map<String, String> KIND = Map.of("ABSENT", "Absence regularization", "LATE_CHECKIN", "Late check-in regularization",
            "MISSED_CHECKOUT", "Missed check-out regularization", "HOLIDAY_WORK", "Comp-off request");
    static final Map<String, String> KIND_SHORT = Map.of("ABSENT", "Absent", "LATE_CHECKIN", "Late check-in", "MISSED_CHECKOUT", "Missed check-out", "HOLIDAY_WORK", "Holiday work");
    static final Map<String, String> PART = Map.of("FULL", "Full day", "FIRST_HALF", "First half", "SECOND_HALF", "Second half");

    private final ApprovalsRepository repo;
    private final TransactionTemplate transactionTemplate;
    private final AttendanceService attendanceService;
    private final LeaveService leave;
    private final AuditService audit;
    private final NotificationService notify;

    public ApprovalsService(ApprovalsRepository repo, TransactionTemplate transactionTemplate, AttendanceService attendance, LeaveService leave, AuditService audit, NotificationService notify) {
        this.repo = repo;
        this.transactionTemplate = transactionTemplate;
        this.attendanceService = attendance;
        this.leave = leave;
        this.audit = audit;
        this.notify = notify;
    }

    public record Decision(String id, Boolean approve, String note) {}

    record Parsed(UUID id, boolean approve, String note) {}

    static Parsed parse(Decision decision) {
        UUID id = com.jurislpo.jurisone.common.Check.uuid(decision.id());
        if (decision.approve() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String note = decision.note() == null ? null : decision.note().trim();
        if (note != null && note.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        return new Parsed(id, decision.approve(), note);
    }

    static String span(LocalDate from, LocalDate to) {
        return from.equals(to) ? Format.formatDayMonth(from) : Format.formatDayMonth(from) + " – " + Format.formatDayMonth(to);
    }

    /** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
    public String decideLeave(CurrentUser user, Decision decision, String ipAddress) {
        Parsed parsed = parse(decision);
        List<Map<String, Object>> rows = repo.findLeaveForDecision(parsed.id());
        if (rows.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> row = rows.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        if (employeeId.equals(user.id())) throw new UserException("You can't decide your own leave.");
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate from = ((Date) row.get("from_date")).toLocalDate(), to = ((Date) row.get("to_date")).toLocalDate();
        double lopDays = ((BigDecimal) row.get("lop_days")).doubleValue();
        double paidDays = ((BigDecimal) row.get("days")).doubleValue() - lopDays;
        if (parsed.approve()) {
            AttendanceEmployee employee = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId).get(0);
            LeaveService.Preview preview = leave.previewRequest(employee, (String) row.get("leave_type"), from, to, LeavePart.valueOf((String) row.get("part")), parsed.id(), true);
            if (preview.error() != null) throw new UserException(preview.error());
            lopDays = preview.lopDays(); // balance may have changed since applying
            paidDays = preview.paidDays();
        }
        double lop = lopDays, paid = paidDays;
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            int count = repo.decideLeave(parsed.id(), parsed.approve() ? "APPROVED" : "DECLINED", user.id(), Timestamp.from(Instant.now()), BigDecimal.valueOf(lop));
            if (count == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (parsed.approve()) leave.postUsage(parsed.id(), employeeId, (String) row.get("leave_type"), paid);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", parsed.approve() ? "APPROVED" : "DECLINED");
            after.put("paidDays", paid);
            after.put("lopDays", lop);
            if (parsed.note() != null) after.put("note", parsed.note());
            audit.log(user.id(), parsed.approve() ? "leave.approve" : "leave.decline", "leave_request", parsed.id().toString(), Map.of("status", "PENDING"), after, ipAddress);
        });
        String typeName = (String) row.get("type_name");
        notify.notifyDecision(employeeId, typeName + " (" + span(from, to) + ")", parsed.approve(), user.name(), "/leave", parsed.note());
        return (parsed.approve() ? "Approved" : "Declined") + " " + row.get("full_name") + "’s " + typeName.toLowerCase();
    }

    /**
     * Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final.
     * Approval effects: absent → paid day; late → mark removed; missed check-out → punch added; holiday work → comp-off credit.
     */
    public String decideRegularization(CurrentUser user, Decision decision, String ipAddress) {
        Parsed parsed = parse(decision);
        List<Map<String, Object>> rows = repo.findRegularizationForDecision(parsed.id());
        if (rows.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> row = rows.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        if (employeeId.equals(user.id())) throw new UserException("You can't decide your own request.");
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate date = ((Date) row.get("work_date")).toLocalDate();
        String kind = (String) row.get("kind");
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        if (parsed.approve() && attendanceService.isMonthLocked(date.toString().substring(0, 7), policy, Instant.now())) {
            throw new UserException("That month's attendance is locked — decline this and adjust LOP in payroll instead.");
        }
        double compOff = 0;
        if (parsed.approve() && "HOLIDAY_WORK".equals(kind)) {
            AttendanceEmployee employee = attendanceService.loadAttendanceEmployees("e.id = ?", employeeId).get(0);
            DayView day = attendanceService.attendanceFor(List.of(employee), date, date, Instant.now()).get(employee.id()).get(0);
            compOff = LeaveRules.compOffDays(day.dayResult.workedMinutes, policy.engine().fullDayHours(), policy.engine().halfDayHours());
            if (compOff == 0) throw new UserException("Less than a half day was worked — no comp-off is due.");
        }
        Timestamp proposedIn = (Timestamp) row.get("proposed_in"), proposedOut = (Timestamp) row.get("proposed_out");
        if (parsed.approve() && "MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("The request has no check-out time.");

        double compOffDays = compOff;
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            int count = repo.decideRegularization(parsed.id(), parsed.approve() ? "APPROVED" : "DECLINED", user.id(), Timestamp.from(Instant.now()), user.roleName());
            if (count == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (parsed.approve() && "MISSED_CHECKOUT".equals(kind)) {
                if (proposedIn != null) {
                    repo.insertRegularizationPunchIn(employeeId, proposedIn);
                }
                repo.insertRegularizationPunchOut(employeeId, proposedOut);
            }
            if (compOffDays > 0) {
                Integer expiry = repo.compOffExpiryDays();
                repo.insertCompOffCredit(employeeId, BigDecimal.valueOf(compOffDays), parsed.id().toString(), Date.valueOf(date.plusDays(expiry != null ? expiry : 60)), "Worked on " + date);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", parsed.approve() ? "APPROVED" : "DECLINED");
            after.put("kind", kind);
            after.put("date", date.toString());
            after.put("compOff", compOffDays);
            if (parsed.note() != null) after.put("note", parsed.note());
            after.put("role", user.roleName());
            audit.log(user.id(), parsed.approve() ? "regularization.approve" : "regularization.decline", "regularization", parsed.id().toString(), Map.of("status", "PENDING"), after, ipAddress);
        });
        notify.notifyDecision(employeeId, KIND.get(kind) + " for " + Format.formatDayMonth(date), parsed.approve(), user.name() + " (" + user.roleName() + ")",
                "/attendance?month=" + date.toString().substring(0, 7) + "&date=" + date, parsed.note());
        return (parsed.approve() ? "Approved" : "Declined") + " " + row.get("full_name") + "’s " + KIND.get(kind).toLowerCase()
                + (compOffDays > 0 ? " · " + LeaveService.formatNumber(compOffDays) + " comp-off credited" : "");
    }

    // ───────────────────────── page ─────────────────────────

    public record LeaveCard(String id, String name, String department, String type, String span, double days, String part, String reason, String impact, int overlap) {}

    public record RegularizationCard(String id, String name, String department, String kind, String date, String detail, String reason) {}

    public record Decided(String id, Instant decidedAt, String when, String employeeName, String what, boolean approved, String decidedBy) {}

    static String formatTwoDecimals(double value) {
        return LeaveService.formatNumber(Math.round(value * 100) / 100.0);
    }

    public Map<String, Object> page(CurrentUser user, Map<String, String> queryParams) {
        boolean canLeave = user.can("appr.leave"), canReg = user.can("appr.reg");
        String tab = ListParams.pick(queryParams, "tab", List.of("leave", "regularizations"), canLeave ? "leave" : "regularizations");
        String search = ListParams.text(queryParams, "search", "").trim();
        String nameFilter = search.isEmpty() ? "" : " AND e.full_name ILIKE ?";
        Object[] nameArg = search.isEmpty() ? new Object[0] : new Object[] {PeopleQuery.like(search)};

        List<LeaveCard> leaveCards = new ArrayList<>();
        if (canLeave) {
            List<Object> args = new ArrayList<>(List.of(user.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> leaves = repo.findPendingLeave(nameFilter, args.toArray());
            for (Map<String, Object> leaveRow : leaves) {
                UUID employeeId = (UUID) leaveRow.get("employee_id");
                LocalDate from = ((Date) leaveRow.get("from_date")).toLocalDate(), to = ((Date) leaveRow.get("to_date")).toLocalDate();
                LeaveService.Balance balance = leave.balances(employeeId).stream().filter(x -> x.code().equals(leaveRow.get("leave_type"))).findFirst().orElseThrow();
                double days = ((BigDecimal) leaveRow.get("days")).doubleValue(), lop = ((BigDecimal) leaveRow.get("lop_days")).doubleValue();
                double paid = days - lop;
                double available = Math.round((balance.balance() - balance.pending() + paid) * 100) / 100.0; // this request's own hold added back
                String impact = !balance.needsBalance() ? "Unpaid leave" : available >= days ? "Balance " + LeaveService.formatNumber(available) + " → " + formatTwoDecimals(available - days)
                        : "Only " + LeaveService.formatNumber(Math.max(0, available)) + " available — " + formatTwoDecimals(days - Math.max(0, Math.floor(available * 2) / 2)) + " day(s) become loss of pay";
                int overlap = 0;
                if (leaveRow.get("department_id") != null) {
                    Integer overlapCount = repo.countDepartmentOverlap(leaveRow.get("id"), employeeId, leaveRow.get("department_id"), from, to);
                    overlap = overlapCount == null ? 0 : overlapCount;
                }
                leaveCards.add(new LeaveCard(leaveRow.get("id").toString(), (String) leaveRow.get("full_name"), leaveRow.get("department_name") == null ? "—" : (String) leaveRow.get("department_name"),
                        (String) leaveRow.get("type_name"), span(from, to), days, PART.get(leaveRow.get("part")), (String) leaveRow.get("reason"), impact, overlap));
            }
        }

        List<RegularizationCard> regCards = new ArrayList<>();
        if (canReg) {
            List<Object> args = new ArrayList<>(List.of(user.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> regularizations = repo.findPendingRegularizations(nameFilter, args.toArray());
            for (Map<String, Object> regularization : regularizations) {
                LocalDate date = ((Date) regularization.get("work_date")).toLocalDate();
                List<AttendanceEmployee> attendanceEmployees = attendanceService.loadAttendanceEmployees("e.id = ?", regularization.get("employee_id"));
                DayView dayView = attendanceEmployees.isEmpty() ? null : attendanceService.attendanceFor(attendanceEmployees, date, date, Instant.now()).get(attendanceEmployees.get(0).id()).get(0);
                String timeZone = (String) regularization.get("time_zone");
                Instant proposedIn = regularization.get("proposed_in") == null ? null : ((Timestamp) regularization.get("proposed_in")).toInstant();
                Instant proposedOut = regularization.get("proposed_out") == null ? null : ((Timestamp) regularization.get("proposed_out")).toInstant();
                String kind = (String) regularization.get("kind");
                String firstIn = dayView != null && dayView.dayResult.firstIn != null ? AttendanceService.formatTime(dayView.dayResult.firstIn, timeZone) : "—";
                String label = dayView != null && dayView.dayResult.label != null && !dayView.dayResult.label.isEmpty() ? dayView.dayResult.label : null;
                String detail = switch (kind) {
                    case "MISSED_CHECKOUT" -> "Checked in " + firstIn + " · asks to set check-out to " + (proposedOut != null ? AttendanceService.formatTime(proposedOut, timeZone) : "—");
                    case "HOLIDAY_WORK" -> "Worked " + (dayView != null ? Format.formatHours(dayView.dayResult.workedMinutes / 60.0) : "—") + " on " + (label != null ? label : "a day off") + " — credit comp-off";
                    case "LATE_CHECKIN" -> "Checked in " + firstIn + (proposedIn != null ? " · says " + AttendanceService.formatTime(proposedIn, timeZone) : "");
                    default -> (label != null ? label : "Absent") + (proposedIn != null ? " · says worked from " + AttendanceService.formatTime(proposedIn, timeZone)
                            + (proposedOut != null ? " to " + AttendanceService.formatTime(proposedOut, timeZone) : "") : "");
                };
                regCards.add(new RegularizationCard(regularization.get("id").toString(), (String) regularization.get("full_name"), regularization.get("department_name") == null ? "—" : (String) regularization.get("department_name"),
                        KIND_SHORT.get(kind), Format.formatDayMonth(date), detail, (String) regularization.get("reason")));
            }
        }

        int leaveCount = canLeave ? count(repo.countPendingLeave(user.id())) : 0;
        int regCount = canReg ? count(repo.countPendingRegularizations(user.id())) : 0;

        Timestamp since = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        List<Decided> decided = new ArrayList<>();
        if ("leave".equals(tab) && canLeave) {
            for (ApprovalsRepository.DecidedLeave decidedLeave : repo.findDecidedLeave(since)) {
                LocalDate from = decidedLeave.from(), to = decidedLeave.to();
                Instant instant = decidedLeave.decidedAt();
                decided.add(new Decided(decidedLeave.id(), instant, Format.formatDateTime(instant, user.timeZone()), decidedLeave.name(),
                        decidedLeave.typeName() + " · " + Format.formatDayMonth(from) + (!to.equals(from) ? " – " + Format.formatDayMonth(to) : ""), "APPROVED".equals(decidedLeave.status()),
                        decidedLeave.decidedBy() == null ? "—" : decidedLeave.decidedBy()));
            }
        }
        if ("regularizations".equals(tab) && canReg) {
            for (ApprovalsRepository.DecidedRegularization decidedRegularization : repo.findDecidedRegularizations(since)) {
                Instant instant = decidedRegularization.decidedAt();
                decided.add(new Decided(decidedRegularization.id(), instant, Format.formatDateTime(instant, user.timeZone()), decidedRegularization.name(),
                        KIND_SHORT.get(decidedRegularization.kind()) + " · " + Format.formatDayMonth(decidedRegularization.workDate()), "APPROVED".equals(decidedRegularization.status()),
                        (decidedRegularization.decidedBy() == null ? "—" : decidedRegularization.decidedBy()) + (decidedRegularization.role() != null ? " (" + decidedRegularization.role() + ")" : "")));
            }
        }
        decided.sort(Comparator.comparing(Decided::decidedAt).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tab", tab);
        out.put("search", search);
        out.put("canLeave", canLeave);
        out.put("canApproveRegularizations", canReg);
        out.put("leaveCount", leaveCount);
        out.put("regularizationCount", regCount);
        out.put("leaveCards", leaveCards);
        out.put("regularizationCards", regCards);
        out.put("decided", decided);
        return out;
    }

    private static int count(Integer count) {
        return count == null ? 0 : count;
    }
}
