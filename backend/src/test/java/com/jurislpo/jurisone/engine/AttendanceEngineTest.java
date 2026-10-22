package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.AttendanceEngine.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/attendance-engine.test.ts — same cases, same expectations. */
class AttendanceEngineTest {

    static final String IST = "Asia/Kolkata";
    static final String NY = "America/New_York";
    static final ShiftDef NIGHT = new ShiftDef("19:30", "04:30", 15);
    static final ShiftDef DAY = new ShiftDef("09:30", "18:30", 15);
    static final ShiftDef US = new ShiftDef("09:00", "18:00", 15);
    static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);

    static LocalDate parseDate(String isoDate) { return LocalDate.parse(isoDate); }
    static Instant instantAt(String date, String time) { return atLocal(parseDate(date), time, IST); }
    static Instant instantAt(String date, String time, String timeZone) { return atLocal(parseDate(date), time, timeZone); }
    static Punch punchAt(String date, String time, PunchKind kind) { return new Punch(instantAt(date, time), kind); }
    static Punch punchAt(String date, String time, PunchKind kind, String timeZone) { return new Punch(instantAt(date, time, timeZone), kind); }
    static final PunchKind IN = PunchKind.IN, OUT = PunchKind.OUT;

    /** The TS `day({...})` helper: defaults, overridable per field. */
    static final class Day {
        LocalDate date = parseDate("2026-09-16");
        Instant now = instantAt("2026-10-06", "12:00");
        String timeZone = IST;
        ShiftDef shift = NIGHT;
        LocalDate dateOfJoining = parseDate("2025-01-01");
        LocalDate exitDate = null;
        String holiday = null;
        Leave leave = null;
        List<Punch> punches = List.of();
        Regularization regularization = Regularization.NONE;

        Day date(String text) { date = parseDate(text); return this; }
        Day now(Instant instant) { now = instant; return this; }
        Day timeZone(String zone) { timeZone = zone; return this; }
        Day shift(ShiftDef shiftDef) { shift = shiftDef; return this; }
        Day dateOfJoining(String text) { dateOfJoining = parseDate(text); return this; }
        Day exitDate(String text) { exitDate = parseDate(text); return this; }
        Day holiday(String name) { holiday = name; return this; }
        Day leave(Leave value) { leave = value; return this; }
        Day punches(Punch... v) { punches = List.of(v); return this; }
        Day regularization(boolean absent, boolean lateWaived) { regularization = new Regularization(absent, lateWaived); return this; }

        DayResult compute() {
            return computeDay(new DayInput(date, now, timeZone, shift, POLICY, dateOfJoining, exitDate, parseDate("2026-04-01"), holiday, leave, punches, regularization));
        }
    }

    static Day day() { return new Day(); }

    @Nested
    @DisplayName("night shift attribution")
    class NightShift {
        @Test
        @DisplayName("assigns a 2 AM check-out to the shift that started the evening before")
        void attributesNightPunches() {
            assertThat(workDateOf(instantAt("2026-09-17", "02:00"), NIGHT, IST)).isEqualTo(parseDate("2026-09-16"));
            assertThat(workDateOf(instantAt("2026-09-16", "19:25"), NIGHT, IST)).isEqualTo(parseDate("2026-09-16"));
            assertThat(workDateOf(instantAt("2026-09-16", "15:29"), NIGHT, IST)).isEqualTo(parseDate("2026-09-15"));
        }

        @Test
        @DisplayName("day shift: an early 6 AM check-in is the same day")
        void earlyDayCheckIn() {
            assertThat(workDateOf(instantAt("2026-09-16", "06:00"), DAY, IST)).isEqualTo(parseDate("2026-09-16"));
        }

        @Test
        @DisplayName("night window ends the next morning")
        void nightWindow() {
            ShiftWindow shiftWindow = shiftWindow(parseDate("2026-09-16"), NIGHT, IST);
            assertThat(shiftWindow.start()).isEqualTo(Instant.parse("2026-09-16T14:00:00Z"));
            assertThat(shiftWindow.end()).isEqualTo(Instant.parse("2026-09-16T23:00:00Z"));
            assertThat(shiftWindow.graceUntil()).isEqualTo(Instant.parse("2026-09-16T14:15:00Z"));
        }
    }

    @Nested
    @DisplayName("worked time")
    class WorkedTime {
        @Test
        @DisplayName("sums multiple pairs")
        void sumsPairs() {
            List<Punch> punches = List.of(punchAt("2026-09-16", "19:30", IN), punchAt("2026-09-16", "23:30", OUT), punchAt("2026-09-17", "00:00", IN), punchAt("2026-09-17", "04:45", OUT));
            assertThat(workedMinutes(punches).minutes()).isEqualTo(4 * 60 + 4 * 60 + 45);
        }

        @Test
        @DisplayName("counts an open check-in up to now while live")
        void openCheckIn() {
            Worked worked = workedMinutes(List.of(punchAt("2026-10-06", "09:30", IN)), instantAt("2026-10-06", "11:00"));
            assertThat(worked.minutes()).isEqualTo(90);
            assertThat(worked.openSince()).isNotNull();
        }
    }

    @Nested
    @DisplayName("day status")
    class DayStatusTests {
        final Punch[] full = {punchAt("2026-09-16", "19:31", IN), punchAt("2026-09-17", "04:40", OUT)};

        @Test
        @DisplayName("present when 8h+ is worked")
        void present() {
            DayResult dayResult = day().punches(full).compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.PRESENT);
            assertThat(dayResult.late).isFalse();
            assertThat(dayResult.lopDays).isZero();
        }

        @Test
        @DisplayName("late after the 15-minute grace")
        void late() {
            assertThat(day().punches(punchAt("2026-09-16", "19:46", IN), punchAt("2026-09-17", "04:50", OUT)).compute().late).isTrue();
            assertThat(day().punches(punchAt("2026-09-16", "19:45", IN), punchAt("2026-09-17", "04:50", OUT)).compute().late).isFalse();
        }

        @Test
        @DisplayName("a regularized late mark is waived")
        void lateWaived() {
            DayResult dayResult = day().punches(punchAt("2026-09-16", "20:10", IN), punchAt("2026-09-17", "05:00", OUT)).regularization(false, true).compute();
            assertThat(dayResult.late).isFalse();
            assertThat(dayResult.lateWaived).isTrue();
        }

        @Test
        @DisplayName("half day between 4h and 8h; absent below 4h")
        void halfAndAbsent() {
            DayResult half = day().punches(punchAt("2026-09-16", "19:30", IN), punchAt("2026-09-17", "01:00", OUT)).compute();
            assertThat(half.status).isEqualTo(DayStatus.HALF_DAY);
            assertThat(half.lopDays).isEqualTo(0.5);
            DayResult absent = day().punches(punchAt("2026-09-16", "19:30", IN), punchAt("2026-09-16", "22:00", OUT)).compute();
            assertThat(absent.status).isEqualTo(DayStatus.ABSENT);
            assertThat(absent.lopDays).isEqualTo(1);
        }

        @Test
        @DisplayName("no check-in: pending until shift end + buffer, then absent (LOP)")
        void noCheckIn() {
            // night shift of 6 Oct ends 4:30 AM on 7 Oct; buffer 2h → 6:30 AM
            assertThat(day().date("2026-10-06").now(instantAt("2026-10-07", "06:00")).compute().status).isEqualTo(DayStatus.PENDING);
            DayResult dayResult = day().date("2026-10-06").now(instantAt("2026-10-07", "06:31")).compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.ABSENT);
            assertThat(dayResult.lopDays).isEqualTo(1);
        }

        @Test
        @DisplayName("an approved absence regularization makes the day paid")
        void absenceRegularized() {
            DayResult dayResult = day().regularization(true, false).compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.PRESENT);
            assertThat(dayResult.lopDays).isZero();
            assertThat(dayResult.regularized).isTrue();
        }

        @Test
        @DisplayName("missed check-out is flagged")
        void missedCheckout() {
            DayResult dayResult = day().punches(punchAt("2026-09-16", "19:30", IN)).compute();
            assertThat(dayResult.missedCheckout).isTrue();
            assertThat(dayResult.status).isEqualTo(DayStatus.ABSENT);
        }

        @Test
        @DisplayName("in progress while checked in today")
        void inProgress() {
            DayResult dayResult = day().date("2026-10-06").shift(DAY).now(instantAt("2026-10-06", "12:00")).punches(punchAt("2026-10-06", "09:31", IN)).compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.PENDING);
            assertThat(dayResult.label).isEqualTo("In progress");
            assertThat(dayResult.live).isTrue();
            assertThat(dayResult.workedMinutes).isEqualTo(149);
        }

        @Test
        @DisplayName("holidays and weekly offs are never leave or LOP")
        void offDays() {
            DayResult holidayResult = day().date("2026-10-02").holiday("Gandhi Jayanti").compute();
            assertThat(holidayResult.status).isEqualTo(DayStatus.HOLIDAY);
            assertThat(holidayResult.lopDays).isZero();
            assertThat(holidayResult.leaveDays).isZero();
            DayResult dayResult = day().date("2026-09-19").compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.WEEKLY_OFF);
            assertThat(dayResult.lopDays).isZero();
        }

        @Test
        @DisplayName("holiday work of 4h+ is a comp-off candidate")
        void holidayWork() {
            DayResult dayResult = day().date("2026-10-02").holiday("Gandhi Jayanti").punches(punchAt("2026-10-02", "19:30", IN), punchAt("2026-10-03", "04:10", OUT)).compute();
            assertThat(dayResult.offDayWork).isTrue();
        }

        @Test
        @DisplayName("leave: paid full day, unpaid LOP leave, and half-day leave")
        void leave() {
            DayResult paid = day().leave(new Leave("CL", "Casual leave", LeavePart.FULL, 1)).compute();
            assertThat(paid.status).isEqualTo(DayStatus.LEAVE);
            assertThat(paid.leaveDays).isEqualTo(1);
            assertThat(paid.lopDays).isZero();
            DayResult unpaid = day().leave(new Leave("LOP", "Leave without pay", LeavePart.FULL, 0)).compute();
            assertThat(unpaid.status).isEqualTo(DayStatus.LEAVE);
            assertThat(unpaid.leaveDays).isZero();
            assertThat(unpaid.lopDays).isEqualTo(1);
            Leave half = new Leave("CL", "Casual leave", LeavePart.FIRST_HALF, 1);
            DayResult worked = day().leave(half).punches(punchAt("2026-09-16", "23:30", IN), punchAt("2026-09-17", "04:00", OUT)).compute();
            assertThat(worked.leaveDays).isEqualTo(0.5);
            assertThat(worked.lopDays).isZero();
            DayResult notWorked = day().leave(half).compute();
            assertThat(notWorked.leaveDays).isEqualTo(0.5);
            assertThat(notWorked.lopDays).isEqualTo(0.5);
            // balance ran short: half of the day is paid, half is LOP
            DayResult shortBalance = day().leave(new Leave("CL", "Casual leave", LeavePart.FULL, 0.5)).compute();
            assertThat(shortBalance.leaveDays).isEqualTo(0.5);
            assertThat(shortBalance.lopDays).isEqualTo(0.5);
        }

        @Test
        @DisplayName("before joining, after exit and before go-live")
        void outsideEmployment() {
            assertThat(day().date("2026-09-07").dateOfJoining("2026-09-08").compute().status).isEqualTo(DayStatus.BEFORE_JOINING);
            assertThat(day().date("2026-09-01").exitDate("2026-08-31").compute().status).isEqualTo(DayStatus.AFTER_EXIT);
            assertThat(day().date("2026-03-31").compute().status).isEqualTo(DayStatus.NOT_TRACKED);
        }

        @Test
        @DisplayName("future days are not judged")
        void future() {
            DayResult dayResult = day().date("2026-10-20").compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.PENDING);
            assertThat(dayResult.future).isTrue();
            assertThat(dayResult.lopDays).isZero();
        }
    }

    @Nested
    @DisplayName("US time zones")
    class UsTimeZones {
        @Test
        @DisplayName("uses the employee's local shift, including DST")
        void daylightSavingDay() {
            assertThat(shiftWindow(parseDate("2026-09-16"), US, NY).start()).isEqualTo(Instant.parse("2026-09-16T13:00:00Z")); // EDT
            assertThat(shiftWindow(parseDate("2026-12-16"), US, NY).start()).isEqualTo(Instant.parse("2026-12-16T14:00:00Z")); // EST
            DayResult dayResult = day().timeZone(NY).shift(US).punches(punchAt("2026-09-16", "09:20", IN, NY), punchAt("2026-09-16", "18:05", OUT, NY)).compute();
            assertThat(dayResult.status).isEqualTo(DayStatus.PRESENT);
            assertThat(dayResult.late).isTrue();
        }
    }

    @Nested
    @DisplayName("month summary")
    class Summary {
        @Test
        @DisplayName("3 late marks = ½ day LOP")
        void lateMarks() {
            DayResult late = day().punches(punchAt("2026-09-16", "20:00", IN), punchAt("2026-09-17", "05:00", OUT)).compute();
            MonthSummary monthSummary = summarize(List.of(late, late, late, late), POLICY);
            assertThat(monthSummary.lateMarks).isEqualTo(4);
            assertThat(monthSummary.lateLopDays).isEqualTo(0.5);
            assertThat(monthSummary.lopDays).isEqualTo(0.5);
            assertThat(monthSummary.present).isEqualTo(4);
        }
    }
}
