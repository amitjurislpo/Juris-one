package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
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

    public ApprovalsService(ApprovalsRepository repo, TransactionTemplate tx, AttendanceService att, LeaveService leave, AuditService audit, NotificationService notify) {
        this.repo = repo;
        this.tx = tx;
        this.att = att;
        this.leave = leave;
        this.audit = audit;
        this.notify = notify;
    }

    public record Decision(String id, Boolean approve, String note) {}

    record Parsed(UUID id, boolean approve, String note) {}

    static Parsed parse(Decision d) {
        UUID id = com.jurislpo.jurisone.common.Check.uuid(d.id());
        if (d.approve() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected boolean, received undefined");
        String note = d.note() == null ? null : d.note().trim();
        if (note != null && note.length() > 300) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=300 characters");
        return new Parsed(id, d.approve(), note);
    }

    static String span(LocalDate f, LocalDate t) {
        return f.equals(t) ? Format.fmtD(f) : Format.fmtD(f) + " – " + Format.fmtD(t);
    }

    /** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
    public String decideLeave(CurrentUser u, Decision in, String ip) {
        Parsed d = parse(in);
        List<Map<String, Object>> rs = repo.findLeaveForDecision(d.id());
        if (rs.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> r = rs.get(0);
        UUID employeeId = (UUID) r.get("employee_id");
        if (employeeId.equals(u.id())) throw new UserException("You can't decide your own leave.");
        if (!"PENDING".equals(r.get("status"))) throw new UserException("This request was already decided.");
        LocalDate from = ((Date) r.get("from_date")).toLocalDate(), to = ((Date) r.get("to_date")).toLocalDate();
        double lopDays = ((BigDecimal) r.get("lop_days")).doubleValue();
        double paidDays = ((BigDecimal) r.get("days")).doubleValue() - lopDays;
        if (d.approve()) {
            AttEmployee emp = att.loadAttEmployees("e.id = ?", employeeId).get(0);
            LeaveService.Preview p = leave.previewRequest(emp, (String) r.get("leave_type"), from, to, LeavePart.valueOf((String) r.get("part")), d.id(), true);
            if (p.error() != null) throw new UserException(p.error());
            lopDays = p.lopDays(); // balance may have changed since applying
            paidDays = p.paidDays();
        }
        double lop = lopDays, paid = paidDays;
        tx.executeWithoutResult(s -> {
            int n = repo.decideLeave(d.id(), d.approve() ? "APPROVED" : "DECLINED", u.id(), Timestamp.from(Instant.now()), BigDecimal.valueOf(lop));
            if (n == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (d.approve()) leave.postUsage(d.id(), employeeId, (String) r.get("leave_type"), paid);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", d.approve() ? "APPROVED" : "DECLINED");
            after.put("paidDays", paid);
            after.put("lopDays", lop);
            if (d.note() != null) after.put("note", d.note());
            audit.log(u.id(), d.approve() ? "leave.approve" : "leave.decline", "leave_request", d.id().toString(), Map.of("status", "PENDING"), after, ip);
        });
        String typeName = (String) r.get("type_name");
        notify.notifyDecision(employeeId, typeName + " (" + span(from, to) + ")", d.approve(), u.name(), "/leave", d.note());
        return (d.approve() ? "Approved" : "Declined") + " " + r.get("full_name") + "’s " + typeName.toLowerCase();
    }

    /**
     * Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final.
     * Approval effects: absent → paid day; late → mark removed; missed check-out → punch added; holiday work → comp-off credit.
     */
    public String decideRegularization(CurrentUser u, Decision in, String ip) {
        Parsed d = parse(in);
        List<Map<String, Object>> rs = repo.findRegularizationForDecision(d.id());
        if (rs.isEmpty()) throw new UserException("Request not found.");
        Map<String, Object> r = rs.get(0);
        UUID employeeId = (UUID) r.get("employee_id");
        if (employeeId.equals(u.id())) throw new UserException("You can't decide your own request.");
        if (!"PENDING".equals(r.get("status"))) throw new UserException("This request was already decided.");
        LocalDate date = ((Date) r.get("work_date")).toLocalDate();
        String kind = (String) r.get("kind");
        AttendanceService.Pol policy = att.policy();
        if (d.approve() && att.isMonthLocked(date.toString().substring(0, 7), policy, Instant.now())) {
            throw new UserException("That month's attendance is locked — decline this and adjust LOP in payroll instead.");
        }
        double compOff = 0;
        if (d.approve() && "HOLIDAY_WORK".equals(kind)) {
            AttEmployee emp = att.loadAttEmployees("e.id = ?", employeeId).get(0);
            DayView day = att.attendanceFor(List.of(emp), date, date, Instant.now()).get(emp.id()).get(0);
            compOff = LeaveRules.compOffDays(day.r.workedMinutes, policy.engine().fullDayHours(), policy.engine().halfDayHours());
            if (compOff == 0) throw new UserException("Less than a half day was worked — no comp-off is due.");
        }
        Timestamp proposedIn = (Timestamp) r.get("proposed_in"), proposedOut = (Timestamp) r.get("proposed_out");
        if (d.approve() && "MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("The request has no check-out time.");

        double co = compOff;
        tx.executeWithoutResult(s -> {
            int n = repo.decideRegularization(d.id(), d.approve() ? "APPROVED" : "DECLINED", u.id(), Timestamp.from(Instant.now()), u.roleName());
            if (n == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (d.approve() && "MISSED_CHECKOUT".equals(kind)) {
                if (proposedIn != null) {
                    repo.insertRegularizationPunchIn(employeeId, proposedIn);
                }
                repo.insertRegularizationPunchOut(employeeId, proposedOut);
            }
            if (co > 0) {
                Integer expiry = repo.compOffExpiryDays();
                repo.insertCompOffCredit(employeeId, BigDecimal.valueOf(co), d.id().toString(), Date.valueOf(date.plusDays(expiry != null ? expiry : 60)), "Worked on " + date);
            }
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", d.approve() ? "APPROVED" : "DECLINED");
            after.put("kind", kind);
            after.put("date", date.toString());
            after.put("compOff", co);
            if (d.note() != null) after.put("note", d.note());
            after.put("role", u.roleName());
            audit.log(u.id(), d.approve() ? "regularization.approve" : "regularization.decline", "regularization", d.id().toString(), Map.of("status", "PENDING"), after, ip);
        });
        notify.notifyDecision(employeeId, KIND.get(kind) + " for " + Format.fmtD(date), d.approve(), u.name() + " (" + u.roleName() + ")",
                "/attendance?m=" + date.toString().substring(0, 7) + "&d=" + date, d.note());
        return (d.approve() ? "Approved" : "Declined") + " " + r.get("full_name") + "’s " + KIND.get(kind).toLowerCase()
                + (co > 0 ? " · " + LeaveService.num(co) + " comp-off credited" : "");
    }

    // ───────────────────────── page ─────────────────────────

    public record LeaveCard(String id, String name, String dept, String type, String span, double days, String part, String reason, String impact, int overlap) {}

    public record RegCard(String id, String name, String dept, String kind, String date, String detail, String reason) {}

    public record Decided(String id, Instant at, String when, String who, String what, boolean ok, String by) {}

    static String fmt2(double d) {
        return LeaveService.num(Math.round(d * 100) / 100.0);
    }

    public Map<String, Object> page(CurrentUser u, Map<String, String> sp) {
        boolean canLeave = u.can("appr.leave"), canReg = u.can("appr.reg");
        String tab = ListParams.pick(sp, "tab", List.of("leave", "att"), canLeave ? "leave" : "att");
        String q = ListParams.str(sp, "q", "").trim();
        String nameFilter = q.isEmpty() ? "" : " AND e.full_name ILIKE ?";
        Object[] nameArg = q.isEmpty() ? new Object[0] : new Object[] {PeopleQuery.like(q)};

        List<LeaveCard> leaveCards = new ArrayList<>();
        if (canLeave) {
            List<Object> args = new ArrayList<>(List.of(u.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> leaves = repo.findPendingLeave(nameFilter, args.toArray());
            for (Map<String, Object> l : leaves) {
                UUID empId = (UUID) l.get("employee_id");
                LocalDate f = ((Date) l.get("from_date")).toLocalDate(), t = ((Date) l.get("to_date")).toLocalDate();
                LeaveService.Balance b = leave.balances(empId).stream().filter(x -> x.code().equals(l.get("leave_type"))).findFirst().orElseThrow();
                double days = ((BigDecimal) l.get("days")).doubleValue(), lop = ((BigDecimal) l.get("lop_days")).doubleValue();
                double paid = days - lop;
                double available = Math.round((b.balance() - b.pending() + paid) * 100) / 100.0; // this request's own hold added back
                String impact = !b.needsBalance() ? "Unpaid leave" : available >= days ? "Balance " + LeaveService.num(available) + " → " + fmt2(available - days)
                        : "Only " + LeaveService.num(Math.max(0, available)) + " available — " + fmt2(days - Math.max(0, Math.floor(available * 2) / 2)) + " day(s) become loss of pay";
                int overlap = 0;
                if (l.get("department_id") != null) {
                    Integer n = repo.countDepartmentOverlap(l.get("id"), empId, l.get("department_id"), f, t);
                    overlap = n == null ? 0 : n;
                }
                leaveCards.add(new LeaveCard(l.get("id").toString(), (String) l.get("full_name"), l.get("dept") == null ? "—" : (String) l.get("dept"),
                        (String) l.get("type_name"), span(f, t), days, PART.get(l.get("part")), (String) l.get("reason"), impact, overlap));
            }
        }

        List<RegCard> regCards = new ArrayList<>();
        if (canReg) {
            List<Object> args = new ArrayList<>(List.of(u.id()));
            args.addAll(List.of(nameArg));
            List<Map<String, Object>> regs = repo.findPendingRegularizations(nameFilter, args.toArray());
            for (Map<String, Object> r : regs) {
                LocalDate date = ((Date) r.get("work_date")).toLocalDate();
                List<AttEmployee> es = att.loadAttEmployees("e.id = ?", r.get("employee_id"));
                DayView d = es.isEmpty() ? null : att.attendanceFor(es, date, date, Instant.now()).get(es.get(0).id()).get(0);
                String tz = (String) r.get("time_zone");
                Instant pin = r.get("proposed_in") == null ? null : ((Timestamp) r.get("proposed_in")).toInstant();
                Instant pout = r.get("proposed_out") == null ? null : ((Timestamp) r.get("proposed_out")).toInstant();
                String kind = (String) r.get("kind");
                String firstIn = d != null && d.r.firstIn != null ? AttendanceService.fmtTime(d.r.firstIn, tz) : "—";
                String label = d != null && d.r.label != null && !d.r.label.isEmpty() ? d.r.label : null;
                String detail = switch (kind) {
                    case "MISSED_CHECKOUT" -> "Checked in " + firstIn + " · asks to set check-out to " + (pout != null ? AttendanceService.fmtTime(pout, tz) : "—");
                    case "HOLIDAY_WORK" -> "Worked " + (d != null ? Format.fmtH(d.r.workedMinutes / 60.0) : "—") + " on " + (label != null ? label : "a day off") + " — credit comp-off";
                    case "LATE_CHECKIN" -> "Checked in " + firstIn + (pin != null ? " · says " + AttendanceService.fmtTime(pin, tz) : "");
                    default -> (label != null ? label : "Absent") + (pin != null ? " · says worked from " + AttendanceService.fmtTime(pin, tz)
                            + (pout != null ? " to " + AttendanceService.fmtTime(pout, tz) : "") : "");
                };
                regCards.add(new RegCard(r.get("id").toString(), (String) r.get("full_name"), r.get("dept") == null ? "—" : (String) r.get("dept"),
                        KIND_SHORT.get(kind), Format.fmtD(date), detail, (String) r.get("reason")));
            }
        }

        int leaveCount = canLeave ? count(repo.countPendingLeave(u.id())) : 0;
        int regCount = canReg ? count(repo.countPendingRegularizations(u.id())) : 0;

        Timestamp since = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        List<Decided> decided = new ArrayList<>();
        if ("leave".equals(tab) && canLeave) {
            for (ApprovalsRepository.DecidedLeave x : repo.findDecidedLeave(since)) {
                LocalDate f = x.from(), t = x.to();
                Instant at = x.at();
                decided.add(new Decided(x.id(), at, Format.fmtDateTime(at, u.timeZone()), x.name(),
                        x.typeName() + " · " + Format.fmtD(f) + (!t.equals(f) ? " – " + Format.fmtD(t) : ""), "APPROVED".equals(x.status()),
                        x.by() == null ? "—" : x.by()));
            }
        }
        if ("att".equals(tab) && canReg) {
            for (ApprovalsRepository.DecidedReg x : repo.findDecidedRegularizations(since)) {
                Instant at = x.at();
                decided.add(new Decided(x.id(), at, Format.fmtDateTime(at, u.timeZone()), x.name(),
                        KIND_SHORT.get(x.kind()) + " · " + Format.fmtD(x.workDate()), "APPROVED".equals(x.status()),
                        (x.by() == null ? "—" : x.by()) + (x.role() != null ? " (" + x.role() + ")" : "")));
            }
        }
        decided.sort(Comparator.comparing(Decided::at).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tab", tab);
        out.put("q", q);
        out.put("canLeave", canLeave);
        out.put("canReg", canReg);
        out.put("leaveCount", leaveCount);
        out.put("regCount", regCount);
        out.put("leaveCards", leaveCards);
        out.put("regCards", regCards);
        out.put("decided", decided);
        return out;
    }

    private static int count(Integer n) {
        return n == null ? 0 : n;
    }
}
