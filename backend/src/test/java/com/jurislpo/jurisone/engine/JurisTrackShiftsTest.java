package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.JurisTrackShifts.TrackShift;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** A JurisOne shift (wall clock of the employee's zone) as JurisTrack gets it (wall clock of the business zone). */
class JurisTrackShiftsTest {

    static final LocalDate WINTER = LocalDate.of(2026, 1, 15), SUMMER = LocalDate.of(2026, 7, 15);

    @Test
    void aShiftInTheBusinessZoneKeepsItsTimesAndItsPlainCode() {
        TrackShift night = JurisTrackShifts.convert("IN_NIGHT", "India Night", "19:30", "04:30", "IN", "Asia/Kolkata", "Asia/Kolkata", WINTER);
        assertThat(night).isEqualTo(new TrackShift("IN_NIGHT", "India Night", "19:30", "04:30"));
    }

    @Test
    void theTimesMoveToTheBusinessZone() {
        // IST is UTC+5:30, Dubai UTC+4: an hour and a half earlier, the end too (the night shift still ends the next day)
        TrackShift night = JurisTrackShifts.convert("IN_NIGHT", "India Night", "19:30", "04:30", "IN", "Asia/Kolkata", "Asia/Dubai", WINTER);
        assertThat(night.startTime()).isEqualTo("18:00");
        assertThat(night.endTime()).isEqualTo("03:00");
        TrackShift day = JurisTrackShifts.convert("IN_DAY", "India Day", "09:30", "18:30", "IN", "Asia/Kolkata", "Asia/Dubai", WINTER);
        assertThat(day.startTime()).isEqualTo("08:00");
        assertThat(day.endTime()).isEqualTo("17:00");
    }

    @Test
    void aShiftThatCrossesMidnightInTheBusinessZoneKeepsItsLength() {
        // 09:00-18:00 in New York is 19:30-04:30 in Kolkata in winter (UTC-5 -> UTC+5:30): ends "04:30", the next day
        TrackShift shift = JurisTrackShifts.convert("US_DAY", "US day", "09:00", "18:00", "US", "America/New_York", "Asia/Kolkata", WINTER);
        assertThat(shift.startTime()).isEqualTo("19:30");
        assertThat(shift.endTime()).isEqualTo("04:30");
    }

    @Test
    void daylightSavingTimeUsesTheOffsetOfTheGivenDay() {
        assertThat(JurisTrackShifts.convert("US_DAY", "US day", "09:00", "18:00", "US", "America/New_York", "Asia/Kolkata", WINTER).startTime()).isEqualTo("19:30");
        assertThat(JurisTrackShifts.convert("US_DAY", "US day", "09:00", "18:00", "US", "America/New_York", "Asia/Kolkata", SUMMER).startTime()).isEqualTo("18:30");
    }

    @Test
    void theHomeZoneOfAShiftKeepsThePlainCodeAndAnotherZoneAddsItsName() {
        assertThat(JurisTrackShifts.trackCode("US_DAY", "US", "America/New_York")).isEqualTo("US_DAY");
        assertThat(JurisTrackShifts.trackCode("US_DAY", "US", "America/Los_Angeles")).isEqualTo("US_DAY_US_PT");
        assertThat(JurisTrackShifts.trackCode("IN_DAY", "IN", "Asia/Kolkata")).isEqualTo("IN_DAY");
        TrackShift pacific = JurisTrackShifts.convert("US_DAY", "US day", "09:00", "18:00", "US", "America/Los_Angeles", "America/New_York", WINTER);
        assertThat(pacific.code()).isEqualTo("US_DAY_US_PT");
        assertThat(pacific.name()).isEqualTo("US day (US_PT)");
        assertThat(pacific.startTime()).isEqualTo("12:00");
    }

    @Test
    void aShiftWithTheSameStartAndEndIsNotSent() {
        assertThat(JurisTrackShifts.convert("ALL", "All day", "00:00", "00:00", "IN", "Asia/Kolkata", "Asia/Kolkata", WINTER)).isNull();
    }
}
