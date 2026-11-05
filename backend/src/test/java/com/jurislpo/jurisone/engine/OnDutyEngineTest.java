package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.AttendanceEngine.atLocal;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Leave;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.AttendanceEngine.MonthSummary;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Regularization;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftDef;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** An approved On Duty day is a present, payable day: the engine's day status, the loss-of-pay days payroll reads, and the month summary stay consistent. */
class OnDutyEngineTest {

    static final String IST = "Asia/Kolkata";
    static final ShiftDef DAY_SHIFT = new ShiftDef("09:30", "18:30", 15);
    static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);
    static final Instant NOW = atLocal(LocalDate.parse("2026-10-06"), "12:00", IST); // a Tuesday
    static final String WEDNESDAY = "2026-09-16", SATURDAY = "2026-09-19", MONDAY_AFTER = "2026-10-12";

    static Instant at(String date, String time) {
        return atLocal(LocalDate.parse(date), time, IST);
    }

    static DayResult compute(String date, Instant now, String holiday, Leave leave, List<Punch> punches, Regularization regularization, LeavePart onDuty) {
        return AttendanceEngine.computeDay(new DayInput(LocalDate.parse(date), now, IST, DAY_SHIFT, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"),
                holiday, leave, punches, regularization, onDuty));
    }

    static DayResult day(String date, List<Punch> punches, LeavePart onDuty) {
        return compute(date, NOW, null, null, punches, Regularization.NONE, onDuty);
    }

    static List<Punch> worked(String date, String in, String out) {
        return List.of(new Punch(at(date, in), PunchKind.IN), new Punch(at(date, out), PunchKind.OUT));
    }

    @Test
    void aFullOnDutyDayWithNoPunchesIsAPresentPayableDayNotLossOfPay() {
        DayResult without = day(WEDNESDAY, List.of(), null);
        assertThat(without.status).isEqualTo(DayStatus.ABSENT);
        assertThat(without.lopDays).isEqualTo(1);

        DayResult onDuty = day(WEDNESDAY, List.of(), LeavePart.FULL);
        assertThat(onDuty.status).isEqualTo(DayStatus.PRESENT);
        assertThat(onDuty.label).isEqualTo("On duty");
        assertThat(onDuty.onDutyDays).isEqualTo(1);
        assertThat(onDuty.lopDays).isZero();
        assertThat(onDuty.live).isFalse();
    }

    @Test
    void aFullOnDutyDayIsPresentWhateverThePunchesSayAndHasNoLateMark() {
        DayResult shortAndLate = day(WEDNESDAY, worked(WEDNESDAY, "11:30", "13:00"), LeavePart.FULL);
        assertThat(shortAndLate.status).isEqualTo(DayStatus.PRESENT);
        assertThat(shortAndLate.late).isFalse();
        assertThat(shortAndLate.lopDays).isZero();
        assertThat(shortAndLate.onDutyDays).isEqualTo(1);

        DayResult missedCheckout = day(WEDNESDAY, List.of(new Punch(at(WEDNESDAY, "09:30"), PunchKind.IN)), LeavePart.FULL);
        assertThat(missedCheckout.status).isEqualTo(DayStatus.PRESENT);
        assertThat(missedCheckout.missedCheckout).isFalse();
    }

    @Test
    void onDutyNeverOverridesAHolidayAWeeklyOffOrLeave() {
        DayResult holiday = compute(WEDNESDAY, NOW, "Founders day", null, List.of(), Regularization.NONE, LeavePart.FULL);
        assertThat(holiday.status).isEqualTo(DayStatus.HOLIDAY);
        assertThat(holiday.onDutyDays).isZero();

        DayResult weeklyOff = day(SATURDAY, List.of(), LeavePart.FULL);
        assertThat(weeklyOff.status).isEqualTo(DayStatus.WEEKLY_OFF);
        assertThat(weeklyOff.onDutyDays).isZero();

        DayResult leave = compute(WEDNESDAY, NOW, null, new Leave("CL", "Casual leave", LeavePart.FULL, 1), List.of(), Regularization.NONE, LeavePart.FULL);
        assertThat(leave.status).isEqualTo(DayStatus.LEAVE);
        assertThat(leave.leaveDays).isEqualTo(1);
        assertThat(leave.onDutyDays).isZero();
    }

    @Test
    void aHalfOnDutyDayNeedsHalfADayOfWorkForTheOtherHalf() {
        DayResult worked = day(WEDNESDAY, worked(WEDNESDAY, "09:30", "13:45"), LeavePart.FIRST_HALF);
        assertThat(worked.status).isEqualTo(DayStatus.PRESENT);
        assertThat(worked.label).isEqualTo("On duty · half day");
        assertThat(worked.onDutyDays).isEqualTo(0.5);
        assertThat(worked.lopDays).isZero();

        DayResult notWorked = day(WEDNESDAY, List.of(), LeavePart.SECOND_HALF);
        assertThat(notWorked.status).isEqualTo(DayStatus.HALF_DAY);
        assertThat(notWorked.onDutyDays).isEqualTo(0.5);
        assertThat(notWorked.lopDays).isEqualTo(0.5);

        DayResult regularized = compute(WEDNESDAY, NOW, null, null, List.of(), new Regularization(true, false), LeavePart.SECOND_HALF);
        assertThat(regularized.status).isEqualTo(DayStatus.PRESENT);
        assertThat(regularized.regularized).isTrue();
        assertThat(regularized.lopDays).isZero();
    }

    @Test
    void aHalfOnDutyDayStillInProgressIsNotYetLossOfPay() {
        Instant morning = at("2026-10-06", "10:30");
        DayResult inProgress = compute("2026-10-06", morning, null, null, List.of(new Punch(at("2026-10-06", "09:30"), PunchKind.IN)), Regularization.NONE, LeavePart.SECOND_HALF);
        assertThat(inProgress.status).isEqualTo(DayStatus.PENDING);
        assertThat(inProgress.lopDays).isZero();
        assertThat(inProgress.onDutyDays).isEqualTo(0.5);
    }

    @Test
    void aPlannedOnDutyDayInTheFutureShowsAsOnDutyAndHasNoEffectYet() {
        DayResult planned = day(MONDAY_AFTER, List.of(), LeavePart.FULL);
        assertThat(planned.future).isTrue();
        assertThat(planned.status).isEqualTo(DayStatus.PENDING);
        assertThat(planned.label).isEqualTo("On duty");
        assertThat(planned.lopDays).isZero();
    }

    @Test
    void theMonthSummaryCountsOnDutyAsPresentSoPayableDaysAndLossOfPayStayConsistent() {
        List<DayResult> days = new ArrayList<>();
        days.add(day("2026-09-14", worked("2026-09-14", "09:30", "18:30"), null));      // Mon: worked, present 1
        days.add(day("2026-09-15", List.of(), LeavePart.FULL));                           // Tue: full on duty, present 1
        days.add(day(WEDNESDAY, List.of(), LeavePart.SECOND_HALF));                       // Wed: half on duty, half absent: present 0.5, LOP 0.5
        days.add(day("2026-09-17", List.of(), null));                                     // Thu: absent, LOP 1
        MonthSummary summary = AttendanceEngine.summarize(days, POLICY);
        assertThat(summary.present).isEqualTo(2.5);
        assertThat(summary.onDuty).isEqualTo(1.5);
        assertThat(summary.absent).isEqualTo(1.5);
        assertThat(summary.lopDays).isEqualTo(1.5); // what payroll deducts: the On Duty days are not in it
    }
}
