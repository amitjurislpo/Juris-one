package com.jurislpo.jurisone.engine;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a JurisOne shift is described to JurisTrack. A JurisOne shift is a wall-clock window in the employee's own time zone
 * ("19:30" to "04:30" for an Indian employee); JurisTrack wants {@code HH:mm} on the wall clock of the business's time zone, one shift per
 * {@code code}. So the same JurisOne shift is converted once per employee zone: the zone the shift is "at home" in keeps the plain code
 * ({@code IN_NIGHT}, {@code US_DAY}), the same shift worked from another zone gets the zone appended ({@code US_DAY_US_PT}).
 */
public final class JurisTrackShifts {
    private JurisTrackShifts() {}

    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    /** A shift as JurisTrack gets it. */
    public record TrackShift(String code, String name, String startTime, String endTime) {}

    /** The zone a shift of {@code region} is written in: the first location of that region (India: IST, US: Eastern). */
    static String homeZone(String region) {
        return PeopleRules.LOCATIONS.stream().filter(location -> location.region().equals(region)).findFirst().orElse(PeopleRules.LOCATIONS.get(0)).timeZone();
    }

    /** The code JurisTrack knows the shift by when it is worked from {@code employeeZone}. */
    public static String trackCode(String shiftCode, String region, String employeeZone) {
        if (homeZone(region).equals(employeeZone)) return shiftCode;
        return shiftCode + "_" + PeopleRules.locationOfTimeZone(employeeZone).value();
    }

    /**
     * The shift on the business's wall clock. The offsets are those of {@code today} (the business zone's date): a region with daylight
     * saving time moves the converted times by an hour twice a year, and the next sync (at most 6 hours later) follows.
     * Null when start and end are the same wall time: JurisTrack allows that only for its own all-day shift.
     */
    public static TrackShift convert(String shiftCode, String name, String startLocal, String endLocal, String region, String employeeZone, String businessZone, LocalDate today) {
        LocalTime start = LocalTime.parse(startLocal), end = LocalTime.parse(endLocal);
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes == 0) return null;
        if (minutes < 0) minutes += 24 * 60; // an end at or before the start is the next day
        LocalTime businessStart = today.atTime(start).atZone(ZoneId.of(employeeZone)).withZoneSameInstant(ZoneId.of(businessZone)).toLocalTime();
        String code = trackCode(shiftCode, region, employeeZone);
        String shiftName = code.equals(shiftCode) ? name : name + " (" + PeopleRules.locationOfTimeZone(employeeZone).value() + ")";
        return new TrackShift(code, shiftName, businessStart.format(HOUR_MINUTE), businessStart.plusMinutes(minutes).format(HOUR_MINUTE));
    }
}
