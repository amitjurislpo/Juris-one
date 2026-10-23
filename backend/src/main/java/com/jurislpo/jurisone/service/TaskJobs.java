package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.TaskDueDates;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.ReminderCandidate;
import com.jurislpo.jurisone.repository.TaskRepository.Reminder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task reminders in the bell (plan §14), run for the current business by {@link JobsService#runForBusiness}: due tomorrow and due today
 * (timed tasks) from 09:00 in the task time zone, and overdue (a date-only task from 09:00 of the day after its due date, a timed task
 * right after its due time). Each reminder is sent once per due date to the direct and the indirect assignee; a new due date re-arms it.
 */
@Service
@Transactional
public class TaskJobs {

    private final TaskRepository repo;
    private final TaskNotificationService notifications;

    public TaskJobs(TaskRepository repo, TaskNotificationService notifications) {
        this.repo = repo;
        this.notifications = notifications;
    }

    /** {@code {dueSoon, dueToday, overdue}}: how many tasks got each reminder in this run. */
    public Map<String, Object> run(Instant now) {
        int dueSoon = 0, dueToday = 0, overdue = 0;
        // the furthest "tomorrow" anywhere is two days after today in UTC
        LocalDate dueUpTo = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(2);
        for (ReminderCandidate task : repo.findReminderCandidates(dueUpTo)) {
            String zone = task.timeZone();
            boolean isReminderTime = TaskDueDates.isReminderTime(zone, now);
            String label = TaskNotificationService.label(task.key());
            String at = task.dueTime() == null ? "" : String.format(java.util.Locale.ROOT, "%02d:%02d", task.dueTime().getHour(), task.dueTime().getMinute());
            if (TaskDueDates.isOverdue(task.dueDate(), task.dueTime(), zone, now)) {
                // a timed task is reminded right after its time; a date-only task at 09:00 of the next day
                if (task.dueTime() != null || isReminderTime) {
                    String due = Format.formatDayMonthYear(task.dueDate()) + (task.dueTime() == null ? "" : " " + at);
                    send(task, label + " is overdue (due " + due + "): " + task.title());
                    repo.markReminder(task.id(), Reminder.OVERDUE, task.dueDate());
                    overdue++;
                }
            } else if (task.dueTime() != null && isReminderTime && TaskDueDates.isDueToday(task.dueDate(), zone, now)
                    && !Objects.equals(task.dueTodayNotifiedFor(), task.dueDate())) {
                send(task, label + " is due today at " + at + ": " + task.title());
                repo.markReminder(task.id(), Reminder.DUE_TODAY, task.dueDate());
                dueToday++;
            } else if (isReminderTime && TaskDueDates.isDueTomorrow(task.dueDate(), zone, now) && !Objects.equals(task.dueSoonNotifiedFor(), task.dueDate())) {
                send(task, label + " is due tomorrow" + (task.dueTime() == null ? "" : " at " + at) + ": " + task.title());
                repo.markReminder(task.id(), Reminder.DUE_SOON, task.dueDate());
                dueSoon++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dueSoon", dueSoon);
        out.put("dueToday", dueToday);
        out.put("overdue", overdue);
        return out;
    }

    private void send(ReminderCandidate task, String text) {
        TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
        notices.add(task.assigneeId(), TaskNotificationService.ASSIGNMENT, text);
        notices.add(task.indirectAssigneeId(), TaskNotificationService.ASSIGNMENT, text);
        notifications.deliver(notices, null, TaskNotificationService.link(task.key(), task.id()));
    }
}
