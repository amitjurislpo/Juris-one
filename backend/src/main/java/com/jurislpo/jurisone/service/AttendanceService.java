package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Leave;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Reg;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftDef;
import com.jurislpo.jurisone.engine.AttendanceLock;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.service.SettingsService.AttendancePolicy;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Loads the data for the attendance engine and runs it — src/lib/attendance/service.ts. Home, My attendance,
 * My team, the register and payroll all read attendance through here. Locked days come from
 * {@code attendance_days} as frozen; everything else is computed live.
 */
@Service
public class AttendanceService {

    /** A tracked employee (role has `self`). */
    public record AttEmployee(UUID id, String code, String name, String designation, String department, UUID departmentId, String tz, String region,
                              LocalDate doj, LocalDate exit, Shift shift) {}

    public record Shift(String code, String name, String startLocal, String endLocal, int graceMinutes) {
        public ShiftDef def() { return new ShiftDef(startLocal, endLocal, graceMinutes); }
    }

    static final Shift DEFAULT_SHIFT = new Shift("IN_DAY", "India Day", "09:30", "18:30", 15);

    /** A computed day plus its punches (the TS DayView). */
    public static final class DayView {
        public final DayResult r;
        public final List<Punch> punches;

        DayView(DayResult r, List<Punch> punches) {
            this.r = r;
            this.punches = punches;
        }
    }

    /** The policy with the engine's shape plus lock settings. */
    public record Pol(Policy engine, LocalDate trackingStart, double shiftTargetHours, int lockDayOfNextMonth, String lockTime, AttendancePolicy raw) {}

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public AttendanceService(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Pol policy() {
        AttendancePolicy p = settings.attendance();
        Policy engine = new Policy(p.fullDayHours(), p.halfDayHours(), Set.copyOf(p.weeklyOffs()), p.absenceJobBufferMinutes(), p.lateMarksPerHalfDayLop());
        return new Pol(engine, LocalDate.parse(p.trackingStartDate()), p.shiftTargetHours(), p.lockDayOfNextMonth(), p.lockTime(), p);
    }

    public Instant lockInstant(String ym, Pol p) {
        return AttendanceLock.lockInstant(ym, p.lockDayOfNextMonth(), p.lockTime());
    }

    public boolean isMonthLocked(String ym, Pol p, Instant now) {
        return AttendanceLock.isMonthLocked(ym, p.lockDayOfNextMonth(), p.lockTime(), now);
    }

    static final String TRACKED = "EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = e.role_id AND rp.permission_key = 'self')";

    /**
     * Tracked employees matching {@code where} (SQL on alias {@code e}), ordered by name.
     * @param where e.g. {@code "e.id = ?"}; may be empty
     */
    public List<AttEmployee> loadAttEmployees(String where, Object... args) {
        int grace = settings.attendance().graceMinutes(); // the business's grace period applies to every shift
        String sql = """
            SELECT e.id, e.code, e.full_name, e.designation, d.name AS dept, e.department_id, e.time_zone, e.region::text AS region,
                   e.date_of_joining, e.exit_date, s.code AS s_code, s.name AS s_name, s.start_local, s.end_local, s.grace_minutes
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN shifts s ON s.id = e.shift_id
            WHERE""" + " " + TRACKED + (where == null || where.isBlank() ? "" : " AND (" + where + ")") + " ORDER BY e.full_name ASC";
        return jdbc.query(sql, (rs, i) -> new AttEmployee(
                (UUID) rs.getObject("id"), rs.getString("code"), rs.getString("full_name"), rs.getString("designation"),
                rs.getString("dept") == null ? "—" : rs.getString("dept"), (UUID) rs.getObject("department_id"), rs.getString("time_zone"),
                rs.getString("region"), rs.getDate("date_of_joining").toLocalDate(), rs.getDate("exit_date") == null ? null : rs.getDate("exit_date").toLocalDate(),
                rs.getString("s_code") == null ? new Shift(DEFAULT_SHIFT.code(), DEFAULT_SHIFT.name(), DEFAULT_SHIFT.startLocal(), DEFAULT_SHIFT.endLocal(), grace)
                        : new Shift(rs.getString("s_code"), rs.getString("s_name"), rs.getString("start_local"), rs.getString("end_local"), grace)),
                args);
    }

    public LocalDate currentWorkDate(AttEmployee e, Instant now) {
        return AttendanceEngine.workDateOf(now, e.shift().def(), e.tz());
    }

    /** Day-by-day attendance for each employee over [from, to] (inclusive, work dates). */
    public Map<UUID, List<DayView>> attendanceFor(List<AttEmployee> emps, LocalDate from, LocalDate to, Instant now) {
        Map<UUID, List<DayView>> out = new LinkedHashMap<>();
        if (emps.isEmpty() || from.isAfter(to)) return out;
        Pol policy = policy();
        UUID[] idArr = emps.stream().map(AttEmployee::id).toArray(UUID[]::new);

        record LeaveRow(UUID id, UUID employeeId, String code, String name, boolean paid, LocalDate from, LocalDate to, LeavePart part, BigDecimal days, BigDecimal lop) {}
        List<LeaveRow> leaves = jdbc.query("""
            SELECT l.id, l.employee_id, l.leave_type, t.name, t.paid, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days
            FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type
            WHERE l.employee_id = ANY (?) AND l.status = 'APPROVED' AND l.from_date <= ? AND l.to_date >= ?""",
                (rs, i) -> new LeaveRow((UUID) rs.getObject("id"), (UUID) rs.getObject("employee_id"), rs.getString("leave_type"), rs.getString("name"),
                        rs.getBoolean("paid"), rs.getDate("from_date").toLocalDate(), rs.getDate("to_date").toLocalDate(), LeavePart.valueOf(rs.getString("part")),
                        rs.getBigDecimal("days"), rs.getBigDecimal("lop_days")),
                idArr, Date.valueOf(to), Date.valueOf(from));
        record RegRow(UUID employeeId, LocalDate date, String kind) {}
        List<RegRow> regs = jdbc.query("""
            SELECT employee_id, work_date, kind::text AS kind FROM regularization_requests
            WHERE employee_id = ANY (?) AND status = 'APPROVED' AND work_date >= ? AND work_date <= ?""",
                (rs, i) -> new RegRow((UUID) rs.getObject(1), rs.getDate(2).toLocalDate(), rs.getString(3)), idArr, Date.valueOf(from), Date.valueOf(to));
        record PunchRow(UUID employeeId, Instant at, PunchKind kind, String source) {}
        List<PunchRow> punches = jdbc.query("""
            SELECT employee_id, at, kind::text AS kind, source::text AS source FROM punches
            WHERE employee_id = ANY (?) AND at >= ? AND at < ? ORDER BY at ASC""",
                (rs, i) -> new PunchRow((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant(), PunchKind.valueOf(rs.getString(3)), rs.getString(4)),
                idArr, Timestamp.from(from.atStartOfDay(ZoneId.of("UTC")).toInstant().minusSeconds(36 * 3600)),
                Timestamp.from(to.atStartOfDay(ZoneId.of("UTC")).toInstant().plusSeconds(60 * 3600)));
        Map<String, Map<String, Object>> locked = new HashMap<>();
        jdbc.query("""
            SELECT employee_id, work_date, status::text AS status, label, first_in, last_out, worked_minutes, late, late_waived, missed_checkout, regularized,
                   lop_days, leave_days FROM attendance_days WHERE employee_id = ANY (?) AND locked AND work_date >= ? AND work_date <= ?""",
                rs -> {
                    Map<String, Object> m = new HashMap<>();
                    for (String c : List.of("status", "label", "first_in", "last_out", "worked_minutes", "late", "late_waived", "missed_checkout", "regularized", "lop_days", "leave_days")) {
                        m.put(c, rs.getObject(c));
                    }
                    locked.put(rs.getObject("employee_id") + "|" + rs.getDate("work_date").toLocalDate(), m);
                }, idArr, Date.valueOf(from), Date.valueOf(to));

        // Holidays across every leave range too: a leave's paid days are allocated from its first working day.
        LocalDate hFrom = from, hTo = to;
        for (LeaveRow l : leaves) {
            if (l.from().isBefore(hFrom)) hFrom = l.from();
            if (l.to().isAfter(hTo)) hTo = l.to();
        }
        record Hol(LocalDate date, String name, String region) {}
        List<Hol> holidays = jdbc.query("SELECT date, name, region::text FROM holidays WHERE date >= ? AND date <= ?",
                (rs, i) -> new Hol(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3)), Date.valueOf(hFrom), Date.valueOf(hTo));

        Map<UUID, AttEmployee> byId = new HashMap<>();
        for (AttEmployee e : emps) byId.put(e.id(), e);
        Map<UUID, Map<LocalDate, List<Punch>>> byEmp = new HashMap<>();
        for (AttEmployee e : emps) byEmp.put(e.id(), new HashMap<>());
        for (PunchRow p : punches) {
            AttEmployee e = byId.get(p.employeeId());
            LocalDate wd = AttendanceEngine.workDateOf(p.at(), e.shift().def(), e.tz());
            byEmp.get(e.id()).computeIfAbsent(wd, k -> new ArrayList<>()).add(new Punch(p.at(), p.kind(), p.source()));
        }

        Map<UUID, Map<LocalDate, Double>> fractions = new HashMap<>(); // leave id → date → paid fraction
        for (LeaveRow l : leaves) {
            AttEmployee e = byId.get(l.employeeId());
            Map<LocalDate, String> hol = new HashMap<>();
            for (Hol h : holidays) if ("ALL".equals(h.region()) || h.region().equals(e.region())) hol.put(h.date(), h.name());
            List<LocalDate> dates = LeaveRules.workingDays(l.from(), l.to(), policy.engine().weeklyOffs(), hol).dates();
            double paidDays = l.paid() ? l.days().doubleValue() - l.lop().doubleValue() : 0;
            fractions.put(l.id(), LeaveRules.paidFractions(dates, l.part(), paidDays));
        }

        for (AttEmployee e : emps) {
            List<DayView> days = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                List<Punch> ps = byEmp.get(e.id()).getOrDefault(d, List.of());
                Map<String, Object> lr = locked.get(e.id() + "|" + d);
                if (lr != null) {
                    DayResult r = new DayResult();
                    r.date = d;
                    r.status = DayStatus.valueOf((String) lr.get("status"));
                    r.label = lr.get("label") == null ? "" : (String) lr.get("label");
                    r.firstIn = lr.get("first_in") == null ? null : ((Timestamp) lr.get("first_in")).toInstant();
                    r.lastOut = lr.get("last_out") == null ? null : ((Timestamp) lr.get("last_out")).toInstant();
                    r.workedMinutes = (Integer) lr.get("worked_minutes");
                    r.openSince = null;
                    r.lateWaived = (Boolean) lr.get("late_waived");
                    r.late = (Boolean) lr.get("late") && !r.lateWaived;
                    r.missedCheckout = (Boolean) lr.get("missed_checkout");
                    r.regularized = (Boolean) lr.get("regularized");
                    r.lopDays = ((BigDecimal) lr.get("lop_days")).doubleValue();
                    r.leaveDays = ((BigDecimal) lr.get("leave_days")).doubleValue();
                    r.live = false;
                    r.future = false;
                    r.offDayWork = false;
                    days.add(new DayView(r, ps));
                    continue;
                }
                LocalDate day = d;
                LeaveRow lv = leaves.stream().filter(l -> l.employeeId().equals(e.id()) && !l.from().isAfter(day) && !l.to().isBefore(day)).findFirst().orElse(null);
                List<RegRow> rs = regs.stream().filter(x -> x.employeeId().equals(e.id()) && x.date().equals(day)).toList();
                String holiday = holidays.stream().filter(h -> h.date().equals(day) && ("ALL".equals(h.region()) || h.region().equals(e.region())))
                        .map(Hol::name).findFirst().orElse(null);
                Leave leave = null;
                if (lv != null) {
                    Double f = fractions.getOrDefault(lv.id(), Map.of()).get(day);
                    leave = new Leave(lv.code(), lv.name(), lv.part(), f != null ? f : (lv.paid() ? 1 : 0));
                }
                Reg reg = new Reg(rs.stream().anyMatch(x -> x.kind().equals("ABSENT") || x.kind().equals("MISSED_CHECKOUT")),
                        rs.stream().anyMatch(x -> x.kind().equals("LATE_CHECKIN")));
                DayResult r = AttendanceEngine.computeDay(new DayInput(d, now, e.tz(), e.shift().def(), policy.engine(), e.doj(), e.exit(),
                        policy.trackingStart(), holiday, leave, ps, reg));
                days.add(new DayView(r, ps));
            }
            out.put(e.id(), days);
        }
        return out;
    }

    /** Persist computed days (finished days only unless locking). Locked rows are never rewritten. */
    public int materialize(List<AttEmployee> emps, LocalDate from, LocalDate to, boolean lock, Instant now) {
        Map<UUID, List<DayView>> all = attendanceFor(emps, from, to, now);
        Set<String> isLocked = new HashSet<>();
        if (!emps.isEmpty()) {
            UUID[] idArr = emps.stream().map(AttEmployee::id).toArray(UUID[]::new);
            jdbc.query("SELECT employee_id, work_date FROM attendance_days WHERE employee_id = ANY (?) AND locked AND work_date >= ? AND work_date <= ?",
                    rs -> { isLocked.add(rs.getObject(1) + "|" + rs.getDate(2).toLocalDate()); }, idArr, Date.valueOf(from), Date.valueOf(to));
        }
        int written = 0;
        for (Map.Entry<UUID, List<DayView>> en : all.entrySet()) {
            for (DayView v : en.getValue()) {
                DayResult d = v.r;
                if (d.future || (d.live && !lock)) continue;
                if (isLocked.contains(en.getKey() + "|" + d.date)) continue;
                jdbc.update("""
                    INSERT INTO attendance_days (employee_id, work_date, status, label, first_in, last_out, worked_minutes, late, late_waived, regularized,
                      missed_checkout, lop_days, leave_days, locked, updated_at)
                    VALUES (?, ?, ?::"DayStatus", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (employee_id, work_date) DO UPDATE SET status = EXCLUDED.status, label = EXCLUDED.label, first_in = EXCLUDED.first_in,
                      last_out = EXCLUDED.last_out, worked_minutes = EXCLUDED.worked_minutes, late = EXCLUDED.late, late_waived = EXCLUDED.late_waived,
                      regularized = EXCLUDED.regularized, missed_checkout = EXCLUDED.missed_checkout, lop_days = EXCLUDED.lop_days,
                      leave_days = EXCLUDED.leave_days, locked = EXCLUDED.locked, updated_at = now()""",
                        en.getKey(), Date.valueOf(d.date), d.status.name(), d.label, ts(d.firstIn), ts(d.lastOut), d.workedMinutes, d.late || d.lateWaived,
                        d.lateWaived, d.regularized, d.missedCheckout, BigDecimal.valueOf(d.lopDays), BigDecimal.valueOf(d.leaveDays), lock);
                written++;
            }
        }
        return written;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** Intl en-US {hour numeric, minute 2-digit}: "9:31 AM"; "—" for none. */
    public static String fmtTime(Instant at, String tz) {
        return at == null ? "—" : TIME.format(at.atZone(ZoneId.of(tz)));
    }
}
