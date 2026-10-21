package com.jurislpo.jurisone.engine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Server-side formatting. Output is byte-identical to Node's Intl
 * (en-GB date-times use "Sept" and lower-case am/pm; en-IN grouping 12,34,567).
 */
public final class Format {
    private Format() {}

    public static final List<String> MONTHS = List.of("January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December");
    public static final List<String> MONTH_ABBREVIATIONS = MONTHS.stream().map(m -> m.substring(0, 3)).toList();
    public static final List<String> WEEKDAY_ABBREVIATIONS = List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat");
    /** Intl en-GB short months (note "Sept"). */
    private static final List<String> MONTH_ABBREVIATIONS_UK = List.of("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct", "Nov", "Dec");

    /** ISO date of {@code at} as seen in an IANA zone. */
    public static LocalDate isoInZone(Instant at, String timeZone) {
        return at.atZone(ZoneId.of(timeZone)).toLocalDate();
    }

    /** {@code Date.prototype.toISOString()}: millisecond precision, Z. */
    public static String isoTimestamp(Instant i) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(i);
    }

    public static LocalDate todayIn(String timeZone) {
        return isoInZone(Instant.now(), timeZone);
    }

    public static String formatDayMonth(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTH_ABBREVIATIONS.get(date.getMonthValue() - 1);
    }

    public static String formatDayMonthYear(LocalDate date) {
        return formatDayMonth(date) + " " + date.getYear();
    }

    public static String formatWeekdayDate(LocalDate date) {
        return WEEKDAY_ABBREVIATIONS.get(AttendanceEngine.dowOf(date)) + ", " + formatDayMonthYear(date);
    }

    /** "2026-09" → "September 2026". */
    public static String monthLabel(String yearMonth) {
        return MONTHS.get(Integer.parseInt(yearMonth.substring(5, 7)) - 1) + " " + Integer.parseInt(yearMonth.substring(0, 4));
    }

    /** Intl en-GB {day numeric, month short, hour numeric, minute 2-digit, hour12} → "7 Oct, 8:49 pm". */
    public static String formatDateTime(Instant at, String timeZone) {
        ZonedDateTime zoned = at.atZone(ZoneId.of(timeZone));
        int hour = zoned.getHour() % 12 == 0 ? 12 : zoned.getHour() % 12;
        return zoned.getDayOfMonth() + " " + MONTH_ABBREVIATIONS_UK.get(zoned.getMonthValue() - 1) + ", " + hour + ":" + String.format(java.util.Locale.ROOT, "%02d", zoned.getMinute()) + " "
                + (zoned.getHour() < 12 ? "am" : "pm");
    }

    /** Whole-day difference b − a. */
    public static long daysBetween(LocalDate a, LocalDate b) {
        return ChronoUnit.DAYS.between(a, b);
    }

    /** Indian digit grouping (en-IN): 1234567 → "12,34,567". */
    public static String groupIndian(long n) {
        if (n < 0) return "-" + groupIndian(-n);
        String text = Long.toString(n);
        if (text.length() <= 3) return text;
        String last3 = text.substring(text.length() - 3);
        String rest = text.substring(0, text.length() - 3);
        StringBuilder out = new StringBuilder();
        int first = rest.length() % 2;
        if (first > 0) out.append(rest, 0, first);
        for (int i = first; i < rest.length(); i += 2) {
            if (out.length() > 0) out.append(',');
            out.append(rest, i, i + 2);
        }
        return out + "," + last3;
    }

    /** Paise → "₹1,23,456". */
    public static String inr(long paise) {
        return "₹" + groupIndian(Math.round(paise / 100.0));
    }

    /** "8h 05m". */
    public static String formatHours(double hours) {
        return (long) Math.floor(hours) + "h " + String.format(java.util.Locale.ROOT, "%02d", Math.round((hours % 1) * 60)) + "m";
    }

    public static String initials(String n) {
        StringBuilder builder = new StringBuilder();
        for (String w : n.trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            builder.appendCodePoint(w.codePointAt(0));
            if (builder.codePointCount(0, builder.length()) == 2) break;
        }
        return builder.toString().toUpperCase();
    }
}
