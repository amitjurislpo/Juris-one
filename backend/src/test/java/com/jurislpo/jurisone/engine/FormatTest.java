package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.Shifts;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/format.test.ts, plus fixtures captured from Node's Intl for the server-side formatters. */
@DisplayName("formatting")
class FormatTest {

    @Test
    @DisplayName("formats paise as Indian rupees")
    void inr() {
        assertThat(Format.inr(1492600)).isEqualTo("₹14,926");
        assertThat(Format.inr(75000000L)).isEqualTo("₹7,50,000");
    }

    @Test
    @DisplayName("formats ISO dates without time-zone drift")
    void dates() {
        assertThat(Format.fmtDL(LocalDate.parse("2026-10-06"))).isEqualTo("Tue, 6 Oct 2026");
        assertThat(Format.daysBetween(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-20"))).isEqualTo(14);
    }

    @Test
    @DisplayName("resolves the calendar date in the employee's zone")
    void zone() {
        Instant at = Instant.parse("2026-10-06T20:00:00Z"); // 01:30 IST next day, 16:00 ET
        assertThat(Format.isoInZone(at, "Asia/Kolkata")).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(Format.isoInZone(at, "America/New_York")).isEqualTo(LocalDate.parse("2026-10-06"));
    }

    @Test
    @DisplayName("matches Node's Intl output (en-GB date-times, en-IN grouping)")
    void nodeParity() {
        // values printed by Node 24 for the same inputs
        assertThat(Format.fmtDateTime(Instant.parse("2026-10-07T15:19:00Z"), "Asia/Kolkata")).isEqualTo("7 Oct, 8:49 pm");
        assertThat(Format.fmtDateTime(Instant.parse("2026-10-07T04:05:00Z"), "Asia/Kolkata")).isEqualTo("7 Oct, 9:35 am");
        assertThat(Format.fmtDateTime(Instant.parse("2026-10-07T18:30:00Z"), "Asia/Kolkata")).isEqualTo("8 Oct, 12:00 am");
        assertThat(Format.fmtDateTime(Instant.parse("2026-03-01T17:00:00Z"), "America/New_York")).isEqualTo("1 Mar, 12:00 pm");
        assertThat(Format.fmtDateTime(Instant.parse("2026-09-15T06:00:00Z"), "UTC")).isEqualTo("15 Sept, 6:00 am");
        assertThat(Format.groupIndian(1234567)).isEqualTo("12,34,567");
        assertThat(Format.groupIndian(-5000)).isEqualTo("-5,000");
        assertThat(Format.groupIndian(999)).isEqualTo("999");
        assertThat(Format.groupIndian(100000)).isEqualTo("1,00,000");
        assertThat(Format.ymLabel("2026-09")).isEqualTo("September 2026");
        assertThat(Shifts.label("IN_NIGHT", "19:30", "04:30")).isEqualTo("Night · 7:30 PM – 4:30 AM IST");
        assertThat(Shifts.label("US_DAY", "09:00", "18:00")).isEqualTo("Day · 9:00 AM – 6:00 PM local");
    }
}
