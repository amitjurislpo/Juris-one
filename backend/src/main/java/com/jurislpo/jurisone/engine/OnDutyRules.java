package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.exception.UserException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;

/** The pure rules of an On Duty request: what may be asked for, which days it fits, and the length of the texts. */
public final class OnDutyRules {
    private OnDutyRules() {}

    public static final List<String> PARTS = List.of("FULL", "FIRST_HALF", "SECOND_HALF");
    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_LOCATION_LENGTH = 120;
    public static final int MAX_RANGE_DAYS = 31;
    /** How far ahead a duty may be planned. */
    public static final int MAX_DAYS_AHEAD = 90;

    /** Days that are not working days of the employee: an On Duty request does not fit them. */
    private static final Set<DayStatus> NOT_WORKING = Set.of(DayStatus.HOLIDAY, DayStatus.WEEKLY_OFF, DayStatus.LEAVE, DayStatus.BEFORE_JOINING, DayStatus.AFTER_EXIT, DayStatus.NOT_TRACKED);

    /** The reason: required, trimmed, at most 500 characters (400 otherwise). */
    public static String reason(String text) {
        String reason = text == null ? "" : text.trim();
        if (reason.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the reason for the On Duty request.");
        if (reason.length() > MAX_REASON_LENGTH) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");
        return reason;
    }

    /** The place (client, site): optional, trimmed, at most 120 characters; blank gives null. */
    public static String location(String text) {
        String location = text == null ? "" : text.trim();
        if (location.length() > MAX_LOCATION_LENGTH) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the place under 120 characters.");
        return location.isEmpty() ? null : location;
    }

    /** The first and last day of a request; a range may cover at most 31 days (400 otherwise). */
    public static void checkRange(LocalDate first, LocalDate last) {
        if (last.isBefore(first)) throw new UserException(HttpStatus.BAD_REQUEST, "The last day can't be before the first day.");
        if (first.until(last, ChronoUnit.DAYS) + 1 > MAX_RANGE_DAYS) throw new UserException(HttpStatus.BAD_REQUEST, "A range can cover at most " + MAX_RANGE_DAYS + " days.");
    }

    /** Why a day is outside the window a request may be made for (null = inside): not older than the back-date limit, not more than 90 days ahead. */
    public static String windowProblem(LocalDate date, LocalDate today, int backDateLimitDays) {
        if (date.isBefore(today.minusDays(backDateLimitDays))) return "Requests can be made up to " + backDateLimitDays + " days back.";
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) return "An On Duty request can be made at most " + MAX_DAYS_AHEAD + " days ahead.";
        return null;
    }

    /** Why the day cannot be On Duty (null = it can): a holiday, weekly off, leave or a day outside the employee's tracked period. */
    public static String dayProblem(DayStatus status) {
        if (!NOT_WORKING.contains(status)) return null;
        return switch (status) {
            case HOLIDAY, WEEKLY_OFF -> "That day is a holiday or weekly off.";
            case LEAVE -> "You are on leave that day.";
            default -> "That day is outside the period attendance is tracked for you.";
        };
    }
}
