package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.AttendanceEngine.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The attendance-info derivations: day code, sessions, break / shortfall / excess, late and early minutes, grace, deduction alerts and averages. */
class AttendanceInfoEngineTest {

    static final String IST = "Asia/Kolkata";
    static final ShiftDef DAY = new ShiftDef("09:30", "18:30", 15);
    static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);
    static final LocalDate DATE = LocalDate.parse("2026-09-02"); // a Wednesday, long over
    static final Instant NOW = atLocal(LocalDate.parse("2026-10-06"), "12:00", IST);
    static final PunchKind IN = PunchKind.IN, OUT = PunchKind.OUT;

    static Instant at(String time) { return atLocal(DATE, time, IST); }

    static Punch punch(String time, PunchKind kind) { return new Punch(at(time), kind); }

    static DayResult compute(Punch... punches) {
        return computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null, List.of(punches), Regularization.NONE));
    }

    static ShiftWindow window() { return shiftWindow(DATE, DAY, IST); }

    @Test
    void aHalfDayIsPresentAbsentWhenTheFirstCheckInIsBeforeTheMiddleOfTheShiftAndAbsentPresentOtherwise() {
        // shift 09:30-18:30: the middle is 14:00
        DayResult morning = compute(punch("09:30", IN), punch("13:45", OUT));
        assertThat(morning.status).isEqualTo(DayStatus.HALF_DAY);
        assertThat(dayCode(morning, window())).isEqualTo("P:A");
        assertThat(dayType(morning)).isEqualTo("HALF_DAY");

        DayResult afternoon = compute(punch("14:30", IN), punch("18:30", OUT));
        assertThat(afternoon.status).isEqualTo(DayStatus.HALF_DAY);
        assertThat(dayCode(afternoon, window())).isEqualTo("A:P");

        // exactly the middle counts as the second half
        assertThat(dayCode(compute(punch("14:00", IN), punch("18:00", OUT)), window())).isEqualTo("A:P");
        assertThat(dayCode(compute(punch("13:59", IN), punch("17:59", OUT)), window())).isEqualTo("P:A");
    }

    @Test
    void theOtherDayCodes() {
        assertThat(dayCode(compute(punch("09:30", IN), punch("18:30", OUT)), window())).isEqualTo("P");
        assertThat(dayCode(compute(), window())).isEqualTo("A");
        assertThat(dayCode(compute(punch("09:30", IN), punch("10:30", OUT)), window())).isEqualTo("A"); // short day
        // weekly off, holiday, leave
        LocalDate sunday = LocalDate.parse("2026-09-06");
        DayResult weeklyOff = computeDay(new DayInput(sunday, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null, List.of(), Regularization.NONE));
        assertThat(dayCode(weeklyOff, shiftWindow(sunday, DAY, IST))).isEqualTo("O");
        assertThat(dayType(weeklyOff)).isEqualTo("WEEKLY_OFF");
        DayResult holiday = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), "Founders day", null, List.of(), Regularization.NONE));
        assertThat(dayCode(holiday, window())).isEqualTo("H");
        assertThat(dayType(holiday)).isEqualTo("HOLIDAY");
        DayResult leave = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null,
                new Leave("CL", "Casual leave", LeavePart.FULL, 1), List.of(), Regularization.NONE));
        assertThat(dayCode(leave, window())).isEqualTo("L");
        assertThat(dayType(leave)).isEqualTo("LEAVE");
        // On Duty
        DayResult onDuty = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null, List.of(), Regularization.NONE, LeavePart.FULL));
        assertThat(dayCode(onDuty, window())).isEqualTo("OD");
        // a day that is not here yet has no code
        LocalDate future = LocalDate.parse("2026-12-02");
        DayResult tomorrow = computeDay(new DayInput(future, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null, List.of(), Regularization.NONE));
        assertThat(dayCode(tomorrow, shiftWindow(future, DAY, IST))).isEmpty();
        // before joining: no record
        DayResult beforeJoining = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2026-09-10"), null, LocalDate.parse("2026-04-01"), null, null, List.of(), Regularization.NONE));
        assertThat(dayCode(beforeJoining, window())).isEmpty();
        assertThat(dayType(beforeJoining)).isEqualTo("WORKING");
    }

    @Test
    void sessionsPairEachCheckInWithTheNextCheckOut() {
        List<Session> sessions = sessions(List.of(punch("13:00", OUT), punch("09:40", IN), punch("09:50", IN), punch("14:00", IN), punch("18:00", OUT), punch("18:10", OUT)));
        assertThat(sessions).hasSize(2); // the stray second IN and the stray last OUT are ignored
        assertThat(sessions.get(0)).isEqualTo(new Session(at("09:40"), at("13:00")));
        assertThat(sessions.get(1)).isEqualTo(new Session(at("14:00"), at("18:00")));
        assertThat(sessionMinutes(sessions.get(0), null)).isEqualTo(200);
        assertThat(sessionMinutes(sessions.get(1), null)).isEqualTo(240);
        assertThat(breakMinutes(sessions)).isEqualTo(60);
        // it agrees with the engine's worked minutes
        assertThat(workedMinutes(List.of(punch("09:40", IN), punch("13:00", OUT), punch("14:00", IN), punch("18:00", OUT))).minutes()).isEqualTo(440);
    }

    @Test
    void anOpenSessionCountsOnlyUntilTheGivenInstantAndHasNoBreakAfterIt() {
        List<Session> sessions = sessions(List.of(punch("09:30", IN), punch("12:00", OUT), punch("13:00", IN)));
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(1).end()).isNull();
        assertThat(sessionMinutes(sessions.get(1), null)).isZero();
        assertThat(sessionMinutes(sessions.get(1), at("15:30"))).isEqualTo(150);
        assertThat(sessionMinutes(sessions.get(1), at("12:30"))).isZero(); // "now" before the start never goes negative
        assertThat(breakMinutes(sessions)).isEqualTo(60);
        assertThat(sessions(List.of())).isEmpty();
        assertThat(breakMinutes(List.of())).isZero();
    }

    @Test
    void totalMinutesIsFirstInToLastOutAndNeverNegative() {
        assertThat(totalMinutes(at("09:40"), at("18:00"))).isEqualTo(500);
        assertThat(totalMinutes(at("09:40"), null)).isZero();
        assertThat(totalMinutes(null, at("18:00"))).isZero();
        assertThat(totalMinutes(at("18:00"), at("09:40"))).isZero();
    }

    @Test
    void shortfallAndExcessAreMeasuredAgainstTheFullDayAndNeverNegative() {
        DayResult shortDay = compute(punch("09:40", IN), punch("13:00", OUT), punch("14:00", IN), punch("18:00", OUT)); // 440 worked
        assertThat(shortfallMinutes(shortDay, POLICY)).isEqualTo(40);
        assertThat(excessMinutes(shortDay, POLICY)).isZero();

        DayResult longDay = compute(punch("09:30", IN), punch("19:30", OUT)); // 600 worked
        assertThat(excessMinutes(longDay, POLICY)).isEqualTo(120);
        assertThat(shortfallMinutes(longDay, POLICY)).isZero();

        DayResult exact = compute(punch("09:30", IN), punch("17:30", OUT));
        assertThat(shortfallMinutes(exact, POLICY)).isZero();
        assertThat(excessMinutes(exact, POLICY)).isZero();

        // an absent day is short by the whole day
        assertThat(shortfallMinutes(compute(), POLICY)).isEqualTo(480);
    }

    @Test
    void offDaysHolidaysAndFullLeaveAskForNothing() {
        LocalDate sunday = LocalDate.parse("2026-09-06");
        DayResult weeklyOff = computeDay(new DayInput(sunday, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null,
                List.of(new Punch(atLocal(sunday, "10:00", IST), IN), new Punch(atLocal(sunday, "20:00", IST), OUT)), Regularization.NONE));
        assertThat(requiredMinutes(weeklyOff, POLICY)).isNull();
        assertThat(shortfallMinutes(weeklyOff, POLICY)).isZero();
        assertThat(excessMinutes(weeklyOff, POLICY)).isZero(); // worked 600 minutes on a weekly off: no excess, that is comp-off material
        DayResult holiday = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), "Founders day", null, List.of(), Regularization.NONE));
        assertThat(shortfallMinutes(holiday, POLICY)).isZero();
        DayResult leave = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null,
                new Leave("CL", "Casual leave", LeavePart.FULL, 1), List.of(), Regularization.NONE));
        assertThat(shortfallMinutes(leave, POLICY)).isZero();
        assertThat(excessMinutes(leave, POLICY)).isZero();
        // a half day of leave asks for half a day
        DayResult halfLeave = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null,
                new Leave("CL", "Casual leave", LeavePart.FIRST_HALF, 1), List.of(punch("14:00", IN), punch("18:30", OUT)), Regularization.NONE));
        assertThat(requiredMinutes(halfLeave, POLICY)).isEqualTo(240);
        assertThat(shortfallMinutes(halfLeave, POLICY)).isZero();
    }

    @Test
    void aDayStillRunningHasNoShortfallYet() {
        Instant duringTheDay = at("11:00");
        DayResult running = computeDay(new DayInput(DATE, duringTheDay, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null,
                List.of(punch("09:30", IN)), Regularization.NONE));
        assertThat(running.live).isTrue();
        assertThat(shortfallMinutes(running, POLICY)).isZero();
    }

    @Test
    void lateAndEarlyMinutesAndTheGraceFlag() {
        DayResult withinGrace = compute(punch("09:40", IN), punch("18:00", OUT));
        assertThat(withinGrace.late).isFalse();
        assertThat(lateMinutes(withinGrace, window())).isEqualTo(10);
        assertThat(earlyMinutes(withinGrace, window())).isEqualTo(30);
        assertThat(graceUsed(withinGrace, window())).isTrue();

        DayResult onTime = compute(punch("09:30", IN), punch("18:30", OUT));
        assertThat(lateMinutes(onTime, window())).isZero();
        assertThat(earlyMinutes(onTime, window())).isZero();
        assertThat(graceUsed(onTime, window())).isFalse();

        DayResult late = compute(punch("10:00", IN), punch("18:30", OUT));
        assertThat(late.late).isTrue();
        assertThat(lateMinutes(late, window())).isEqualTo(30);
        assertThat(graceUsed(late, window())).isFalse(); // beyond the grace: that is a late mark, not a used grace

        // a waived late mark counts as used grace
        DayResult waived = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null,
                List.of(punch("10:00", IN), punch("18:30", OUT)), new Regularization(false, true)));
        assertThat(waived.late).isFalse();
        assertThat(graceUsed(waived, window())).isTrue();
    }

    @Test
    void daysWithoutAShiftToKeepHaveNoLateOrEarlyMinutes() {
        LocalDate sunday = LocalDate.parse("2026-09-06");
        DayResult weeklyOff = computeDay(new DayInput(sunday, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null,
                List.of(new Punch(atLocal(sunday, "11:00", IST), IN), new Punch(atLocal(sunday, "15:00", IST), OUT)), Regularization.NONE));
        ShiftWindow sundayWindow = shiftWindow(sunday, DAY, IST);
        assertThat(lateMinutes(weeklyOff, sundayWindow)).isZero();
        assertThat(earlyMinutes(weeklyOff, sundayWindow)).isZero();
        assertThat(graceUsed(weeklyOff, sundayWindow)).isFalse();
        DayResult remote = computeDay(new DayInput(DATE, NOW, IST, DAY, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), null, null,
                List.of(punch("09:40", IN), punch("18:00", OUT)), new Regularization(false, false, true)));
        assertThat(lateMinutes(remote, window())).isZero();
        assertThat(graceUsed(remote, window())).isFalse();
    }

    private static DayResult lateDay() {
        DayResult day = new DayResult();
        day.status = DayStatus.PRESENT;
        day.late = true;
        return day;
    }

    private static DayResult onTimeDay() {
        DayResult day = new DayResult();
        day.status = DayStatus.PRESENT;
        return day;
    }

    @Test
    void onlyTheLateMarksOfACompleteGroupAreADeduction() {
        // three late marks per half day of LOP: with 4 late marks the first 3 are charged, the 4th is not yet
        List<DayResult> days = List.of(lateDay(), onTimeDay(), lateDay(), lateDay(), onTimeDay(), lateDay());
        assertThat(deductionAlerts(days, 3)).containsExactly(true, false, true, true, false, false);
        // the summary agrees: 4 marks = half a day
        assertThat(summarize(days, POLICY).lateLopDays).isEqualTo(0.5);
        // fewer marks than a group: none is charged yet
        assertThat(deductionAlerts(List.of(lateDay(), lateDay(), onTimeDay()), 3)).containsExactly(false, false, false);
        // 6 marks = a full group twice
        assertThat(deductionAlerts(List.of(lateDay(), lateDay(), lateDay(), lateDay(), lateDay(), lateDay()), 3)).containsOnly(true);
        // one mark per half day: every mark is charged
        assertThat(deductionAlerts(List.of(lateDay(), onTimeDay(), lateDay()), 1)).containsExactly(true, false, true);
        assertThat(deductionAlerts(List.of(), 3)).isEmpty();
    }

    @Test
    void averagesAreOverTheDaysWithBothACheckInAndACheckOut() {
        DayResult halfDay = compute(punch("09:40", IN), punch("13:00", OUT), punch("14:00", IN), punch("18:00", OUT)); // total 500, actual 440
        DayResult fullDay = compute(punch("09:30", IN), punch("18:30", OUT)); // total 540, actual 540
        DayResult absent = compute();
        DayResult openOnly = compute(punch("09:30", IN)); // no check-out: not counted
        AverageMinutes average = averageMinutes(List.of(halfDay, fullDay, absent, openOnly));
        assertThat(average.workMinutes()).isEqualTo(520);
        assertThat(average.actualMinutes()).isEqualTo(490);
        assertThat(averageMinutes(List.of(absent, openOnly))).isNull();
        assertThat(averageMinutes(List.of())).isNull();
    }
}
