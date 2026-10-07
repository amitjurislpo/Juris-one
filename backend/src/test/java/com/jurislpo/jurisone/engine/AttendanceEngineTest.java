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

    static LocalDate d(String iso) { return LocalDate.parse(iso); }
    static Instant at(String date, String hm) { return atLocal(d(date), hm, IST); }
    static Instant at(String date, String hm, String tz) { return atLocal(d(date), hm, tz); }
    static Punch p(String date, String hm, PunchKind kind) { return new Punch(at(date, hm), kind); }
    static Punch p(String date, String hm, PunchKind kind, String tz) { return new Punch(at(date, hm, tz), kind); }
    static final PunchKind IN = PunchKind.IN, OUT = PunchKind.OUT;

    /** The TS `day({...})` helper: defaults, overridable per field. */
    static final class Day {
        LocalDate date = d("2026-09-16");
        Instant now = at("2026-10-06", "12:00");
        String tz = IST;
        ShiftDef shift = NIGHT;
        LocalDate doj = d("2025-01-01");
        LocalDate exitDate = null;
        String holiday = null;
        Leave leave = null;
        List<Punch> punches = List.of();
        Reg reg = Reg.NONE;

        Day date(String v) { date = d(v); return this; }
        Day now(Instant v) { now = v; return this; }
        Day tz(String v) { tz = v; return this; }
        Day shift(ShiftDef v) { shift = v; return this; }
        Day doj(String v) { doj = d(v); return this; }
        Day exit(String v) { exitDate = d(v); return this; }
        Day holiday(String v) { holiday = v; return this; }
        Day leave(Leave v) { leave = v; return this; }
        Day punches(Punch... v) { punches = List.of(v); return this; }
        Day reg(boolean absent, boolean lateWaived) { reg = new Reg(absent, lateWaived); return this; }

        DayResult compute() {
            return computeDay(new DayInput(date, now, tz, shift, POLICY, doj, exitDate, d("2026-04-01"), holiday, leave, punches, reg));
        }
    }

    static Day day() { return new Day(); }

    @Nested
    @DisplayName("night shift attribution")
    class NightShift {
        @Test
        @DisplayName("assigns a 2 AM check-out to the shift that started the evening before")
        void attributesNightPunches() {
            assertThat(workDateOf(at("2026-09-17", "02:00"), NIGHT, IST)).isEqualTo(d("2026-09-16"));
            assertThat(workDateOf(at("2026-09-16", "19:25"), NIGHT, IST)).isEqualTo(d("2026-09-16"));
            assertThat(workDateOf(at("2026-09-16", "15:29"), NIGHT, IST)).isEqualTo(d("2026-09-15"));
        }

        @Test
        @DisplayName("day shift: an early 6 AM check-in is the same day")
        void earlyDayCheckIn() {
            assertThat(workDateOf(at("2026-09-16", "06:00"), DAY, IST)).isEqualTo(d("2026-09-16"));
        }

        @Test
        @DisplayName("night window ends the next morning")
        void nightWindow() {
            ShiftWindow w = shiftWindow(d("2026-09-16"), NIGHT, IST);
            assertThat(w.start()).isEqualTo(Instant.parse("2026-09-16T14:00:00Z"));
            assertThat(w.end()).isEqualTo(Instant.parse("2026-09-16T23:00:00Z"));
            assertThat(w.graceUntil()).isEqualTo(Instant.parse("2026-09-16T14:15:00Z"));
        }
    }

    @Nested
    @DisplayName("worked time")
    class WorkedTime {
        @Test
        @DisplayName("sums multiple pairs")
        void sumsPairs() {
            List<Punch> ps = List.of(p("2026-09-16", "19:30", IN), p("2026-09-16", "23:30", OUT), p("2026-09-17", "00:00", IN), p("2026-09-17", "04:45", OUT));
            assertThat(workedMinutes(ps).minutes()).isEqualTo(4 * 60 + 4 * 60 + 45);
        }

        @Test
        @DisplayName("counts an open check-in up to now while live")
        void openCheckIn() {
            Worked r = workedMinutes(List.of(p("2026-10-06", "09:30", IN)), at("2026-10-06", "11:00"));
            assertThat(r.minutes()).isEqualTo(90);
            assertThat(r.openSince()).isNotNull();
        }
    }

    @Nested
    @DisplayName("day status")
    class DayStatusTests {
        final Punch[] full = {p("2026-09-16", "19:31", IN), p("2026-09-17", "04:40", OUT)};

        @Test
        @DisplayName("present when 8h+ is worked")
        void present() {
            DayResult r = day().punches(full).compute();
            assertThat(r.status).isEqualTo(DayStatus.PRESENT);
            assertThat(r.late).isFalse();
            assertThat(r.lopDays).isZero();
        }

        @Test
        @DisplayName("late after the 15-minute grace")
        void late() {
            assertThat(day().punches(p("2026-09-16", "19:46", IN), p("2026-09-17", "04:50", OUT)).compute().late).isTrue();
            assertThat(day().punches(p("2026-09-16", "19:45", IN), p("2026-09-17", "04:50", OUT)).compute().late).isFalse();
        }

        @Test
        @DisplayName("a regularized late mark is waived")
        void lateWaived() {
            DayResult r = day().punches(p("2026-09-16", "20:10", IN), p("2026-09-17", "05:00", OUT)).reg(false, true).compute();
            assertThat(r.late).isFalse();
            assertThat(r.lateWaived).isTrue();
        }

        @Test
        @DisplayName("half day between 4h and 8h; absent below 4h")
        void halfAndAbsent() {
            DayResult half = day().punches(p("2026-09-16", "19:30", IN), p("2026-09-17", "01:00", OUT)).compute();
            assertThat(half.status).isEqualTo(DayStatus.HALF_DAY);
            assertThat(half.lopDays).isEqualTo(0.5);
            DayResult absent = day().punches(p("2026-09-16", "19:30", IN), p("2026-09-16", "22:00", OUT)).compute();
            assertThat(absent.status).isEqualTo(DayStatus.ABSENT);
            assertThat(absent.lopDays).isEqualTo(1);
        }

        @Test
        @DisplayName("no check-in: pending until shift end + buffer, then absent (LOP)")
        void noCheckIn() {
            // night shift of 6 Oct ends 4:30 AM on 7 Oct; buffer 2h → 6:30 AM
            assertThat(day().date("2026-10-06").now(at("2026-10-07", "06:00")).compute().status).isEqualTo(DayStatus.PENDING);
            DayResult r = day().date("2026-10-06").now(at("2026-10-07", "06:31")).compute();
            assertThat(r.status).isEqualTo(DayStatus.ABSENT);
            assertThat(r.lopDays).isEqualTo(1);
        }

        @Test
        @DisplayName("an approved absence regularization makes the day paid")
        void absenceRegularized() {
            DayResult r = day().reg(true, false).compute();
            assertThat(r.status).isEqualTo(DayStatus.PRESENT);
            assertThat(r.lopDays).isZero();
            assertThat(r.regularized).isTrue();
        }

        @Test
        @DisplayName("missed check-out is flagged")
        void missedCheckout() {
            DayResult r = day().punches(p("2026-09-16", "19:30", IN)).compute();
            assertThat(r.missedCheckout).isTrue();
            assertThat(r.status).isEqualTo(DayStatus.ABSENT);
        }

        @Test
        @DisplayName("in progress while checked in today")
        void inProgress() {
            DayResult r = day().date("2026-10-06").shift(DAY).now(at("2026-10-06", "12:00")).punches(p("2026-10-06", "09:31", IN)).compute();
            assertThat(r.status).isEqualTo(DayStatus.PENDING);
            assertThat(r.label).isEqualTo("In progress");
            assertThat(r.live).isTrue();
            assertThat(r.workedMinutes).isEqualTo(149);
        }

        @Test
        @DisplayName("holidays and weekly offs are never leave or LOP")
        void offDays() {
            DayResult hol = day().date("2026-10-02").holiday("Gandhi Jayanti").compute();
            assertThat(hol.status).isEqualTo(DayStatus.HOLIDAY);
            assertThat(hol.lopDays).isZero();
            assertThat(hol.leaveDays).isZero();
            DayResult wo = day().date("2026-09-19").compute();
            assertThat(wo.status).isEqualTo(DayStatus.WEEKLY_OFF);
            assertThat(wo.lopDays).isZero();
        }

        @Test
        @DisplayName("holiday work of 4h+ is a comp-off candidate")
        void holidayWork() {
            DayResult r = day().date("2026-10-02").holiday("Gandhi Jayanti").punches(p("2026-10-02", "19:30", IN), p("2026-10-03", "04:10", OUT)).compute();
            assertThat(r.offDayWork).isTrue();
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
            DayResult worked = day().leave(half).punches(p("2026-09-16", "23:30", IN), p("2026-09-17", "04:00", OUT)).compute();
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
            assertThat(day().date("2026-09-07").doj("2026-09-08").compute().status).isEqualTo(DayStatus.BEFORE_JOINING);
            assertThat(day().date("2026-09-01").exit("2026-08-31").compute().status).isEqualTo(DayStatus.AFTER_EXIT);
            assertThat(day().date("2026-03-31").compute().status).isEqualTo(DayStatus.NOT_TRACKED);
        }

        @Test
        @DisplayName("future days are not judged")
        void future() {
            DayResult r = day().date("2026-10-20").compute();
            assertThat(r.status).isEqualTo(DayStatus.PENDING);
            assertThat(r.future).isTrue();
            assertThat(r.lopDays).isZero();
        }
    }

    @Nested
    @DisplayName("US time zones")
    class UsTimeZones {
        @Test
        @DisplayName("uses the employee's local shift, including DST")
        void dst() {
            assertThat(shiftWindow(d("2026-09-16"), US, NY).start()).isEqualTo(Instant.parse("2026-09-16T13:00:00Z")); // EDT
            assertThat(shiftWindow(d("2026-12-16"), US, NY).start()).isEqualTo(Instant.parse("2026-12-16T14:00:00Z")); // EST
            DayResult r = day().tz(NY).shift(US).punches(p("2026-09-16", "09:20", IN, NY), p("2026-09-16", "18:05", OUT, NY)).compute();
            assertThat(r.status).isEqualTo(DayStatus.PRESENT);
            assertThat(r.late).isTrue();
        }
    }

    @Nested
    @DisplayName("month summary")
    class Summary {
        @Test
        @DisplayName("3 late marks = ½ day LOP")
        void lateMarks() {
            DayResult late = day().punches(p("2026-09-16", "20:00", IN), p("2026-09-17", "05:00", OUT)).compute();
            MonthSummary s = summarize(List.of(late, late, late, late), POLICY);
            assertThat(s.lateMarks).isEqualTo(4);
            assertThat(s.lateLopDays).isEqualTo(0.5);
            assertThat(s.lopDays).isEqualTo(0.5);
            assertThat(s.present).isEqualTo(4);
        }
    }
}
