package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Leave;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Regularization;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftDef;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An approved remote work day is a normal working day: Present and paid whether or not there is a punch, shown as "Remote". */
class AttendanceEngineRemoteWorkTest {

    static final String IST = "Asia/Kolkata";
    static final ShiftDef SHIFT = new ShiftDef("09:30", "18:30", 15);
    static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);
    static final Regularization REMOTE = new Regularization(false, false, true);
    static final Instant LATER = at("2026-10-06", "12:00");

    static Instant at(String date, String time) {
        return AttendanceEngine.atLocal(LocalDate.parse(date), time, IST);
    }

    static Punch punch(String date, String time, PunchKind kind) {
        return new Punch(at(date, time), kind);
    }

    static DayResult compute(String date, Instant now, String holiday, Leave leave, List<Punch> punches, Regularization regularization) {
        return AttendanceEngine.computeDay(new DayInput(LocalDate.parse(date), now, IST, SHIFT, POLICY, LocalDate.parse("2025-01-01"), null, LocalDate.parse("2026-04-01"), holiday, leave, punches, regularization));
    }

    @Test
    @DisplayName("a working day with no punch is Present (Remote), not Absent, and costs no LOP")
    void noPunchIsPresent() {
        DayResult withoutRemote = compute("2026-09-16", LATER, null, null, List.of(), Regularization.NONE);
        assertThat(withoutRemote.status).isEqualTo(DayStatus.ABSENT);
        assertThat(withoutRemote.lopDays).isEqualTo(1);

        DayResult remote = compute("2026-09-16", LATER, null, null, List.of(), REMOTE);
        assertThat(remote.status).isEqualTo(DayStatus.PRESENT);
        assertThat(remote.label).isEqualTo("Remote");
        assertThat(remote.lopDays).isZero();
        assertThat(remote.regularized).isFalse();
    }

    @Test
    @DisplayName("a short day worked remotely does not become half day or absent")
    void shortDayStaysPresent() {
        List<Punch> twoHours = List.of(punch("2026-09-16", "10:00", PunchKind.IN), punch("2026-09-16", "12:00", PunchKind.OUT));
        assertThat(compute("2026-09-16", LATER, null, null, twoHours, Regularization.NONE).status).isEqualTo(DayStatus.ABSENT);
        DayResult remote = compute("2026-09-16", LATER, null, null, twoHours, REMOTE);
        assertThat(remote.status).isEqualTo(DayStatus.PRESENT);
        assertThat(remote.lopDays).isZero();
        assertThat(remote.workedMinutes).isEqualTo(120); // the real hours are still shown
    }

    @Test
    @DisplayName("a forgotten check-out on a remote day is still Present")
    void missedCheckoutStaysPresent() {
        DayResult remote = compute("2026-09-16", LATER, null, null, List.of(punch("2026-09-16", "10:00", PunchKind.IN)), REMOTE);
        assertThat(remote.status).isEqualTo(DayStatus.PRESENT);
        assertThat(remote.lopDays).isZero();
    }

    @Test
    @DisplayName("a late check-in on a remote day is not a late mark")
    void noLateMark() {
        List<Punch> late = List.of(punch("2026-09-16", "11:30", PunchKind.IN), punch("2026-09-16", "20:00", PunchKind.OUT));
        assertThat(compute("2026-09-16", LATER, null, null, late, Regularization.NONE).late).isTrue();
        assertThat(compute("2026-09-16", LATER, null, null, late, REMOTE).late).isFalse();
    }

    @Test
    @DisplayName("holidays and weekly offs stay what they are")
    void offDaysUnchanged() {
        assertThat(compute("2026-09-16", LATER, "Founders day", null, List.of(), REMOTE).status).isEqualTo(DayStatus.HOLIDAY);
        DayResult saturday = compute("2026-09-19", LATER, null, null, List.of(), REMOTE);
        assertThat(saturday.status).isEqualTo(DayStatus.WEEKLY_OFF);
        assertThat(saturday.lopDays).isZero();
    }

    @Test
    @DisplayName("a full-day leave stays leave; on a half-day leave the working half counts as worked")
    void leaveDays() {
        Leave full = new Leave("CL", "Casual leave", LeavePart.FULL, 1);
        assertThat(compute("2026-09-16", LATER, null, full, List.of(), REMOTE).status).isEqualTo(DayStatus.LEAVE);

        Leave half = new Leave("CL", "Casual leave", LeavePart.FIRST_HALF, 1);
        DayResult withoutRemote = compute("2026-09-16", LATER, null, half, List.of(), Regularization.NONE);
        assertThat(withoutRemote.lopDays).isEqualTo(0.5);
        DayResult remote = compute("2026-09-16", LATER, null, half, List.of(), REMOTE);
        assertThat(remote.status).isEqualTo(DayStatus.LEAVE);
        assertThat(remote.leaveDays).isEqualTo(0.5);
        assertThat(remote.lopDays).isZero();
    }

    @Test
    @DisplayName("today, before anyone has checked in, a remote day is already Present")
    void todayWithoutPunch() {
        Instant morning = at("2026-09-16", "08:00");
        assertThat(compute("2026-09-16", morning, null, null, List.of(), Regularization.NONE).status).isEqualTo(DayStatus.PENDING);
        DayResult remote = compute("2026-09-16", morning, null, null, List.of(), REMOTE);
        assertThat(remote.status).isEqualTo(DayStatus.PRESENT);
        assertThat(remote.label).isEqualTo("Remote");
    }

    @Test
    @DisplayName("a future remote day is not counted yet but is labelled Remote")
    void futureDay() {
        DayResult remote = compute("2026-10-20", LATER, null, null, List.of(), REMOTE);
        assertThat(remote.future).isTrue();
        assertThat(remote.status).isEqualTo(DayStatus.PENDING);
        assertThat(remote.label).isEqualTo("Remote");
        assertThat(remote.lopDays).isZero();
    }

    @Test
    @DisplayName("payable days: remote days count as present and add no LOP to the month")
    void monthSummary() {
        List<DayResult> days = List.of(
                compute("2026-09-15", LATER, null, null, List.of(), REMOTE),
                compute("2026-09-16", LATER, null, null, List.of(), REMOTE),
                compute("2026-09-17", LATER, null, null, List.of(), Regularization.NONE));
        AttendanceEngine.MonthSummary summary = AttendanceEngine.summarize(days, POLICY);
        assertThat(summary.present).isEqualTo(2);
        assertThat(summary.absent).isEqualTo(1);
        assertThat(summary.lopDays).isEqualTo(1);
    }

    @Test
    @DisplayName("the two-flag Regularization constructor and NONE mean no remote work")
    void oldConstructor() {
        assertThat(new Regularization(true, false).remoteWork()).isFalse();
        assertThat(Regularization.NONE.remoteWork()).isFalse();
    }
}
