package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.AttendanceEngine.Policy;
import com.jurislpo.jurisone.exception.UserException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** The pure rules of an attendance regularization request: the times asked for, the hours and the status they would give. */
public final class RegularizationRules {
    private RegularizationRules() {}

    public static final List<String> KINDS = List.of("ABSENT", "LATE_CHECKIN", "MISSED_CHECKOUT", "HOLIDAY_WORK");
    public static final int MAX_DESCRIPTION_LENGTH = 500;
    public static final int MAX_SPAN_HOURS = 24;

    private static final Pattern LOCAL_DATE_TIME = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$");
    /** Days that are not worked days: a regularization never changes what they are. */
    private static final Set<String> FIXED_STATUSES = Set.of("HOLIDAY", "WEEKLY_OFF", "LEAVE", "BEFORE_JOINING", "AFTER_EXIT", "NOT_TRACKED");

    /** {@code "2026-10-29T09:30"} in the employee's time zone → the instant; blank → null; anything else → 400. */
    public static Instant parseLocalDateTime(String text, String timeZone) {
        if (text == null || text.isBlank()) return null;
        if (!LOCAL_DATE_TIME.matcher(text).matches()) throw new UserException(HttpStatus.BAD_REQUEST, "Use the format yyyy-MM-ddTHH:mm for check-in and check-out.");
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.of(timeZone)).toInstant();
        } catch (java.time.DateTimeException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "That date or time does not exist.");
        }
    }

    /**
     * Checks the asked-for times against each other and the day: the check-in is on the attendance date, the check-out is after it
     * (a night shift may end on the next calendar day), not more than 24 hours later, and neither is in the future.
     * {@code existingCheckIn} is the day's recorded check-in, used when only a check-out is asked for.
     */
    public static void checkTimes(LocalDate date, Instant checkIn, Instant checkOut, Instant existingCheckIn, String timeZone, Instant now) {
        if (checkIn != null) {
            if (!AttendanceEngine.localParts(checkIn, timeZone).date().equals(date)) throw new UserException(HttpStatus.BAD_REQUEST, "Check-in must be on the regularized day.");
            if (checkIn.isAfter(now)) throw new UserException(HttpStatus.BAD_REQUEST, "Check-in can't be in the future.");
        }
        if (checkOut != null) {
            LocalDate outDate = AttendanceEngine.localParts(checkOut, timeZone).date();
            if (outDate.isBefore(date) || outDate.isAfter(date.plusDays(1))) throw new UserException(HttpStatus.BAD_REQUEST, "Check-out must be on the regularized day or the next morning.");
            if (checkOut.isAfter(now)) throw new UserException(HttpStatus.BAD_REQUEST, "Check-out can't be in the future.");
            Instant start = checkIn != null ? checkIn : existingCheckIn;
            if (start != null && !checkOut.isAfter(start)) throw new UserException(HttpStatus.BAD_REQUEST, "Check-out must be after check-in.");
            if (start != null && Duration.between(start, checkOut).compareTo(Duration.ofHours(MAX_SPAN_HOURS)) > 0) {
                throw new UserException(HttpStatus.BAD_REQUEST, "Check-in and check-out can be at most " + MAX_SPAN_HOURS + " hours apart.");
            }
        }
    }

    /** Which times each kind needs: absent needs both, late check-in the check-in, missed check-out the check-out, holiday work none. */
    public static void requireTimesFor(String kind, Instant checkIn, Instant checkOut) {
        switch (kind) {
            case "ABSENT" -> {
                if (checkIn == null || checkOut == null) throw new UserException(HttpStatus.BAD_REQUEST, "Enter both the check-in and the check-out time.");
            }
            case "LATE_CHECKIN" -> {
                if (checkIn == null) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the check-in time.");
            }
            case "MISSED_CHECKOUT" -> {
                if (checkOut == null) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the time you actually checked out.");
            }
            default -> { }
        }
    }

    /** Minutes between two instants (whole minutes, never negative); null when either is missing. */
    public static Integer minutesBetween(Instant checkIn, Instant checkOut) {
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) return null;
        return (int) Duration.between(checkIn, checkOut).toMinutes();
    }

    /**
     * The status the day would have once the request is approved. Off days, leave and the like stay what they are; an absent or
     * missed-check-out request makes the day a paid (regularized) present day; a late check-in request only waives the late mark,
     * so the hours decide; holiday work keeps the day's status.
     */
    public static String newStatus(String oldStatus, String kind, Integer newMinutes, Policy policy) {
        if (oldStatus != null && FIXED_STATUSES.contains(oldStatus)) return oldStatus;
        if ("HOLIDAY_WORK".equals(kind)) return oldStatus;
        if ("ABSENT".equals(kind) || "MISSED_CHECKOUT".equals(kind)) return "PRESENT";
        if (newMinutes == null) return oldStatus;
        if (newMinutes >= policy.fullDayHours() * 60) return "PRESENT";
        if (newMinutes >= policy.halfDayHours() * 60) return "HALF_DAY";
        return "ABSENT";
    }
}
