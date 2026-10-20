package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Leave;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Reg;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftDef;
import com.jurislpo.jurisone.engine.AttendanceLock;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.repository.AttendanceRepository;
import com.jurislpo.jurisone.repository.AttendanceRepository.Hol;
import com.jurislpo.jurisone.repository.AttendanceRepository.LeaveRow;
import com.jurislpo.jurisone.repository.AttendanceRepository.PunchRow;
import com.jurislpo.jurisone.repository.AttendanceRepository.RegRow;
import com.jurislpo.jurisone.service.SettingsService.AttendancePolicy;
import java.math.BigDecimal;
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
import org.springframework.stereotype.Service;

/**
 * Loads the data for the attendance engine and runs it. Home, My attendance,
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

    private final AttendanceRepository repo;
    private final SettingsService settings;

    public AttendanceService(AttendanceRepository repo, SettingsService settings) {
        this.repo = repo;
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

    /**
     * Tracked employees matching {@code where} (SQL on alias {@code e}), ordered by name.
     * @param where e.g. {@code "e.id = ?"}; may be empty
     */
    public List<AttEmployee> loadAttEmployees(String where, Object... args) {
        int grace = settings.attendance().graceMinutes(); // the business's grace period applies to every shift
        return repo.findAttEmployees(where, DEFAULT_SHIFT, grace, args);
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

        List<LeaveRow> leaves = repo.approvedLeaves(idArr, from, to);
        List<RegRow> regs = repo.approvedRegularizations(idArr, from, to);
        List<PunchRow> punches = repo.punches(idArr, from.atStartOfDay(ZoneId.of("UTC")).toInstant().minusSeconds(36 * 3600),
                to.atStartOfDay(ZoneId.of("UTC")).toInstant().plusSeconds(60 * 3600));
        Map<String, Map<String, Object>> locked = repo.lockedDays(idArr, from, to);

        // Holidays across every leave range too: a leave's paid days are allocated from its first working day.
        LocalDate hFrom = from, hTo = to;
        for (LeaveRow l : leaves) {
            if (l.from().isBefore(hFrom)) hFrom = l.from();
            if (l.to().isAfter(hTo)) hTo = l.to();
        }
        List<Hol> holidays = repo.holidays(hFrom, hTo);

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
            isLocked = repo.lockedDayKeys(idArr, from, to);
        }
        int written = 0;
        for (Map.Entry<UUID, List<DayView>> en : all.entrySet()) {
            for (DayView v : en.getValue()) {
                DayResult d = v.r;
                if (d.future || (d.live && !lock)) continue;
                if (isLocked.contains(en.getKey() + "|" + d.date)) continue;
                repo.upsertDay(en.getKey(), d, lock);
                written++;
            }
        }
        return written;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** Intl en-US {hour numeric, minute 2-digit}: "9:31 AM"; "—" for none. */
    public static String fmtTime(Instant at, String tz) {
        return at == null ? "—" : TIME.format(at.atZone(ZoneId.of(tz)));
    }
}
