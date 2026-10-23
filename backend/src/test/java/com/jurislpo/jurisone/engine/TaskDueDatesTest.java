package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Due date, optional due time and the task time zone (plan §8.6). 2026-10-23 is a Friday. */
@DisplayName("task due dates")
class TaskDueDatesTest {

    static final String INDIA = "Asia/Kolkata", NEW_YORK = "America/New_York";
    static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);

    @Test
    @DisplayName("a date-only task is due at the end of its day, in the task time zone")
    void dateOnly() {
        Instant now = Instant.parse("2026-10-23T19:00:00Z"); // India: Sat 00:30; New York: Fri 15:00
        assertThat(TaskDueDates.isOverdue(FRIDAY, null, INDIA, now)).isTrue();
        assertThat(TaskDueDates.isOverdue(FRIDAY, null, NEW_YORK, now)).isFalse();
        assertThat(TaskDueDates.isOverdue(FRIDAY, null, INDIA, Instant.parse("2026-10-23T18:29:59Z"))).isFalse(); // 23:59:59 in India
        assertThat(TaskDueDates.isOverdue(null, null, INDIA, now)).isFalse();
    }

    @Test
    @DisplayName("a timed task is overdue from its due minute")
    void timed() {
        LocalTime five = LocalTime.of(15, 0);
        assertThat(TaskDueDates.isOverdue(FRIDAY, five, NEW_YORK, Instant.parse("2026-10-23T18:59:59Z"))).isFalse();
        assertThat(TaskDueDates.isOverdue(FRIDAY, five, NEW_YORK, Instant.parse("2026-10-23T19:00:00Z"))).isTrue();
        assertThat(TaskDueDates.isOverdue(FRIDAY, five, INDIA, Instant.parse("2026-10-23T09:29:00Z"))).isFalse();
        assertThat(TaskDueDates.isOverdue(FRIDAY, five, INDIA, Instant.parse("2026-10-23T09:30:00Z"))).isTrue();
    }

    @Test
    @DisplayName("the task time zone is the direct assignee's, else the indirect assignee's, else the reporter's")
    void timeZone() {
        assertThat(TaskDueDates.taskTimeZone(NEW_YORK, INDIA, INDIA)).isEqualTo(NEW_YORK);
        assertThat(TaskDueDates.taskTimeZone(null, NEW_YORK, INDIA)).isEqualTo(NEW_YORK);
        assertThat(TaskDueDates.taskTimeZone(null, null, NEW_YORK)).isEqualTo(NEW_YORK);
        assertThat(TaskDueDates.taskTimeZone(null, null, null)).isEqualTo(TaskDueDates.FALLBACK_TIME_ZONE);
    }

    @Test
    @DisplayName("today, tomorrow and the 09:00 reminder time follow the task time zone")
    void todayTomorrowAndReminders() {
        Instant now = Instant.parse("2026-10-23T03:30:00Z"); // India 09:00 Fri; New York 23:30 Thu
        assertThat(TaskDueDates.isDueToday(FRIDAY, INDIA, now)).isTrue();
        assertThat(TaskDueDates.isDueTomorrow(FRIDAY, NEW_YORK, now)).isTrue();
        assertThat(TaskDueDates.isReminderTime(INDIA, now)).isTrue();
        assertThat(TaskDueDates.isReminderTime(INDIA, Instant.parse("2026-10-23T03:29:00Z"))).isFalse();
        assertThat(TaskDueDates.isReminderTime(NEW_YORK, now)).isTrue();
        assertThat(TaskDueDates.isReminderTime(NEW_YORK, Instant.parse("2026-10-23T12:59:00Z"))).isFalse();
    }

    @Test
    @DisplayName("buckets: overdue, today, the rest of this Monday-Sunday week, later, no date")
    void buckets() {
        Instant now = Instant.parse("2026-10-23T06:00:00Z"); // India: Friday 11:30
        assertThat(TaskDueDates.bucket(FRIDAY.minusDays(1), null, INDIA, now)).isEqualTo(TaskDueDates.OVERDUE);
        assertThat(TaskDueDates.bucket(FRIDAY, null, INDIA, now)).isEqualTo(TaskDueDates.TODAY);
        assertThat(TaskDueDates.bucket(FRIDAY, LocalTime.of(11, 0), INDIA, now)).isEqualTo(TaskDueDates.OVERDUE);
        assertThat(TaskDueDates.bucket(FRIDAY.plusDays(2), null, INDIA, now)).isEqualTo(TaskDueDates.THIS_WEEK);
        assertThat(TaskDueDates.bucket(FRIDAY.plusDays(3), null, INDIA, now)).isEqualTo(TaskDueDates.LATER);
        assertThat(TaskDueDates.bucket(null, null, INDIA, now)).isEqualTo(TaskDueDates.NO_DATE);
    }

    @Test
    @DisplayName("the extra reminder is the offset before the due moment: the due time, or the end of the due day")
    void reminderBeforeDue() {
        LocalTime five = LocalTime.of(17, 0);
        assertThat(TaskDueDates.REMINDER_BEFORE_MINUTES).containsExactly(15, 60, 240, 1440);
        assertThat(TaskDueDates.dueMoment(FRIDAY, five, INDIA)).isEqualTo(Instant.parse("2026-10-23T11:30:00Z"));
        assertThat(TaskDueDates.dueMoment(FRIDAY, null, INDIA)).isEqualTo(Instant.parse("2026-10-23T18:30:00Z")); // 00:00 Saturday
        assertThat(TaskDueDates.reminderMoment(FRIDAY, five, INDIA, 60)).isEqualTo(Instant.parse("2026-10-23T10:30:00Z"));
        assertThat(TaskDueDates.reminderMoment(FRIDAY, null, INDIA, 1440)).isEqualTo(Instant.parse("2026-10-22T18:30:00Z"));
        assertThat(TaskDueDates.reminderMoment(FRIDAY, five, NEW_YORK, 15)).isEqualTo(Instant.parse("2026-10-23T20:45:00Z"));
        // sent from its moment until the due moment, not before and not after
        assertThat(TaskDueDates.isReminderBeforeDue(FRIDAY, five, INDIA, 60, Instant.parse("2026-10-23T10:29:59Z"))).isFalse();
        assertThat(TaskDueDates.isReminderBeforeDue(FRIDAY, five, INDIA, 60, Instant.parse("2026-10-23T10:30:00Z"))).isTrue();
        assertThat(TaskDueDates.isReminderBeforeDue(FRIDAY, five, INDIA, 60, Instant.parse("2026-10-23T11:29:59Z"))).isTrue();
        assertThat(TaskDueDates.isReminderBeforeDue(FRIDAY, five, INDIA, 60, Instant.parse("2026-10-23T11:30:00Z"))).isFalse();
        assertThat(TaskDueDates.isReminderBeforeDue(null, null, INDIA, 60, Instant.parse("2026-10-23T11:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("the offset is written in words for the bell text")
    void describeOffset() {
        assertThat(TaskDueDates.describeOffset(15)).isEqualTo("15 minutes");
        assertThat(TaskDueDates.describeOffset(60)).isEqualTo("1 hour");
        assertThat(TaskDueDates.describeOffset(240)).isEqualTo("4 hours");
        assertThat(TaskDueDates.describeOffset(1440)).isEqualTo("1 day");
    }
}
