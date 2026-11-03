package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.exception.UserException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Hours, status and time checks of a regularization request (pure rules). */
class RegularizationRulesTest {

    static final String ZONE = "Asia/Kolkata";
    static final Policy POLICY = new Policy(8, 4, Set.of(0, 6), 120, 3);
    static final LocalDate DAY = LocalDate.parse("2026-10-29");
    static final Instant NOW = AttendanceEngine.atLocal(LocalDate.parse("2026-10-31"), "12:00", ZONE);

    static Instant at(String date, String time) {
        return AttendanceEngine.atLocal(LocalDate.parse(date), time, ZONE);
    }

    @Test
    void parsesLocalDateTimesInTheEmployeesZone() {
        assertThat(RegularizationRules.parseLocalDateTime("2026-10-29T09:30", ZONE)).isEqualTo(at("2026-10-29", "09:30"));
        assertThat(RegularizationRules.parseLocalDateTime("", ZONE)).isNull();
        assertThat(RegularizationRules.parseLocalDateTime(null, ZONE)).isNull();
        assertThatThrownBy(() -> RegularizationRules.parseLocalDateTime("29-10-2026 09:30", ZONE)).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> RegularizationRules.parseLocalDateTime("2026-10-29T25:99", ZONE)).isInstanceOf(UserException.class);
    }

    @Test
    void minutesBetweenIsWholeMinutesAndNeverNegative() {
        assertThat(RegularizationRules.minutesBetween(at("2026-10-29", "09:30"), at("2026-10-29", "18:45"))).isEqualTo(555);
        assertThat(RegularizationRules.minutesBetween(at("2026-10-29", "22:00"), at("2026-10-30", "06:00"))).isEqualTo(480); // night shift
        assertThat(RegularizationRules.minutesBetween(at("2026-10-29", "18:00"), at("2026-10-29", "09:00"))).isNull();
        assertThat(RegularizationRules.minutesBetween(null, at("2026-10-29", "09:00"))).isNull();
    }

    @Test
    void checksTheTimesAgainstEachOtherAndTheDay() {
        Instant in = at("2026-10-29", "09:30");
        RegularizationRules.checkTimes(DAY, in, at("2026-10-29", "18:30"), null, ZONE, NOW);
        RegularizationRules.checkTimes(DAY, at("2026-10-29", "22:00"), at("2026-10-30", "06:00"), null, ZONE, NOW); // night shift into the next morning
        RegularizationRules.checkTimes(DAY, null, at("2026-10-29", "18:30"), in, ZONE, NOW); // only a check-out: after the recorded check-in
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, in, in, null, ZONE, NOW)).hasMessageContaining("after check-in");
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, null, at("2026-10-29", "08:00"), in, ZONE, NOW)).hasMessageContaining("after check-in");
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, at("2026-10-28", "23:00"), at("2026-10-29", "07:00"), null, ZONE, NOW)).hasMessageContaining("regularized day");
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, in, at("2026-10-31", "09:30"), null, ZONE, NOW)).hasMessageContaining("next morning");
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, at("2026-10-29", "08:00"), at("2026-10-30", "09:00"), null, ZONE, NOW)).hasMessageContaining("24 hours");
        assertThatThrownBy(() -> RegularizationRules.checkTimes(DAY, in, at("2026-10-31", "18:00"), null, ZONE, NOW)).isInstanceOf(UserException.class);
    }

    @Test
    void eachKindAsksForTheTimesItNeeds() {
        Instant in = at("2026-10-29", "09:30"), out = at("2026-10-29", "18:30");
        RegularizationRules.requireTimesFor("ABSENT", in, out);
        RegularizationRules.requireTimesFor("LATE_CHECKIN", in, null);
        RegularizationRules.requireTimesFor("MISSED_CHECKOUT", null, out);
        RegularizationRules.requireTimesFor("HOLIDAY_WORK", null, null);
        assertThatThrownBy(() -> RegularizationRules.requireTimesFor("ABSENT", in, null)).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> RegularizationRules.requireTimesFor("LATE_CHECKIN", null, out)).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> RegularizationRules.requireTimesFor("MISSED_CHECKOUT", in, null)).isInstanceOf(UserException.class);
    }

    @Test
    void newStatusFollowsTheKindAndTheHours() {
        assertThat(RegularizationRules.newStatus("ABSENT", "ABSENT", 540, POLICY)).isEqualTo("PRESENT");
        assertThat(RegularizationRules.newStatus("ABSENT", "MISSED_CHECKOUT", 100, POLICY)).isEqualTo("PRESENT"); // regularized day is paid
        assertThat(RegularizationRules.newStatus("PRESENT", "LATE_CHECKIN", 540, POLICY)).isEqualTo("PRESENT");
        assertThat(RegularizationRules.newStatus("PRESENT", "LATE_CHECKIN", 300, POLICY)).isEqualTo("HALF_DAY");
        assertThat(RegularizationRules.newStatus("PRESENT", "LATE_CHECKIN", 120, POLICY)).isEqualTo("ABSENT");
        assertThat(RegularizationRules.newStatus("WEEKLY_OFF", "HOLIDAY_WORK", 300, POLICY)).isEqualTo("WEEKLY_OFF");
        assertThat(RegularizationRules.newStatus("HOLIDAY", "ABSENT", 540, POLICY)).isEqualTo("HOLIDAY");
        assertThat(RegularizationRules.newStatus("LEAVE", "LATE_CHECKIN", 540, POLICY)).isEqualTo("LEAVE");
    }
}
