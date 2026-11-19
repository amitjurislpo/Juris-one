package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Attendance engine — pure functions, no database (REQUIREMENTS §5.4).
 *
 * <p>Every date here is a <em>work date</em> in the employee's own IANA zone. A night shift belongs to the
 * date it started on: punches are attributed to the shift whose window contains them, where a window opens
 * 4 hours before shift start and lasts 24 hours.
 */
public final class AttendanceEngine {

    private AttendanceEngine() {}

    /** "19:30" start, "04:30" end, in the employee's own zone. */
    public record ShiftDef(String startLocal, String endLocal, int graceMinutes) {}

    /** {@code weeklyOffs}: 0 = Sunday … 6 = Saturday (JS getUTCDay numbering). */
    public record Policy(double fullDayHours, double halfDayHours, Set<Integer> weeklyOffs, int absenceJobBufferMinutes, int lateMarksPerHalfDayLop) {}

    public record Punch(Instant punchedAt, PunchKind kind, String source, String note) {
        public Punch(Instant instant, PunchKind kind, String source) { this(instant, kind, source, null); }
        public Punch(Instant instant, PunchKind kind) { this(instant, kind, null, null); }
    }

    public enum LeavePart { FULL, FIRST_HALF, SECOND_HALF }

    /** Approved leave covering a date. {@code paidFraction}: 1 = paid from balance, 0 = loss of pay. */
    public record Leave(String code, String name, LeavePart part, double paidFraction) {}

    /**
     * Approved regularizations for a date; {@code remoteWork}: an approved remote work request covers the whole date; {@code remoteHalf}: an approved
     * half-day remote work request covers one half of it (the other half is an ordinary working half).
     */
    public record Regularization(boolean absent, boolean lateWaived, boolean remoteWork, boolean remoteHalf) {
        public static final Regularization NONE = new Regularization(false, false, false, false);

        public Regularization(boolean absent, boolean lateWaived, boolean remoteWork) {
            this(absent, lateWaived, remoteWork, false);
        }

        public Regularization(boolean absent, boolean lateWaived) {
            this(absent, lateWaived, false, false);
        }
    }

    /**
     * {@code onDutyPart}: the approved On Duty request of the day (FULL = the whole day, a half = that half), or null. An On Duty day is a
     * present, payable day; it never overrides a holiday, a weekly off or leave.
     */
    public record DayInput(
            LocalDate date, Instant now, String timeZone, ShiftDef shift, Policy policy, LocalDate dateOfJoining, LocalDate exitDate,
            LocalDate trackingStart, String holiday, Leave leave, List<Punch> punches, Regularization regularization, LeavePart onDutyPart) {

        /** A day without On Duty. */
        public DayInput(LocalDate date, Instant now, String timeZone, ShiftDef shift, Policy policy, LocalDate dateOfJoining, LocalDate exitDate,
                        LocalDate trackingStart, String holiday, Leave leave, List<Punch> punches, Regularization regularization) {
            this(date, now, timeZone, shift, policy, dateOfJoining, exitDate, trackingStart, holiday, leave, punches, regularization, null);
        }
    }

    /** Mutable result, copied the way the TS code spreads objects. */
    public static final class DayResult {
        public LocalDate date;
        public DayStatus status = DayStatus.PENDING;
        public String label = "";
        public Instant firstIn;
        public Instant lastOut;
        public int workedMinutes;
        /** Open check-in (no check-out yet). */
        public Instant openSince;
        public boolean late;
        public boolean lateWaived;
        public boolean missedCheckout;
        public boolean regularized;
        public double lopDays;
        public double leaveDays;
        /** Day not finished yet (today, or future). */
        public boolean live;
        public boolean future;
        /** Worked on a holiday / weekly off (comp-off candidate). */
        public boolean offDayWork;
        /** The part of the day spent On Duty (approved request): 1, 0.5 or 0. It is inside the present days, not on top of them. */
        public double onDutyDays;

        DayResult copy() {
            DayResult dayResult = new DayResult();
            dayResult.date = date; dayResult.status = status; dayResult.label = label; dayResult.firstIn = firstIn; dayResult.lastOut = lastOut;
            dayResult.workedMinutes = workedMinutes; dayResult.openSince = openSince; dayResult.late = late; dayResult.lateWaived = lateWaived;
            dayResult.missedCheckout = missedCheckout; dayResult.regularized = regularized; dayResult.lopDays = lopDays; dayResult.leaveDays = leaveDays;
            dayResult.live = live; dayResult.future = future; dayResult.offDayWork = offDayWork; dayResult.onDutyDays = onDutyDays;
            return dayResult;
        }

        DayResult with(DayStatus status, String label) {
            DayResult dayResult = copy();
            dayResult.status = status;
            dayResult.label = label;
            return dayResult;
        }
    }

    // ───────────────────────── time-zone helpers ─────────────────────────

    public static final int WINDOW_LEAD_MIN = 240;
    /** The day label of an approved remote work day. */
    public static final String REMOTE_LABEL = "Remote";

    /** The day label of an approved half-day remote work request. */
    public static final String REMOTE_HALF_LABEL = "Remote · half day";

    static int toMin(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    /** Local calendar date + minutes-after-midnight of an instant in {@code tz}. */
    public record LocalParts(LocalDate date, int minutes, int dayOfWeek) {}

    public static LocalParts localParts(Instant instant, String timeZone) {
        ZonedDateTime zoned = instant.atZone(ZoneId.of(timeZone));
        return new LocalParts(zoned.toLocalDate(), zoned.getHour() * 60 + zoned.getMinute(), dowOf(zoned.toLocalDate()));
    }

    /** 0 = Sunday … 6 = Saturday. */
    public static int dowOf(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : date.getDayOfWeek().getValue();
    }

    /** The instant of a local wall-clock time on {@code date} in {@code tz}. */
    public static Instant atLocal(LocalDate date, String time, String timeZone) {
        String[] parts = time.split(":");
        return ZonedDateTime.of(date, LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), ZoneId.of(timeZone)).toInstant();
    }

    /** Which work date a punch belongs to. */
    public static LocalDate workDateOf(Instant instant, ShiftDef shift, String timeZone) {
        LocalParts parts = localParts(instant, timeZone);
        int anchor = (toMin(shift.startLocal()) - WINDOW_LEAD_MIN + 1440) % 1440;
        return parts.minutes() < anchor ? parts.date().minusDays(1) : parts.date();
    }

    public record ShiftWindow(Instant start, Instant graceUntil, Instant end) {}

    /** Shift start, grace deadline and end for a work date (end rolls to the next day for night shifts). */
    public static ShiftWindow shiftWindow(LocalDate date, ShiftDef shift, String timeZone) {
        Instant start = atLocal(date, shift.startLocal(), timeZone);
        boolean crosses = toMin(shift.endLocal()) <= toMin(shift.startLocal());
        Instant end = atLocal(crosses ? date.plusDays(1) : date, shift.endLocal(), timeZone);
        return new ShiftWindow(start, start.plus(Duration.ofMinutes(shift.graceMinutes())), end);
    }

    public record Worked(int minutes, Instant openSince) {}

    /** Sum of check-in → check-out pairs; an open check-in counts up to {@code liveUntil} if given. */
    public static Worked workedMinutes(List<Punch> punches, Instant liveUntil) {
        List<Punch> sortedPunches = sorted(punches);
        long millis = 0;
        Instant open = null;
        for (Punch punch : sortedPunches) {
            if (punch.kind() == PunchKind.IN) {
                if (open == null) open = punch.punchedAt();
            } else if (open != null) {
                millis += Duration.between(open, punch.punchedAt()).toMillis();
                open = null;
            }
        }
        if (open != null && liveUntil != null && liveUntil.isAfter(open)) millis += Duration.between(open, liveUntil).toMillis();
        return new Worked((int) Math.floorDiv(millis, 60_000L), open);
    }

    public static Worked workedMinutes(List<Punch> punches) {
        return workedMinutes(punches, null);
    }

    private static List<Punch> sorted(List<Punch> punches) {
        List<Punch> punchList = new ArrayList<>(punches);
        punchList.sort(Comparator.comparing(Punch::punchedAt)); // stable, like Array.prototype.sort
        return punchList;
    }

    // ───────────────────────── day status ─────────────────────────

    private static double round2(double value) {
        return Num.round2(value);
    }

    /** Leave days vs LOP days for one date of leave (half-day parts count 0.5). */
    private static double[] leaveSplit(Leave leave) {
        double unit = leave.part() == LeavePart.FULL ? 1 : 0.5;
        double paid = round2(unit * Math.min(1, Math.max(0, leave.paidFraction())));
        return new double[] {paid, round2(unit - paid)};
    }

    private static DayResult withLeave(DayResult dayResult, Leave leave) {
        double[] split = leaveSplit(leave);
        dayResult.leaveDays = split[0];
        dayResult.lopDays = split[1];
        return dayResult;
    }

    public static DayResult computeDay(DayInput dayInput) {
        LocalDate today = localParts(dayInput.now(), dayInput.timeZone()).date();
        DayResult base = new DayResult();
        base.date = dayInput.date();
        base.future = dayInput.date().isAfter(today);
        if (dayInput.date().isBefore(dayInput.trackingStart())) return base.with(DayStatus.NOT_TRACKED, "Not tracked");
        if (dayInput.exitDate() != null && dayInput.date().isAfter(dayInput.exitDate())) return base.with(DayStatus.AFTER_EXIT, "After exit");
        if (dayInput.date().isBefore(dayInput.dateOfJoining())) return base.with(DayStatus.BEFORE_JOINING, "Before joining");

        ShiftWindow window = shiftWindow(dayInput.date(), dayInput.shift(), dayInput.timeZone());
        boolean dayOver = !dayInput.now().isBefore(window.end().plus(Duration.ofMinutes(dayInput.policy().absenceJobBufferMinutes())));
        List<Punch> sorted = sorted(dayInput.punches());
        Instant firstIn = sorted.stream().filter(p -> p.kind() == PunchKind.IN).map(Punch::punchedAt).findFirst().orElse(null);
        Instant lastOut = null;
        for (int k = sorted.size() - 1; k >= 0; k--) if (sorted.get(k).kind() == PunchKind.OUT) { lastOut = sorted.get(k).punchedAt(); break; }
        Worked worked = workedMinutes(sorted, dayOver ? null : dayInput.now());
        boolean lastIsIn = !sorted.isEmpty() && sorted.get(sorted.size() - 1).kind() == PunchKind.IN;
        boolean rawLate = firstIn != null && firstIn.isAfter(window.graceUntil());

        // the TS "punchFacts" spread
        DayResult facts = base.copy();
        facts.firstIn = firstIn;
        facts.lastOut = lastOut;
        facts.workedMinutes = worked.minutes();
        facts.openSince = worked.openSince();
        boolean isRemote = dayInput.regularization().remoteWork();
        facts.late = rawLate && !dayInput.regularization().lateWaived() && !isRemote; // remote work has no shift-start check-in to be late for
        facts.lateWaived = rawLate && dayInput.regularization().lateWaived();
        facts.missedCheckout = lastIsIn && dayOver;
        facts.live = !dayOver && !base.future;

        // Off days: never leave or LOP; work on them is a comp-off candidate.
        String nonWorkingReason = dayInput.holiday() != null ? dayInput.holiday() : (dayInput.policy().weeklyOffs().contains(dowOf(dayInput.date())) ? "Weekly off" : null);
        if (nonWorkingReason != null) {
            DayResult dayResult = facts.with(dayInput.holiday() != null ? DayStatus.HOLIDAY : DayStatus.WEEKLY_OFF, nonWorkingReason);
            dayResult.late = false;
            dayResult.lateWaived = false;
            dayResult.offDayWork = worked.minutes() >= dayInput.policy().halfDayHours() * 60;
            return dayResult;
        }
        if (base.future) {
            if (dayInput.leave() != null) {
                Leave leave = dayInput.leave();
                return withLeave(base.with(DayStatus.LEAVE, leave.part() == LeavePart.FULL ? leave.name() : leave.name() + " · half day"), leave);
            }
            if (isRemote) return base.with(DayStatus.PENDING, REMOTE_LABEL);
            if (dayInput.regularization().remoteHalf()) return base.with(DayStatus.PENDING, REMOTE_HALF_LABEL);
            if (dayInput.onDutyPart() != null) {
                DayResult dayResult = base.with(DayStatus.PENDING, onDutyLabel(dayInput.onDutyPart()));
                dayResult.onDutyDays = onDutyDays(dayInput.onDutyPart());
                return dayResult;
            }
            return base.with(DayStatus.PENDING, "");
        }

        double full = dayInput.policy().fullDayHours() * 60;
        double half = dayInput.policy().halfDayHours() * 60;

        if (dayInput.leave() != null && dayInput.leave().part() == LeavePart.FULL) {
            DayResult dayResult = withLeave(facts.with(DayStatus.LEAVE, dayInput.leave().name()), dayInput.leave());
            dayResult.late = false;
            return dayResult;
        }

        // Half-day leave: the other half needs half-day hours.
        if (dayInput.leave() != null) {
            boolean shortDay = dayOver && worked.minutes() < half && !isRemote; // the working half wasn't worked (remote work counts as worked)
            boolean regularized = shortDay && dayInput.regularization().absent();
            double[] split = leaveSplit(dayInput.leave());
            DayResult dayResult = facts.with(DayStatus.LEAVE, dayInput.leave().name() + " · half day");
            dayResult.regularized = regularized;
            dayResult.leaveDays = split[0];
            dayResult.lopDays = split[1] + (shortDay && !regularized ? 0.5 : 0);
            return dayResult;
        }

        // An approved remote work day is a normal working day: Present (paid, no LOP) whether or not there is a punch.
        if (isRemote) return facts.with(DayStatus.PRESENT, REMOTE_LABEL);
        if (dayInput.regularization().remoteHalf()) return halfDayWorkedElsewhere(dayInput, facts, worked.minutes(), dayOver, half, REMOTE_HALF_LABEL);
        if (dayInput.onDutyPart() != null) return onDutyDay(dayInput, facts, worked.minutes(), dayOver, half);

        if (sorted.isEmpty()) {
            if (dayInput.regularization().absent()) {
                DayResult dayResult = base.with(DayStatus.PRESENT, "Regularized");
                dayResult.regularized = true;
                dayResult.live = false;
                return dayResult;
            }
            if (!dayOver) {
                DayResult dayResult = base.with(DayStatus.PENDING, "Not checked in");
                dayResult.live = true;
                return dayResult;
            }
            DayResult dayResult = base.with(DayStatus.ABSENT, "Absent · LOP");
            dayResult.lopDays = 1;
            return dayResult;
        }

        if (!dayOver && (lastIsIn || worked.minutes() < full)) {
            return facts.with(DayStatus.PENDING, lastIsIn ? "In progress" : "Checked out");
        }
        if (dayInput.regularization().absent() && worked.minutes() < full) {
            DayResult dayResult = facts.with(DayStatus.PRESENT, "Regularized");
            dayResult.regularized = true;
            return dayResult;
        }
        if (worked.minutes() >= full) return facts.with(DayStatus.PRESENT, "Present");
        if (worked.minutes() >= half) {
            DayResult dayResult = facts.with(DayStatus.HALF_DAY, "Half day");
            dayResult.lopDays = 0.5;
            return dayResult;
        }
        DayResult dayResult = facts.with(DayStatus.ABSENT, facts.missedCheckout ? "Missed check-out · LOP" : "Short day · LOP");
        dayResult.lopDays = 1;
        return dayResult;
    }

    /**
     * A working day of which one half is remote work: the remote half counts as worked, the other half needs half-day hours like the other half of
     * a half-day leave or On Duty; short of that it is a half day with 0.5 loss of pay (or paid in full when an absence regularization is approved).
     */
    private static DayResult halfDayWorkedElsewhere(DayInput dayInput, DayResult facts, int workedMinutes, boolean dayOver, double halfMinutes, String label) {
        if (workedMinutes >= halfMinutes) return facts.with(DayStatus.PRESENT, label);
        if (!dayOver) return facts.with(DayStatus.PENDING, label);
        if (dayInput.regularization().absent()) {
            DayResult dayResult = facts.with(DayStatus.PRESENT, label);
            dayResult.regularized = true;
            return dayResult;
        }
        DayResult dayResult = facts.with(DayStatus.HALF_DAY, label);
        dayResult.lopDays = 0.5;
        return dayResult;
    }

    private static double onDutyDays(LeavePart part) {
        return part == LeavePart.FULL ? 1 : 0.5;
    }

    private static String onDutyLabel(LeavePart part) {
        return part == LeavePart.FULL ? "On duty" : "On duty · half day";
    }

    /**
     * A working day with an approved On Duty request (no leave on it). Full day: present, whatever the punches say, and no late mark. Half day:
     * the other half needs half-day hours, like the other half of a half-day leave; short of that it is a half day with 0.5 loss of pay
     * (or paid in full when an absence regularization is approved).
     */
    private static DayResult onDutyDay(DayInput dayInput, DayResult facts, int workedMinutes, boolean dayOver, double halfMinutes) {
        LeavePart part = dayInput.onDutyPart();
        String label = onDutyLabel(part);
        DayResult dayResult;
        if (part == LeavePart.FULL) {
            dayResult = facts.with(DayStatus.PRESENT, label);
            dayResult.late = false;
            dayResult.lateWaived = false;
            dayResult.missedCheckout = false;
            dayResult.live = false;
        } else if (workedMinutes >= halfMinutes) {
            dayResult = facts.with(DayStatus.PRESENT, label);
        } else if (!dayOver) {
            dayResult = facts.with(DayStatus.PENDING, label);
        } else if (dayInput.regularization().absent()) {
            dayResult = facts.with(DayStatus.PRESENT, label);
            dayResult.regularized = true;
        } else {
            dayResult = facts.with(DayStatus.HALF_DAY, label);
            dayResult.lopDays = 0.5;
        }
        dayResult.onDutyDays = onDutyDays(part);
        return dayResult;
    }

    // ───────────────────────── month summary ─────────────────────────

    public static final class MonthSummary {
        public double present; // half days count 0.5
        public double absent; // LOP days from attendance (absences + half days + unpaid leave)
        public double leave;
        public int holidays;
        public int weeklyOffs;
        public long workedMinutes;
        public int lateMarks;
        public double lateLopDays;
        /** Days spent On Duty (already counted in {@code present}). */
        public double onDuty;
        public double lopDays; // absent + late-mark LOP
    }

    public static MonthSummary summarize(List<DayResult> days, Policy policy) {
        MonthSummary summary = new MonthSummary();
        for (DayResult dayResult : days) {
            if (dayResult.status == DayStatus.PRESENT) summary.present += 1;
            if (dayResult.status == DayStatus.HALF_DAY) summary.present += 0.5;
            if (dayResult.status == DayStatus.LEAVE && dayResult.workedMinutes >= policy.halfDayHours() * 60) summary.present += 0.5;
            if (dayResult.status == DayStatus.HOLIDAY) summary.holidays += 1;
            if (dayResult.status == DayStatus.WEEKLY_OFF) summary.weeklyOffs += 1;
            summary.leave += dayResult.leaveDays;
            summary.onDuty += dayResult.onDutyDays;
            summary.absent += dayResult.lopDays;
            summary.workedMinutes += dayResult.workedMinutes;
            if (dayResult.late) summary.lateMarks += 1;
        }
        summary.lateLopDays = Math.floorDiv(summary.lateMarks, policy.lateMarksPerHalfDayLop()) * 0.5;
        summary.lopDays = summary.absent + summary.lateLopDays;
        return summary;
    }

    // ───────────────────────── attendance info (day code, sessions, averages) ─────────────────────────

    /** One check-in → check-out pair of a day; {@code end} is null while the check-in is still open. */
    public record Session(Instant start, Instant end) {}

    /** The day's sessions in time order: each IN opens one, the next OUT closes it (an OUT without an open IN and an IN inside an open one are ignored, as in {@link #workedMinutes}). */
    public static List<Session> sessions(List<Punch> punches) {
        List<Session> sessions = new ArrayList<>();
        Instant open = null;
        for (Punch punch : sorted(punches)) {
            if (punch.kind() == PunchKind.IN) {
                if (open == null) open = punch.punchedAt();
            } else if (open != null) {
                sessions.add(new Session(open, punch.punchedAt()));
                open = null;
            }
        }
        if (open != null) sessions.add(new Session(open, null));
        return sessions;
    }

    /** Whole minutes of a session; an open one counts up to {@code liveUntil} (0 when that is null or not after the start). */
    public static int sessionMinutes(Session session, Instant liveUntil) {
        Instant end = session.end() != null ? session.end() : liveUntil;
        if (end == null || !end.isAfter(session.start())) return 0;
        return (int) Math.floorDiv(Duration.between(session.start(), end).toMillis(), 60_000L);
    }

    /** The minutes spent between one session's check-out and the next session's check-in, summed. */
    public static int breakMinutes(List<Session> sessions) {
        long millis = 0;
        for (int i = 1; i < sessions.size(); i++) {
            Instant previousEnd = sessions.get(i - 1).end();
            if (previousEnd != null && sessions.get(i).start().isAfter(previousEnd)) millis += Duration.between(previousEnd, sessions.get(i).start()).toMillis();
        }
        return (int) Math.floorDiv(millis, 60_000L);
    }

    /** First check-in → last check-out in whole minutes (0 when either is missing or the check-out is not after the check-in). */
    public static int totalMinutes(Instant firstIn, Instant lastOut) {
        if (firstIn == null || lastOut == null || !lastOut.isAfter(firstIn)) return 0;
        return (int) Math.floorDiv(Duration.between(firstIn, lastOut).toMillis(), 60_000L);
    }

    private static boolean isHalfDayLabel(String label) {
        return label != null && label.endsWith("half day");
    }

    /**
     * The minutes the day asks for, or null when it asks for none (weekly off, holiday, full leave, full On Duty, full remote work, days outside the
     * employment). A half day of leave / On Duty / remote work asks for half of the full day.
     */
    public static Integer requiredMinutes(DayResult day, Policy policy) {
        int full = (int) Math.round(policy.fullDayHours() * 60);
        switch (day.status) {
            case WEEKLY_OFF, HOLIDAY, NOT_TRACKED, BEFORE_JOINING, AFTER_EXIT -> { return null; }
            case LEAVE -> { return isHalfDayLabel(day.label) ? full / 2 : null; }
            default -> { }
        }
        if (day.onDutyDays >= 1 || REMOTE_LABEL.equals(day.label)) return null;
        if (day.onDutyDays > 0 || REMOTE_HALF_LABEL.equals(day.label)) return full / 2;
        return full;
    }

    /** Minutes short of what the day asks for; 0 when it asks for none, and while the day is still running or not yet here. */
    public static int shortfallMinutes(DayResult day, Policy policy) {
        Integer required = requiredMinutes(day, policy);
        if (required == null || day.future || day.live) return 0;
        return Math.max(0, required - day.workedMinutes);
    }

    /** Minutes worked beyond what the day asks for; 0 when it asks for none. */
    public static int excessMinutes(DayResult day, Policy policy) {
        Integer required = requiredMinutes(day, policy);
        if (required == null) return 0;
        return Math.max(0, day.workedMinutes - required);
    }

    /**
     * The short code of a day, as in the attendance calendar: P present, A absent, P:A / A:P half day (present in the first / second half),
     * O weekly off, H holiday, L leave, OD On Duty, and "" for a day that is not here yet or has no record. A half day is P:A when the first check-in
     * is before the middle of the shift (shift start to shift end), A:P otherwise.
     */
    public static String dayCode(DayResult day, ShiftWindow window) {
        switch (day.status) {
            case PRESENT: return day.label != null && day.label.startsWith("On duty") ? "OD" : "P";
            case HALF_DAY: {
                Instant middle = window.start().plus(Duration.between(window.start(), window.end()).dividedBy(2));
                return day.firstIn != null && !day.firstIn.isBefore(middle) ? "A:P" : "P:A";
            }
            case LEAVE: return "L";
            case ABSENT: return "A";
            case WEEKLY_OFF: return "O";
            case HOLIDAY: return "H";
            case PENDING:
                if (day.label != null && day.label.startsWith("On duty")) return "OD";
                return day.firstIn != null ? "P" : "";
            default: return "";
        }
    }

    /** WEEKLY_OFF, HOLIDAY, HALF_DAY, LEAVE, else WORKING. */
    public static String dayType(DayResult day) {
        return switch (day.status) {
            case WEEKLY_OFF -> "WEEKLY_OFF";
            case HOLIDAY -> "HOLIDAY";
            case HALF_DAY -> "HALF_DAY";
            case LEAVE -> "LEAVE";
            default -> "WORKING";
        };
    }

    /**
     * Which days (same order as {@code days}) carry a late mark that is part of a deduction: late marks are counted in date order and every complete group of
     * {@code lateMarksPerHalfDayLop} costs half a day, so the marks of a trailing incomplete group do not count yet.
     */
    public static boolean[] deductionAlerts(List<DayResult> days, int lateMarksPerHalfDayLop) {
        int lateMarks = 0;
        for (DayResult day : days) if (day.late) lateMarks++;
        int chargedMarks = Math.floorDiv(lateMarks, lateMarksPerHalfDayLop) * lateMarksPerHalfDayLop;
        boolean[] alerts = new boolean[days.size()];
        int seen = 0;
        for (int i = 0; i < days.size(); i++) {
            if (!days.get(i).late) continue;
            seen++;
            alerts[i] = seen <= chargedMarks;
        }
        return alerts;
    }

    /**
     * Whether the late grace period was used: first check-in after the shift start but not after the grace deadline, or a late mark that was waived.
     * False for days with no shift-start check-in to be late for (off days, leave, On Duty, remote work).
     */
    public static boolean graceUsed(DayResult day, ShiftWindow window) {
        if (!expectsShiftTimes(day)) return false;
        if (day.lateWaived) return true;
        return day.firstIn != null && day.firstIn.isAfter(window.start()) && !day.firstIn.isAfter(window.graceUntil());
    }

    /** Whether the day has a shift start and end to keep: not an off day, leave, On Duty or remote work. */
    private static boolean expectsShiftTimes(DayResult day) {
        if (day.status == DayStatus.WEEKLY_OFF || day.status == DayStatus.HOLIDAY || day.status == DayStatus.LEAVE || day.onDutyDays > 0) return false;
        return day.label == null || !day.label.startsWith(REMOTE_LABEL);
    }

    /** Minutes the first check-in came after the shift start (grace not deducted; 0 when on time, without a check-in, or on a day with no shift to keep). */
    public static int lateMinutes(DayResult day, ShiftWindow window) {
        if (!expectsShiftTimes(day) || day.firstIn == null || !day.firstIn.isAfter(window.start())) return 0;
        return (int) Math.floorDiv(Duration.between(window.start(), day.firstIn).toMillis(), 60_000L);
    }

    /** Minutes the last check-out came before the shift end (0 when not before it, without a check-out, while the day is still running, or on a day with no shift to keep). */
    public static int earlyMinutes(DayResult day, ShiftWindow window) {
        if (!expectsShiftTimes(day) || day.live || day.lastOut == null || !day.lastOut.isBefore(window.end())) return 0;
        return (int) Math.floorDiv(Duration.between(day.lastOut, window.end()).toMillis(), 60_000L);
    }

    /** Average first-in → last-out and average worked minutes per day, over the days that have both a check-in and a check-out. */
    public record AverageMinutes(int workMinutes, int actualMinutes) {}

    /** The averages over {@code days}, or null when no day has both a check-in and a check-out. */
    public static AverageMinutes averageMinutes(List<DayResult> days) {
        long total = 0, actual = 0;
        int counted = 0;
        for (DayResult day : days) {
            if (day.future || day.firstIn == null || day.lastOut == null || !day.lastOut.isAfter(day.firstIn)) continue;
            total += totalMinutes(day.firstIn, day.lastOut);
            actual += day.workedMinutes;
            counted++;
        }
        return counted == 0 ? null : new AverageMinutes((int) Math.round((double) total / counted), (int) Math.round((double) actual / counted));
    }
}
