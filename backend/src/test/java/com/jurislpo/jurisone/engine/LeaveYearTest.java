package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.LeaveYear.YearEndAction;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The leave year (default 1 Jan - 31 Dec, changeable) and the year-end maths. */
class LeaveYearTest {

    @Test
    void theDefaultYearIsTheCalendarYear() {
        LeaveYear.Span span = LeaveYear.of(LocalDate.parse("2026-12-31"), 1, 1);
        assertThat(span.start()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(span.end()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(span.label()).isEqualTo("2026");
        assertThat(LeaveYear.of(LocalDate.parse("2027-01-01"), 1, 1).start()).isEqualTo(LocalDate.parse("2027-01-01"));
    }

    @Test
    void aYearThatStartsInAprilRunsIntoTheNextCalendarYear() {
        LeaveYear.Span inMarch = LeaveYear.of(LocalDate.parse("2027-03-31"), 4, 1);
        assertThat(inMarch.start()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(inMarch.end()).isEqualTo(LocalDate.parse("2027-03-31"));
        assertThat(inMarch.label()).isEqualTo("2026");
        assertThat(LeaveYear.of(LocalDate.parse("2027-04-01"), 4, 1).label()).isEqualTo("2027");
        assertThat(inMarch.contains(LocalDate.parse("2027-04-01"))).isFalse();
        assertThat(inMarch.contains(LocalDate.parse("2026-04-01"))).isTrue();
    }

    @Test
    void theYearBeforeEndsTheDayBeforeTheCurrentOneStarts() {
        LeaveYear.Span ended = LeaveYear.before(LocalDate.parse("2027-01-02"), 1, 1);
        assertThat(ended.start()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(ended.end()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(LeaveYear.before(LocalDate.parse("2026-12-31"), 1, 1).label()).isEqualTo("2025");
    }

    @Test
    void aStartDayMustExistEveryYear() {
        assertThat(LeaveYear.isValidStart(1, 1)).isTrue();
        assertThat(LeaveYear.isValidStart(2, 28)).isTrue();
        assertThat(LeaveYear.isValidStart(2, 29)).isFalse();
        assertThat(LeaveYear.isValidStart(4, 31)).isFalse();
        assertThat(LeaveYear.isValidStart(0, 1)).isFalse();
        assertThat(LeaveYear.isValidStart(13, 1)).isFalse();
        assertThat(LeaveYear.isValidStart(5, 0)).isFalse();
    }

    @Test
    void collapseLapsesAllCarryAllLapsesNothingCappedLapsesTheExcess() {
        assertThat(LeaveYear.lapse(YearEndAction.COLLAPSE, 4, 0)).isEqualTo(4);
        assertThat(LeaveYear.lapse(YearEndAction.COLLAPSE, -2, 0)).isZero();
        assertThat(LeaveYear.lapse(YearEndAction.CARRY_ALL, 99, 0)).isZero();
        assertThat(LeaveYear.lapse(YearEndAction.CARRY_CAPPED, 34.5, 30)).isEqualTo(4.5);
        assertThat(LeaveYear.lapse(YearEndAction.CARRY_CAPPED, 12, 30)).isZero();
    }
}
