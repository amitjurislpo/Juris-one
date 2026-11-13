package com.jurislpo.jurisone.engine;

import static com.jurislpo.jurisone.engine.Num.round2;

import java.time.LocalDate;

/**
 * The business's leave year (REQUIREMENTS §5.6.4) — pure functions. A leave year starts every year on the same month and day
 * (default 1 January) and ends the day before the next start. The year is named by the calendar year it starts in.
 */
public final class LeaveYear {

    private LeaveYear() {}

    /** What happens to the unused balance of a leave type when a leave year ends. */
    public enum YearEndAction { COLLAPSE, CARRY_ALL, CARRY_CAPPED }

    /** One leave year: first and last day (both included). */
    public record Span(LocalDate start, LocalDate end) {
        /** The name the year goes by: the calendar year it starts in. */
        public String label() { return String.valueOf(start.getYear()); }

        public boolean contains(LocalDate date) { return !date.isBefore(start) && !date.isAfter(end); }
    }

    /** A start day that exists every year: 29 February is not allowed. */
    public static boolean isValidStart(int month, int day) {
        if (month < 1 || month > 12) return false;
        return day >= 1 && day <= java.time.YearMonth.of(2023, month).lengthOfMonth();
    }

    /** The leave year that contains {@code date}. */
    public static Span of(LocalDate date, int startMonth, int startDay) {
        LocalDate start = LocalDate.of(date.getYear(), startMonth, startDay);
        if (date.isBefore(start)) start = start.minusYears(1);
        return new Span(start, start.plusYears(1).minusDays(1));
    }

    /** The leave year just before the one that contains {@code date}. */
    public static Span before(LocalDate date, int startMonth, int startDay) {
        Span current = of(date, startMonth, startDay);
        return new Span(current.start().minusYears(1), current.start().minusDays(1));
    }

    /**
     * The balance that lapses when a leave year ends: all of it (COLLAPSE), none (CARRY_ALL), or what is above the cap (CARRY_CAPPED).
     * A balance of zero or less never lapses.
     */
    public static double lapse(YearEndAction action, double balance, double carryForwardCap) {
        return switch (action) {
            case COLLAPSE -> round2(Math.max(0, balance));
            case CARRY_ALL -> 0;
            case CARRY_CAPPED -> round2(Math.max(0, balance - carryForwardCap));
        };
    }
}
