package com.jurislpo.jurisone.engine;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Due date, optional due time and the task time zone (plan §8.6). A date-only task is due at the end of that day (it is overdue from
 * the start of the next day); a timed task is overdue from its due time. "Today" is the task time zone's today.
 */
public final class TaskDueDates {
    private TaskDueDates() {}

    public static final String OVERDUE = "OVERDUE", TODAY = "TODAY", THIS_WEEK = "THIS_WEEK", LATER = "LATER", NO_DATE = "NO_DATE";
    /** Reminders are sent from this local time of the task time zone. */
    public static final LocalTime REMINDER_TIME = LocalTime.of(9, 0);
    /** Used when nobody on the task has a time zone (never in practice: every employee has one). */
    public static final String FALLBACK_TIME_ZONE = "Asia/Kolkata";

    /** The task time zone: the direct assignee's, else the indirect assignee's, else the reporter's. */
    public static String taskTimeZone(String assigneeTimeZone, String indirectAssigneeTimeZone, String reporterTimeZone) {
        if (assigneeTimeZone != null) return assigneeTimeZone;
        if (indirectAssigneeTimeZone != null) return indirectAssigneeTimeZone;
        return reporterTimeZone != null ? reporterTimeZone : FALLBACK_TIME_ZONE;
    }

    public static LocalDateTime localNow(String timeZone, Instant now) {
        return LocalDateTime.ofInstant(now, ZoneId.of(timeZone));
    }

    public static boolean isOverdue(LocalDate dueDate, LocalTime dueTime, String timeZone, Instant now) {
        if (dueDate == null) return false;
        LocalDateTime local = localNow(timeZone, now);
        if (dueTime == null) return local.toLocalDate().isAfter(dueDate);
        return !local.isBefore(dueDate.atTime(dueTime));
    }

    public static boolean isDueToday(LocalDate dueDate, String timeZone, Instant now) {
        return dueDate != null && dueDate.equals(localNow(timeZone, now).toLocalDate());
    }

    public static boolean isDueTomorrow(LocalDate dueDate, String timeZone, Instant now) {
        return dueDate != null && dueDate.equals(localNow(timeZone, now).toLocalDate().plusDays(1));
    }

    /** Local time of the task time zone has reached 09:00. */
    public static boolean isReminderTime(String timeZone, Instant now) {
        return !localNow(timeZone, now).toLocalTime().isBefore(REMINDER_TIME);
    }

    /** OVERDUE, TODAY, THIS_WEEK (the rest of this Monday–Sunday week), LATER or NO_DATE, for an open task. */
    public static String bucket(LocalDate dueDate, LocalTime dueTime, String timeZone, Instant now) {
        if (dueDate == null) return NO_DATE;
        if (isOverdue(dueDate, dueTime, timeZone, now)) return OVERDUE;
        LocalDate today = localNow(timeZone, now).toLocalDate();
        if (dueDate.equals(today)) return TODAY;
        LocalDate endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return dueDate.isAfter(endOfWeek) ? LATER : THIS_WEEK;
    }
}
