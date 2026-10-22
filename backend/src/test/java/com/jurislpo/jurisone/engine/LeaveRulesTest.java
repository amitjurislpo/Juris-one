package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.LeaveRules.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/leave-rules.test.ts. */
class LeaveRulesTest {

    static LocalDate parseDate(String text) { return LocalDate.parse(text); }
    static final Map<LocalDate, String> HOL = Map.of(parseDate("2026-10-20"), "Dussehra");

    @Nested
    @DisplayName("working days")
    class Working {
        @Test
        @DisplayName("skips weekends and the employee's holidays")
        void skips() {
            WorkingDays workingDays = workingDays(parseDate("2026-10-16"), parseDate("2026-10-21"), Set.of(0, 6), HOL); // Fri → Wed
            assertThat(workingDays.dates()).containsExactly(parseDate("2026-10-16"), parseDate("2026-10-19"), parseDate("2026-10-21"));
            assertThat(workingDays.skipped()).containsExactly(new Skipped(parseDate("2026-10-20"), "Dussehra"));
        }

        @Test
        @DisplayName("half day applies only to a single date")
        void halfDay() {
            assertThat(requestDays(List.of(parseDate("2026-10-21")), LeavePart.FIRST_HALF)).isEqualTo(0.5);
            assertThat(requestDays(List.of(parseDate("2026-10-21"), parseDate("2026-10-22")), LeavePart.FIRST_HALF)).isEqualTo(2);
            assertThat(requestDays(List.of(), LeavePart.FULL)).isZero();
        }
    }

    @Nested
    @DisplayName("loss of pay when the balance is short")
    class Lop {
        @Test
        @DisplayName("pays what the balance covers, in half-day steps")
        void halfDaySteps() {
            assertThat(lopSplit(3, 1.77, true, true)).isEqualTo(new LopSplit(1.5, 1.5));
            assertThat(lopSplit(2, 5, true, true)).isEqualTo(new LopSplit(2, 0));
            assertThat(lopSplit(2, 0, true, true)).isEqualTo(new LopSplit(0, 2));
        }

        @Test
        @DisplayName("leave without pay is all LOP")
        void unpaid() {
            assertThat(lopSplit(2, 10, false, false)).isEqualTo(new LopSplit(0, 2));
        }

        @Test
        @DisplayName("shortfall lands on the last days")
        void shortfall() {
            assertThat(paidFractions(List.of(parseDate("2026-10-21"), parseDate("2026-10-22"), parseDate("2026-10-23")), LeavePart.FULL, 1.5).values()).containsExactly(1.0, 0.5, 0.0);
            assertThat(paidFractions(List.of(parseDate("2026-10-21")), LeavePart.FIRST_HALF, 0.5).values()).containsExactly(1.0);
        }
    }

    @Nested
    @DisplayName("accrual")
    class Accrual {
        @Test
        @DisplayName("full month after joining, pro-rata in the joining month, none before")
        void accrual() {
            assertThat(accrualFor(YearMonth.of(2026, 10), parseDate("2026-09-08"), 1)).isEqualTo(1);
            assertThat(accrualFor(YearMonth.of(2026, 9), parseDate("2026-09-08"), 1)).isEqualTo(0.77); // 23 of 30 days — prototype JC-074
            assertThat(accrualFor(YearMonth.of(2026, 9), parseDate("2026-09-08"), 1.5)).isEqualTo(1.15);
            assertThat(accrualFor(YearMonth.of(2026, 8), parseDate("2026-09-08"), 1)).isZero();
        }
    }

    @Nested
    @DisplayName("comp-off")
    class CompOff {
        @Test
        @DisplayName("credits a full or half day by hours worked")
        void credits() {
            assertThat(compOffDays(8 * 60 + 40, 8, 4)).isEqualTo(1);
            assertThat(compOffDays(5 * 60, 8, 4)).isEqualTo(0.5);
            assertThat(compOffDays(3 * 60, 8, 4)).isZero();
        }

        @Test
        @DisplayName("expires the unused part of old credits first-in-first-out")
        void oldestCreditsFirst() {
            Credit first = new Credit("a", 1, parseDate("2026-09-01"), 0);
            Credit second = new Credit("b", 1, parseDate("2026-12-01"), 0);
            assertThat(compOffExpiries(List.of(first, second), 0.5, parseDate("2026-10-06"))).containsExactly(new Expiry("a", 0.5));
            assertThat(compOffExpiries(List.of(first, second), 1, parseDate("2026-10-06"))).isEmpty();
            assertThat(compOffExpiries(List.of(new Credit("a", 1, parseDate("2026-09-01"), 0.5), second), 0.5, parseDate("2026-10-06"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("year end")
    class YearEnd {
        @Test
        @DisplayName("casual leave lapses, earned leave carries up to 30")
        void lapse() {
            assertThat(yearEndLapse(4, 0)).isEqualTo(4);
            assertThat(yearEndLapse(34.5, 30)).isEqualTo(4.5);
            assertThat(yearEndLapse(12, 30)).isZero();
        }
    }
}
