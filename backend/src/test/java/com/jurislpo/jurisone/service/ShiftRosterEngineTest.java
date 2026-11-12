package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayInput;
import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.AttendanceEngine.Regularization;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.Shift;
import com.jurislpo.jurisone.service.AttendanceService.ShiftPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which shift applies on a date when a roster is present, and which work date a punch belongs to (night shifts, open ranges, fallback). Pure logic, no database. */
class ShiftRosterEngineTest {

    private static final Shift DAY = new Shift("IN_DAY", "India Day", "09:30", "18:30", 15);
    private static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);
    private static final Shift NIGHT = new Shift("CUSTOM_NIGHT", "Night", "22:00", "06:00", 15);

    private static AttendanceEmployee employee(List<ShiftPeriod> periods) {
        return new AttendanceEmployee(UUID.randomUUID(), "E1", "Asha", "Analyst", "Ops", null, "Asia/Kolkata", "IN", LocalDate.of(2020, 1, 1), null, DAY, periods);
    }

    @Test
    void withoutARosterTheEmployeesOwnShiftApplies() {
        AttendanceEmployee employee = employee(List.of());
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 10))).isEqualTo(DAY);
    }

    @Test
    void anAssignmentCoversItsDatesInclusiveAndTheOwnShiftIsTheFallbackAround() {
        AttendanceEmployee employee = employee(List.of(new ShiftPeriod(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), NIGHT)));
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 9))).isEqualTo(DAY);
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 10))).isEqualTo(NIGHT);
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 12))).isEqualTo(NIGHT);
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 13))).isEqualTo(DAY);
    }

    @Test
    void anOpenEndedAssignmentRunsForever() {
        AttendanceEmployee employee = employee(List.of(new ShiftPeriod(LocalDate.of(2026, 11, 10), null, NIGHT)));
        assertThat(employee.shiftOn(LocalDate.of(2026, 11, 9))).isEqualTo(DAY);
        assertThat(employee.shiftOn(LocalDate.of(2030, 1, 1))).isEqualTo(NIGHT);
    }

    @Test
    void aPunchAfterMidnightBelongsToTheNightShiftThatStartedTheDayBefore() {
        AttendanceEmployee employee = employee(List.of(new ShiftPeriod(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), NIGHT)));
        // 11 Nov 02:00 IST (= 10 Nov 20:30 UTC) is inside the night shift that started on 10 Nov.
        assertThat(employee.workDateAt(Instant.parse("2026-11-10T20:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 10));
        // 10 Nov 22:15 IST (= 16:45 UTC) is the check-in itself.
        assertThat(employee.workDateAt(Instant.parse("2026-11-10T16:45:00Z"))).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void aLateNightCheckOutStillCountsForTheNightShiftEvenWhenTheNextDayStartsEarlier() {
        // Night shift on 10 Nov ends 06:00 on 11 Nov; the employee is back on the day shift on 13 Nov, so nothing shadows 11 Nov.
        AttendanceEmployee employee = employee(List.of(new ShiftPeriod(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), NIGHT)));
        // 11 Nov 06:30 IST (= 01:00 UTC) is 30 minutes after the night shift ended: still the 10 Nov work date.
        assertThat(employee.workDateAt(Instant.parse("2026-11-11T01:00:00Z"))).isEqualTo(LocalDate.of(2026, 11, 10));
        // 11 Nov 10:00 IST is long after: a new (day shift) work date.
        assertThat(employee.workDateAt(Instant.parse("2026-11-11T04:30:00Z"))).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    void theEngineJudgesADayAgainstTheShiftOfThatDate() {
        AttendanceEmployee employee = employee(List.of(new ShiftPeriod(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), NIGHT)));
        LocalDate date = LocalDate.of(2026, 11, 10);
        // 22:00 to 06:00 IST on a roster night shift: a full 8 hours, on time.
        List<Punch> punches = List.of(new Punch(Instant.parse("2026-11-10T16:30:00Z"), PunchKind.IN, "WEB"), new Punch(Instant.parse("2026-11-11T00:30:00Z"), PunchKind.OUT, "WEB"));
        Instant now = Instant.parse("2026-11-12T06:00:00Z");
        DayResult night = AttendanceEngine.computeDay(new DayInput(date, now, employee.timeZone(), employee.shiftOn(date).shiftDefinition(), POLICY, employee.dateOfJoining(), null,
                LocalDate.of(2026, 4, 1), null, null, punches, Regularization.NONE));
        assertThat(night.status).isEqualTo(DayStatus.PRESENT);
        assertThat(night.late).isFalse();
        assertThat(night.workedMinutes).isEqualTo(480);
        // The same punches against the plain day shift (09:30) would be a very late arrival: the engine must be given the date's shift.
        DayResult wrongShift = AttendanceEngine.computeDay(new DayInput(date, now, employee.timeZone(), DAY.shiftDefinition(), POLICY, employee.dateOfJoining(), null,
                LocalDate.of(2026, 4, 1), null, null, punches, Regularization.NONE));
        assertThat(wrongShift.late).isTrue();
    }
}
