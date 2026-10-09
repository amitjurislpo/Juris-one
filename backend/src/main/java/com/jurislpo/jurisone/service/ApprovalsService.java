package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.LeaveRules;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approvals (REQUIREMENTS §5.6/§5.4; src/app/(app)/approvals): leave by HR (appr.leave), regularizations by HR, Finance or
 * Admin (appr.reg). The first decision wins — an atomic {@code UPDATE … WHERE status = 'PENDING'}.
 */
@Service
public class ApprovalsService {

    static final Map<String, String> KIND = Map.of("ABSENT", "Absence regularization", "LATE_CHECKIN", "Late check-in regularization",
            "MISSED_CHECKOUT", "Missed check-out regularization", "HOLIDAY_WORK", "Comp-off request");
    static final Map<String, String> KIND_SHORT = Map.of("ABSENT", "Absent", "LATE_CHECKIN", "Late check-in", "MISSED_CHECKOUT", "Missed check-out", "HOLIDAY_WORK", "Holiday work");
    static final Map<String, String> PART = Map.of("FULL", "Full day", "FIRST_HALF", "First half", "SECOND_HALF", "Second half");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AttendanceService att;
    private final LeaveService leave;
    private final AuditService audit;
    private final NotificationService notify;

    public ApprovalsService(JdbcTemplate jdbc, TransactionTemplate tx, AttendanceService att, LeaveService leave, AuditService audit, NotificationService notify) {
        this.jdbc = jdbc;
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
        List<Map<String, Object>> rs = jdbc.queryForList("""
            SELECT l.employee_id, l.leave_type, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.status::text AS status, t.name AS type_name,
                   e.full_name FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type JOIN employees e ON e.id = l.employee_id WHERE l.id = ?""", d.id());
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
            int n = jdbc.update("UPDATE leave_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = ?, lop_days = ? WHERE id = ? AND status = 'PENDING'",
                    d.approve() ? "APPROVED" : "DECLINED", u.id(), Timestamp.from(Instant.now()), BigDecimal.valueOf(lop), d.id());
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
        List<Map<String, Object>> rs = jdbc.queryForList("""
            SELECT r.employee_id, r.work_date, r.kind::text AS kind, r.status::text AS status, r.proposed_in, r.proposed_out, e.full_name
            FROM regularization_requests r JOIN employees e ON e.id = r.employee_id WHERE r.id = ?""", d.id());
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
            int n = jdbc.update("UPDATE regularization_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = ?, decided_role = ? WHERE id = ? AND status = 'PENDING'",
                    d.approve() ? "APPROVED" : "DECLINED", u.id(), Timestamp.from(Instant.now()), u.roleName(), d.id());
            if (n == 0) throw new UserException("Someone else decided this request a moment ago.");
            if (d.approve() && "MISSED_CHECKOUT".equals(kind)) {
                if (proposedIn != null) {
                    jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, created_at) VALUES (?, ?, ?, 'IN', 'REGULARIZATION', now())", UUID.randomUUID(), employeeId, proposedIn);
                }
                jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, created_at) VALUES (?, ?, ?, 'OUT', 'REGULARIZATION', now())", UUID.randomUUID(), employeeId, proposedOut);
            }
            if (co > 0) {
                Integer expiry = jdbc.queryForObject("SELECT expiry_days FROM leave_types WHERE code = 'CO'", Integer.class);
                jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, expires_on, note, at) VALUES (?, ?, 'CO', ?, 'COMP_OFF_CREDIT', ?, ?, ?, now())",
                        UUID.randomUUID(), employeeId, BigDecimal.valueOf(co), d.id().toString(), Date.valueOf(date.plusDays(expiry != null ? expiry : 60)), "Worked on " + date);
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
            List<Map<String, Object>> leaves = jdbc.queryForList("""
                SELECT l.id, l.employee_id, l.leave_type, t.name AS type_name, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.reason,
                       e.full_name, e.department_id, d.name AS dept
                FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type JOIN employees e ON e.id = l.employee_id LEFT JOIN departments d ON d.id = e.department_id
                WHERE l.status = 'PENDING' AND l.employee_id <> ?""" + nameFilter + " ORDER BY l.from_date ASC", args.toArray());
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
                    Integer n = jdbc.queryForObject("""
                        SELECT count(*) FROM leave_requests o JOIN employees oe ON oe.id = o.employee_id
                        WHERE o.id <> ? AND o.employee_id <> ? AND o.status IN ('PENDING', 'APPROVED') AND oe.department_id = ? AND o.from_date <= ? AND o.to_date >= ?""",
                            Integer.class, l.get("id"), empId, l.get("department_id"), Date.valueOf(t), Date.valueOf(f));
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
            List<Map<String, Object>> regs = jdbc.queryForList("""
                SELECT r.id, r.employee_id, r.work_date, r.kind::text AS kind, r.proposed_in, r.proposed_out, r.reason, e.full_name, e.time_zone, d.name AS dept
                FROM regularization_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN departments d ON d.id = e.department_id
                WHERE r.status = 'PENDING' AND r.employee_id <> ?""" + nameFilter + " ORDER BY r.work_date ASC", args.toArray());
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

        int leaveCount = canLeave ? count("SELECT count(*) FROM leave_requests WHERE status = 'PENDING' AND employee_id <> ?", u.id()) : 0;
        int regCount = canReg ? count("SELECT count(*) FROM regularization_requests WHERE status = 'PENDING' AND employee_id <> ?", u.id()) : 0;

        Timestamp since = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        List<Decided> decided = new ArrayList<>();
        if ("leave".equals(tab) && canLeave) {
            jdbc.query("""
                SELECT l.id::text, l.decided_at, e.full_name, t.name, l.from_date, l.to_date, l.status::text, dd.full_name AS by
                FROM leave_requests l JOIN employees e ON e.id = l.employee_id JOIN leave_types t ON t.code = l.leave_type LEFT JOIN employees dd ON dd.id = l.decided_by
                WHERE l.status IN ('APPROVED', 'DECLINED') AND l.decided_at >= ? ORDER BY l.decided_at DESC LIMIT 15""", rs -> {
                LocalDate f = rs.getDate(5).toLocalDate(), t = rs.getDate(6).toLocalDate();
                Instant at = rs.getTimestamp(2).toInstant();
                decided.add(new Decided(rs.getString(1), at, Format.fmtDateTime(at, u.timeZone()), rs.getString(3),
                        rs.getString(4) + " · " + Format.fmtD(f) + (!t.equals(f) ? " – " + Format.fmtD(t) : ""), "APPROVED".equals(rs.getString(7)),
                        rs.getString(8) == null ? "—" : rs.getString(8)));
            }, since);
        }
        if ("att".equals(tab) && canReg) {
            jdbc.query("""
                SELECT r.id::text, r.decided_at, e.full_name, r.kind::text, r.work_date, r.status::text, dd.full_name AS by, r.decided_role
                FROM regularization_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN employees dd ON dd.id = r.decided_by
                WHERE r.status IN ('APPROVED', 'DECLINED') AND r.decided_at >= ? ORDER BY r.decided_at DESC LIMIT 15""", rs -> {
                Instant at = rs.getTimestamp(2).toInstant();
                decided.add(new Decided(rs.getString(1), at, Format.fmtDateTime(at, u.timeZone()), rs.getString(3),
                        KIND_SHORT.get(rs.getString(4)) + " · " + Format.fmtD(rs.getDate(5).toLocalDate()), "APPROVED".equals(rs.getString(6)),
                        (rs.getString(7) == null ? "—" : rs.getString(7)) + (rs.getString(8) != null ? " (" + rs.getString(8) + ")" : "")));
            }, since);
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

    private int count(String sql, UUID id) {
        Integer n = jdbc.queryForObject(sql, Integer.class, id);
        return n == null ? 0 : n;
    }
}
