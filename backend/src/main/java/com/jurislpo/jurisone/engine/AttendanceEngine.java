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

    public record Punch(Instant at, PunchKind kind, String source) {
        public Punch(Instant at, PunchKind kind) { this(at, kind, null); }
    }

    public enum LeavePart { FULL, FIRST_HALF, SECOND_HALF }

    /** Approved leave covering a date. {@code paidFraction}: 1 = paid from balance, 0 = loss of pay. */
    public record Leave(String code, String name, LeavePart part, double paidFraction) {}

    /** Approved regularizations for a date. */
    public record Regularization(boolean absent, boolean lateWaived) {
        public static final Regularization NONE = new Regularization(false, false);
    }

    public record DayInput(
            LocalDate date, Instant now, String timeZone, ShiftDef shift, Policy policy, LocalDate dateOfJoining, LocalDate exitDate,
            LocalDate trackingStart, String holiday, Leave leave, List<Punch> punches, Regularization regularization) {}

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

        DayResult copy() {
            DayResult dayResult = new DayResult();
            dayResult.date = date; dayResult.status = status; dayResult.label = label; dayResult.firstIn = firstIn; dayResult.lastOut = lastOut;
            dayResult.workedMinutes = workedMinutes; dayResult.openSince = openSince; dayResult.late = late; dayResult.lateWaived = lateWaived;
            dayResult.missedCheckout = missedCheckout; dayResult.regularized = regularized; dayResult.lopDays = lopDays; dayResult.leaveDays = leaveDays;
            dayResult.live = live; dayResult.future = future; dayResult.offDayWork = offDayWork;
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

    static int toMin(String time) {
        String[] p = time.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** Local calendar date + minutes-after-midnight of an instant in {@code tz}. */
    public record LocalParts(LocalDate date, int minutes, int dow) {}

    public static LocalParts localParts(Instant at, String timeZone) {
        ZonedDateTime z = at.atZone(ZoneId.of(timeZone));
        return new LocalParts(z.toLocalDate(), z.getHour() * 60 + z.getMinute(), dowOf(z.toLocalDate()));
    }

    /** 0 = Sunday … 6 = Saturday. */
    public static int dowOf(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : date.getDayOfWeek().getValue();
    }

    /** The instant of a local wall-clock time on {@code date} in {@code tz}. */
    public static Instant atLocal(LocalDate date, String time, String timeZone) {
        String[] p = time.split(":");
        return ZonedDateTime.of(date, LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1])), ZoneId.of(timeZone)).toInstant();
    }

    /** Which work date a punch belongs to. */
    public static LocalDate workDateOf(Instant at, ShiftDef shift, String timeZone) {
        LocalParts lp = localParts(at, timeZone);
        int anchor = (toMin(shift.startLocal()) - WINDOW_LEAD_MIN + 1440) % 1440;
        return lp.minutes() < anchor ? lp.date().minusDays(1) : lp.date();
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
        List<Punch> ps = sorted(punches);
        long millis = 0;
        Instant open = null;
        for (Punch punch : ps) {
            if (punch.kind() == PunchKind.IN) {
                if (open == null) open = punch.at();
            } else if (open != null) {
                millis += Duration.between(open, punch.at()).toMillis();
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
        List<Punch> ps = new ArrayList<>(punches);
        ps.sort(Comparator.comparing(Punch::at)); // stable, like Array.prototype.sort
        return ps;
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
        double[] s = leaveSplit(leave);
        dayResult.leaveDays = s[0];
        dayResult.lopDays = s[1];
        return dayResult;
    }

    public static DayResult computeDay(DayInput i) {
        LocalDate today = localParts(i.now(), i.timeZone()).date();
        DayResult base = new DayResult();
        base.date = i.date();
        base.future = i.date().isAfter(today);
        if (i.date().isBefore(i.trackingStart())) return base.with(DayStatus.NOT_TRACKED, "Not tracked");
        if (i.exitDate() != null && i.date().isAfter(i.exitDate())) return base.with(DayStatus.AFTER_EXIT, "After exit");
        if (i.date().isBefore(i.dateOfJoining())) return base.with(DayStatus.BEFORE_JOINING, "Before joining");

        ShiftWindow win = shiftWindow(i.date(), i.shift(), i.timeZone());
        boolean dayOver = !i.now().isBefore(win.end().plus(Duration.ofMinutes(i.policy().absenceJobBufferMinutes())));
        List<Punch> sorted = sorted(i.punches());
        Instant firstIn = sorted.stream().filter(p -> p.kind() == PunchKind.IN).map(Punch::at).findFirst().orElse(null);
        Instant lastOut = null;
        for (int k = sorted.size() - 1; k >= 0; k--) if (sorted.get(k).kind() == PunchKind.OUT) { lastOut = sorted.get(k).at(); break; }
        Worked worked = workedMinutes(sorted, dayOver ? null : i.now());
        boolean lastIsIn = !sorted.isEmpty() && sorted.get(sorted.size() - 1).kind() == PunchKind.IN;
        boolean rawLate = firstIn != null && firstIn.isAfter(win.graceUntil());

        // the TS "punchFacts" spread
        DayResult facts = base.copy();
        facts.firstIn = firstIn;
        facts.lastOut = lastOut;
        facts.workedMinutes = worked.minutes();
        facts.openSince = worked.openSince();
        facts.late = rawLate && !i.regularization().lateWaived();
        facts.lateWaived = rawLate && i.regularization().lateWaived();
        facts.missedCheckout = lastIsIn && dayOver;
        facts.live = !dayOver && !base.future;

        // Off days: never leave or LOP; work on them is a comp-off candidate.
        String off = i.holiday() != null ? i.holiday() : (i.policy().weeklyOffs().contains(dowOf(i.date())) ? "Weekly off" : null);
        if (off != null) {
            DayResult dayResult = facts.with(i.holiday() != null ? DayStatus.HOLIDAY : DayStatus.WEEKLY_OFF, off);
            dayResult.late = false;
            dayResult.lateWaived = false;
            dayResult.offDayWork = worked.minutes() >= i.policy().halfDayHours() * 60;
            return dayResult;
        }
        if (base.future) {
            if (i.leave() != null) {
                Leave l = i.leave();
                return withLeave(base.with(DayStatus.LEAVE, l.part() == LeavePart.FULL ? l.name() : l.name() + " · half day"), l);
            }
            return base.with(DayStatus.PENDING, "");
        }

        double full = i.policy().fullDayHours() * 60;
        double half = i.policy().halfDayHours() * 60;

        if (i.leave() != null && i.leave().part() == LeavePart.FULL) {
            DayResult dayResult = withLeave(facts.with(DayStatus.LEAVE, i.leave().name()), i.leave());
            dayResult.late = false;
            return dayResult;
        }

        // Half-day leave: the other half needs half-day hours.
        if (i.leave() != null) {
            boolean shortDay = dayOver && worked.minutes() < half; // the working half wasn't worked
            boolean regularized = shortDay && i.regularization().absent();
            double[] s = leaveSplit(i.leave());
            DayResult dayResult = facts.with(DayStatus.LEAVE, i.leave().name() + " · half day");
            dayResult.regularized = regularized;
            dayResult.leaveDays = s[0];
            dayResult.lopDays = s[1] + (shortDay && !regularized ? 0.5 : 0);
            return dayResult;
        }

        if (sorted.isEmpty()) {
            if (i.regularization().absent()) {
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
        if (i.regularization().absent() && worked.minutes() < full) {
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
            summary.absent += dayResult.lopDays;
            summary.workedMinutes += dayResult.workedMinutes;
            if (dayResult.late) summary.lateMarks += 1;
        }
        summary.lateLopDays = Math.floorDiv(summary.lateMarks, policy.lateMarksPerHalfDayLop()) * 0.5;
        summary.lopDays = summary.absent + summary.lateLopDays;
        return summary;
    }
}
