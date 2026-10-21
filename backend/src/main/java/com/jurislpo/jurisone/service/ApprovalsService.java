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
    private final TransactionTemplate tx;
    private final AttendanceService att;
    private final LeaveService leave;
    private final AuditService audit;
    private final NotificationService notify;

    public ApprovalsService(ApprovalsRepository repo, TransactionTemplate tx, AttendanceService attendance, LeaveService leave, AuditService audit, NotificationService notify) {
        this.repo = repo;
        this.tx = tx;
        this.att = attendance;
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

    static String span(LocalDate f, LocalDate t) {
        return f.equals(t) ? Format.formatDayMonth(f) : Format.formatDayMonth(f) + " – " + Format.formatDayMonth(t);
    }

    /** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
    public String decideLeave(CurrentUser user, Decision decision, String ip) {
        Parsed parsed = parse(decision);
        List<Map<String, Object>> rs = repo.findLeaveForDecision(parsed.id());
        if (rs.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> row = rs.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        if (employeeId.equals(user.id())) throw new UserException("You can't decide your own leave.");
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate from = ((Date) row.get("from_date")).toLocalDate(), to = ((Date) row.get("to_date")).toLocalDate();
        double lopDays = ((BigDecimal) row.get("lop_days")).doubleValue();
        double paidDays = ((BigDecimal) row.get("days")).doubleValue() - lopDays;
        if (parsed.approve()) {
            AttendanceEmployee employee = att.loadAttendanceEmployees("e.id = ?", employeeId).get(0);
            LeaveService.Preview preview = leave.previewRequest(employee, (String) row.get("leave_type"), from, to, LeavePart.valueOf((String) row.get("part")), parsed.id(), true);
            if (preview.error() != null) throw new UserException(preview.error());
            lopDays = preview.lopDays(); // balance may have changed since applying
            paidDays = preview.paidDays();
        }
        double lop = lopDays, paid = paidDays;
        tx.executeWithoutResult(s -> {
            int count = repo.decideLeave(parsed.id(), parsed.approve() ? "APPROVED" : "DECLINED", user.id(), Timestamp.from(Instant.now()), BigDecimal.valueOf(lop));
            if (count == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (parsed.approve()) leave.postUsage(parsed.id(), employeeId, (String) row.get("leave_type"), paid);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", parsed.approve() ? "APPROVED" : "DECLINED");
            after.put("paidDays", paid);
            after.put("lopDays", lop);
            if (parsed.note() != null) after.put("note", parsed.note());
            audit.log(user.id(), parsed.approve() ? "leave.approve" : "leave.decline", "leave_request", parsed.id().toString(), Map.of("status", "PENDING"), after, ip);
        });
        String typeName = (String) row.get("type_name");
        notify.notifyDecision(employeeId, typeName + " (" + span(from, to) + ")", parsed.approve(), user.name(), "/leave", parsed.note());
        return (parsed.approve() ? "Approved" : "Declined") + " " + row.get("full_name") + "’s " + typeName.toLowerCase();
    }

    /**
     * Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final.
     * Approval effects: absent → paid day; late → mark removed; missed check-out → punch added; holiday work → comp-off credit.
     */
    public String decideRegularization(CurrentUser user, Decision decision, String ip) {
        Parsed parsed = parse(decision);
        List<Map<String, Object>> rs = repo.findRegularizationForDecision(parsed.id());
        if (rs.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> row = rs.get(0);
        UUID employeeId = (UUID) row.get("employee_id");
        if (employeeId.equals(user.id())) throw new UserException("You can't decide your own request.");
        if (!"PENDING".equals(row.get("status"))) throw new UserException("This request was already decided.");
        LocalDate date = ((Date) row.get("work_date")).toLocalDate();
        String kind = (String) row.get("kind");
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        if (parsed.approve() && att.isMonthLocked(date.toString().substring(0, 7), policy, Instant.now())) {
            throw new UserException("That month's attendance is locked — decline this and adjust LOP in payroll instead.");
        }
        double compOff = 0;
        if (parsed.approve() && "HOLIDAY_WORK".equals(kind)) {
            AttendanceEmployee employee = att.loadAttendanceEmployees("e.id = ?", employeeId).get(0);
            DayView day = att.attendanceFor(List.of(employee), date, date, Instant.now()).get(employee.id()).get(0);
            compOff = LeaveRules.compOffDays(day.r.workedMinutes, policy.engine().fullDayHours(), policy.engine().halfDayHours());
            if (compOff == 0) throw new UserException("Less than a half day was worked — no comp-off is due.");
        }
        Timestamp proposedIn = (Timestamp) row.get("proposed_in"), proposedOut = (Timestamp) row.get("proposed_out");
        if (parsed.approve() && "MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("The request has no check-out time.");

        double co = compOff;
        tx.executeWithoutResult(s -> {
            int count = repo.decideRegularization(parsed.id(), parsed.approve() ? "APPROVED" : "DECLINED", user.id(), Timestamp.from(Instant.now()), user.roleName());
            if (count == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (parsed.approve() && "MISSED_CHECKOUT".equals(kind)) {
                if (proposedIn != null) {
                    repo.insertRegularizationPunchIn(employeeId, proposedIn);
                }
                repo.insertRegularizationPunchOut(employeeId, proposedOut);
            }
            if (co > 0) {
                Integer expiry = repo.compOffExpiryDays();
                repo.insertCompOffCredit(employeeId, BigDecimal.valueOf(co), parsed.id().toString(), Date.valueOf(date.plusDays(expiry != null ? expiry : 60)), "Worked on " + date);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", parsed.approve() ? "APPROVED" : "DECLINED");
            after.put("kind", kind);
            after.put("date", date.toString());
            after.put("compOff", co);
            if (parsed.note() != null) after.put("note", parsed.note());
            after.put("role", user.roleName());
            audit.log(user.id(), parsed.approve() ? "regularization.approve" : "regularization.decline", "regularization", parsed.id().toString(), Map.of("status", "PENDING"), after, ip);
        });
        notify.notifyDecision(employeeId, KIND.get(kind) + " for " + Format.formatDayMonth(date), parsed.approve(), user.name() + " (" + user.roleName() + ")",
                "/attendance?m=" + date.toString().substring(0, 7) + "&d=" + date, parsed.note());
        return (parsed.approve() ? "Approved" : "Declined") + " " + row.get("full_name") + "’s " + KIND.get(kind).toLowerCase()
                + (co > 0 ? " · " + LeaveService.num(co) + " comp-off credited" : "");
    }

    // ───────────────────────── page ─────────────────────────

    public record LeaveCard(String id, String name, String department, String type, String span, double days, String part, String reason, String impact, int overlap) {}

    public record RegularizationCard(String id, String name, String department, String kind, String date, String detail, String reason) {}

    public record Decided(String id, Instant at, String when, String employeeName, String what, boolean approved, String decidedBy) {}

    static String formatTwoDecimals(double d) {
        return LeaveService.num(Math.round(d * 100) / 100.0);
    }

    public Map<String, Object> page(CurrentUser user, Map<String, String> queryParams) {
        boolean canLeave = user.can("appr.leave"), canReg = user.can("appr.reg");
        String tab = ListParams.pick(queryParams, "tab", List.of("leave", "regularizations"), canLeave ? "leave" : "regularizations");
        String search = ListParams.str(queryParams, "search", "").trim();
        String nameFilter = search.isEmpty() ? "" : " AND e.full_name ILIKE ?";
        Object[] nameArg = search.isEmpty() ? new Object[0] : new Object[] {PeopleQuery.like(search)};

        List<LeaveCard> leaveCards = new ArrayList<>();
        if (canLeave) {
            List<Object> args = new ArrayList<>(List.of(user.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> leaves = repo.findPendingLeave(nameFilter, args.toArray());
            for (Map<String, Object> l : leaves) {
                UUID employeeId = (UUID) l.get("employee_id");
                LocalDate f = ((Date) l.get("from_date")).toLocalDate(), t = ((Date) l.get("to_date")).toLocalDate();
                LeaveService.Balance balance = leave.balances(employeeId).stream().filter(x -> x.code().equals(l.get("leave_type"))).findFirst().orElseThrow();
                double days = ((BigDecimal) l.get("days")).doubleValue(), lop = ((BigDecimal) l.get("lop_days")).doubleValue();
                double paid = days - lop;
                double available = Math.round((balance.balance() - balance.pending() + paid) * 100) / 100.0; // this request's own hold added back
                String impact = !balance.needsBalance() ? "Unpaid leave" : available >= days ? "Balance " + LeaveService.num(available) + " → " + formatTwoDecimals(available - days)
                        : "Only " + LeaveService.num(Math.max(0, available)) + " available — " + formatTwoDecimals(days - Math.max(0, Math.floor(available * 2) / 2)) + " day(s) become loss of pay";
                int overlap = 0;
                if (l.get("department_id") != null) {
                    Integer n = repo.countDepartmentOverlap(l.get("id"), employeeId, l.get("department_id"), f, t);
                    overlap = n == null ? 0 : n;
                }
                leaveCards.add(new LeaveCard(l.get("id").toString(), (String) l.get("full_name"), l.get("dept") == null ? "—" : (String) l.get("dept"),
                        (String) l.get("type_name"), span(f, t), days, PART.get(l.get("part")), (String) l.get("reason"), impact, overlap));
            }
        }

        List<RegularizationCard> regCards = new ArrayList<>();
        if (canReg) {
            List<Object> args = new ArrayList<>(List.of(user.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> regularizations = repo.findPendingRegularizations(nameFilter, args.toArray());
            for (Map<String, Object> regularization : regularizations) {
                LocalDate date = ((Date) regularization.get("work_date")).toLocalDate();
                List<AttendanceEmployee> attendanceEmployees = att.loadAttendanceEmployees("e.id = ?", regularization.get("employee_id"));
                DayView dayView = attendanceEmployees.isEmpty() ? null : att.attendanceFor(attendanceEmployees, date, date, Instant.now()).get(attendanceEmployees.get(0).id()).get(0);
                String timeZone = (String) regularization.get("time_zone");
                Instant pin = regularization.get("proposed_in") == null ? null : ((Timestamp) regularization.get("proposed_in")).toInstant();
                Instant pout = regularization.get("proposed_out") == null ? null : ((Timestamp) regularization.get("proposed_out")).toInstant();
                String kind = (String) regularization.get("kind");
                String firstIn = dayView != null && dayView.r.firstIn != null ? AttendanceService.formatTime(dayView.r.firstIn, timeZone) : "—";
                String label = dayView != null && dayView.r.label != null && !dayView.r.label.isEmpty() ? dayView.r.label : null;
                String detail = switch (kind) {
                    case "MISSED_CHECKOUT" -> "Checked in " + firstIn + " · asks to set check-out to " + (pout != null ? AttendanceService.formatTime(pout, timeZone) : "—");
                    case "HOLIDAY_WORK" -> "Worked " + (dayView != null ? Format.formatHours(dayView.r.workedMinutes / 60.0) : "—") + " on " + (label != null ? label : "a day off") + " — credit comp-off";
                    case "LATE_CHECKIN" -> "Checked in " + firstIn + (pin != null ? " · says " + AttendanceService.formatTime(pin, timeZone) : "");
                    default -> (label != null ? label : "Absent") + (pin != null ? " · says worked from " + AttendanceService.formatTime(pin, timeZone)
                            + (pout != null ? " to " + AttendanceService.formatTime(pout, timeZone) : "") : "");
                };
                regCards.add(new RegularizationCard(regularization.get("id").toString(), (String) regularization.get("full_name"), regularization.get("dept") == null ? "—" : (String) regularization.get("dept"),
                        KIND_SHORT.get(kind), Format.formatDayMonth(date), detail, (String) regularization.get("reason")));
            }
        }

        int leaveCount = canLeave ? count(repo.countPendingLeave(user.id())) : 0;
        int regCount = canReg ? count(repo.countPendingRegularizations(user.id())) : 0;

        Timestamp since = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        List<Decided> decided = new ArrayList<>();
        if ("leave".equals(tab) && canLeave) {
            for (ApprovalsRepository.DecidedLeave decidedLeave : repo.findDecidedLeave(since)) {
                LocalDate f = decidedLeave.from(), t = decidedLeave.to();
                Instant at = decidedLeave.at();
                decided.add(new Decided(decidedLeave.id(), at, Format.formatDateTime(at, user.timeZone()), decidedLeave.name(),
                        decidedLeave.typeName() + " · " + Format.formatDayMonth(f) + (!t.equals(f) ? " – " + Format.formatDayMonth(t) : ""), "APPROVED".equals(decidedLeave.status()),
                        decidedLeave.decidedBy() == null ? "—" : decidedLeave.decidedBy()));
            }
        }
        if ("regularizations".equals(tab) && canReg) {
            for (ApprovalsRepository.DecidedRegularization decidedRegularization : repo.findDecidedRegularizations(since)) {
                Instant at = decidedRegularization.at();
                decided.add(new Decided(decidedRegularization.id(), at, Format.formatDateTime(at, user.timeZone()), decidedRegularization.name(),
                        KIND_SHORT.get(decidedRegularization.kind()) + " · " + Format.formatDayMonth(decidedRegularization.workDate()), "APPROVED".equals(decidedRegularization.status()),
                        (decidedRegularization.decidedBy() == null ? "—" : decidedRegularization.decidedBy()) + (decidedRegularization.role() != null ? " (" + decidedRegularization.role() + ")" : "")));
            }
        }
        decided.sort(Comparator.comparing(Decided::at).reversed());

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
