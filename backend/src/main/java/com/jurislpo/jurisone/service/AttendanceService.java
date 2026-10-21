package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Leave;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Regularization;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftDef;
import com.jurislpo.jurisone.engine.AttendanceLock;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.repository.AttendanceRepository;
import com.jurislpo.jurisone.repository.AttendanceRepository.HolidayRow;
import com.jurislpo.jurisone.repository.AttendanceRepository.LeaveRow;
import com.jurislpo.jurisone.repository.AttendanceRepository.PunchRow;
import com.jurislpo.jurisone.repository.AttendanceRepository.RegularizationRow;
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
    public record AttendanceEmployee(UUID id, String code, String name, String designation, String department, UUID departmentId, String timeZone, String region,
                              LocalDate dateOfJoining, LocalDate exitDate, Shift shift) {}

    public record Shift(String code, String name, String startLocal, String endLocal, int graceMinutes) {
        public ShiftDef def() { return new ShiftDef(startLocal, endLocal, graceMinutes); }
    }

    static final Shift DEFAULT_SHIFT = new Shift("IN_DAY", "India Day", "09:30", "18:30", 15);

    /** A computed day plus its punches (the TS DayView). */
    public static final class DayView {
        public final DayResult r;
        public final List<Punch> punches;

        DayView(DayResult dayResult, List<Punch> punches) {
            this.r = dayResult;
            this.punches = punches;
        }
    }

    /** The policy with the engine's shape plus lock settings. */
    public record ResolvedAttendancePolicy(Policy engine, LocalDate trackingStart, double shiftTargetHours, int lockDayOfNextMonth, String lockTime, AttendancePolicy raw) {}

    private final AttendanceRepository repo;
    private final SettingsService settings;

    public AttendanceService(AttendanceRepository repo, SettingsService settings) {
        this.repo = repo;
        this.settings = settings;
    }

    public ResolvedAttendancePolicy policy() {
        AttendancePolicy attendancePolicy = settings.attendance();
        Policy engine = new Policy(attendancePolicy.fullDayHours(), attendancePolicy.halfDayHours(), Set.copyOf(attendancePolicy.weeklyOffs()), attendancePolicy.absenceJobBufferMinutes(), attendancePolicy.lateMarksPerHalfDayLop());
        return new ResolvedAttendancePolicy(engine, LocalDate.parse(attendancePolicy.trackingStartDate()), attendancePolicy.shiftTargetHours(), attendancePolicy.lockDayOfNextMonth(), attendancePolicy.lockTime(), attendancePolicy);
    }

    public Instant lockInstant(String yearMonth, ResolvedAttendancePolicy resolvedAttendancePolicy) {
        return AttendanceLock.lockInstant(yearMonth, resolvedAttendancePolicy.lockDayOfNextMonth(), resolvedAttendancePolicy.lockTime());
    }

    public boolean isMonthLocked(String yearMonth, ResolvedAttendancePolicy resolvedAttendancePolicy, Instant now) {
        return AttendanceLock.isMonthLocked(yearMonth, resolvedAttendancePolicy.lockDayOfNextMonth(), resolvedAttendancePolicy.lockTime(), now);
    }

    /**
     * Tracked employees matching {@code where} (SQL on alias {@code e}), ordered by name.
     * @param where e.g. {@code "e.id = ?"}; may be empty
     */
    public List<AttendanceEmployee> loadAttendanceEmployees(String where, Object... args) {
        int grace = settings.attendance().graceMinutes(); // the business's grace period applies to every shift
        return repo.findAttendanceEmployees(where, DEFAULT_SHIFT, grace, args);
    }

    public LocalDate currentWorkDate(AttendanceEmployee employee, Instant now) {
        return AttendanceEngine.workDateOf(now, employee.shift().def(), employee.timeZone());
    }

    /** Day-by-day attendance for each employee over [from, to] (inclusive, work dates). */
    public Map<UUID, List<DayView>> attendanceFor(List<AttendanceEmployee> employees, LocalDate from, LocalDate to, Instant now) {
        Map<UUID, List<DayView>> out = new LinkedHashMap<>();
        if (employees.isEmpty() || from.isAfter(to)) return out;
        ResolvedAttendancePolicy policy = policy();
        UUID[] idArr = employees.stream().map(AttendanceEmployee::id).toArray(UUID[]::new);

        List<LeaveRow> leaves = repo.approvedLeaves(idArr, from, to);
        List<RegularizationRow> regularizations = repo.approvedRegularizations(idArr, from, to);
        List<PunchRow> punches = repo.punches(idArr, from.atStartOfDay(ZoneId.of("UTC")).toInstant().minusSeconds(36 * 3600),
                to.atStartOfDay(ZoneId.of("UTC")).toInstant().plusSeconds(60 * 3600));
        Map<String, Map<String, Object>> locked = repo.lockedDays(idArr, from, to);

        // Holidays across every leave range too: a leave's paid days are allocated from its first working day.
        LocalDate hFrom = from, hTo = to;
        for (LeaveRow leaveRow : leaves) {
            if (leaveRow.from().isBefore(hFrom)) hFrom = leaveRow.from();
            if (leaveRow.to().isAfter(hTo)) hTo = leaveRow.to();
        }
        List<HolidayRow> holidays = repo.holidays(hFrom, hTo);

        Map<UUID, AttendanceEmployee> byId = new HashMap<>();
        for (AttendanceEmployee e : employees) byId.put(e.id(), e);
        Map<UUID, Map<LocalDate, List<Punch>>> byEmployee = new HashMap<>();
        for (AttendanceEmployee e : employees) byEmployee.put(e.id(), new HashMap<>());
        for (PunchRow punchRow : punches) {
            AttendanceEmployee employee = byId.get(punchRow.employeeId());
            LocalDate wd = AttendanceEngine.workDateOf(punchRow.at(), employee.shift().def(), employee.timeZone());
            byEmployee.get(employee.id()).computeIfAbsent(wd, k -> new ArrayList<>()).add(new Punch(punchRow.at(), punchRow.kind(), punchRow.source()));
        }

        Map<UUID, Map<LocalDate, Double>> fractions = new HashMap<>(); // leave id → date → paid fraction
        for (LeaveRow leaveRow : leaves) {
            AttendanceEmployee employee = byId.get(leaveRow.employeeId());
            Map<LocalDate, String> hol = new HashMap<>();
            for (HolidayRow h : holidays) if ("ALL".equals(h.region()) || h.region().equals(employee.region())) hol.put(h.date(), h.name());
            List<LocalDate> dates = LeaveRules.workingDays(leaveRow.from(), leaveRow.to(), policy.engine().weeklyOffs(), hol).dates();
            double paidDays = leaveRow.paid() ? leaveRow.days().doubleValue() - leaveRow.lop().doubleValue() : 0;
            fractions.put(leaveRow.id(), LeaveRules.paidFractions(dates, leaveRow.part(), paidDays));
        }

        for (AttendanceEmployee employee : employees) {
            List<DayView> days = new ArrayList<>();
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                List<Punch> dayPunches = byEmployee.get(employee.id()).getOrDefault(date, List.of());
                Map<String, Object> row = locked.get(employee.id() + "|" + date);
                if (row != null) {
                    DayResult dayResult = new DayResult();
                    dayResult.date = date;
                    dayResult.status = DayStatus.valueOf((String) row.get("status"));
                    dayResult.label = row.get("label") == null ? "" : (String) row.get("label");
                    dayResult.firstIn = row.get("first_in") == null ? null : ((Timestamp) row.get("first_in")).toInstant();
                    dayResult.lastOut = row.get("last_out") == null ? null : ((Timestamp) row.get("last_out")).toInstant();
                    dayResult.workedMinutes = (Integer) row.get("worked_minutes");
                    dayResult.openSince = null;
                    dayResult.lateWaived = (Boolean) row.get("late_waived");
                    dayResult.late = (Boolean) row.get("late") && !dayResult.lateWaived;
                    dayResult.missedCheckout = (Boolean) row.get("missed_checkout");
                    dayResult.regularized = (Boolean) row.get("regularized");
                    dayResult.lopDays = ((BigDecimal) row.get("lop_days")).doubleValue();
                    dayResult.leaveDays = ((BigDecimal) row.get("leave_days")).doubleValue();
                    dayResult.live = false;
                    dayResult.future = false;
                    dayResult.offDayWork = false;
                    days.add(new DayView(dayResult, dayPunches));
                    continue;
                }
                LocalDate day = date;
                LeaveRow leaveRow = leaves.stream().filter(l -> l.employeeId().equals(employee.id()) && !l.from().isAfter(day) && !l.to().isBefore(day)).findFirst().orElse(null);
                List<RegularizationRow> regularizationRows = regularizations.stream().filter(x -> x.employeeId().equals(employee.id()) && x.date().equals(day)).toList();
                String holiday = holidays.stream().filter(h -> h.date().equals(day) && ("ALL".equals(h.region()) || h.region().equals(employee.region())))
                        .map(HolidayRow::name).findFirst().orElse(null);
                Leave leave = null;
                if (leaveRow != null) {
                    Double f = fractions.getOrDefault(leaveRow.id(), Map.of()).get(day);
                    leave = new Leave(leaveRow.code(), leaveRow.name(), leaveRow.part(), f != null ? f : (leaveRow.paid() ? 1 : 0));
                }
                Regularization reg = new Regularization(regularizationRows.stream().anyMatch(x -> x.kind().equals("ABSENT") || x.kind().equals("MISSED_CHECKOUT")),
                        regularizationRows.stream().anyMatch(x -> x.kind().equals("LATE_CHECKIN")));
                DayResult dayResult = AttendanceEngine.computeDay(new DayInput(date, now, employee.timeZone(), employee.shift().def(), policy.engine(), employee.dateOfJoining(), employee.exitDate(),
                        policy.trackingStart(), holiday, leave, dayPunches, reg));
                days.add(new DayView(dayResult, dayPunches));
            }
            out.put(employee.id(), days);
        }
        return out;
    }

    /** Persist computed days (finished days only unless locking). Locked rows are never rewritten. */
    public int materialize(List<AttendanceEmployee> employees, LocalDate from, LocalDate to, boolean lock, Instant now) {
        Map<UUID, List<DayView>> all = attendanceFor(employees, from, to, now);
        Set<String> isLocked = new HashSet<>();
        if (!employees.isEmpty()) {
            UUID[] idArr = employees.stream().map(AttendanceEmployee::id).toArray(UUID[]::new);
            isLocked = repo.lockedDayKeys(idArr, from, to);
        }
        int written = 0;
        for (Map.Entry<UUID, List<DayView>> entry : all.entrySet()) {
            for (DayView dayView : entry.getValue()) {
                DayResult dayResult = dayView.r;
                if (dayResult.future || (dayResult.live && !lock)) continue;
                if (isLocked.contains(entry.getKey() + "|" + dayResult.date)) continue;
                repo.upsertDay(entry.getKey(), dayResult, lock);
                written++;
            }
        }
        return written;
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    /** Intl en-US {hour numeric, minute 2-digit}: "9:31 AM"; "—" for none. */
    public static String formatTime(Instant at, String timeZone) {
        return at == null ? "—" : TIME.format(at.atZone(ZoneId.of(timeZone)));
    }
}
