package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.repository.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bell notifications for tasks (plan §10; no emails). One request sends at most one notification per person, the most important
 * event winning (mention &gt; assignment &gt; comment &gt; status); the actor is never notified, nor anyone who has left or has no
 * portal access.
 */
@Service
@Transactional
public class TaskNotificationService {

    public static final int STATUS = 1, COMMENT = 2, ASSIGNMENT = 3, MENTION = 4;

    /** The notifications one request wants to send, by recipient. */
    public static final class Notices {
        private record Notice(int rank, String text) {}

        private final Map<UUID, Notice> byRecipient = new LinkedHashMap<>();

        public void add(UUID recipientId, int rank, String text) {
            if (recipientId == null) return;
            Notice current = byRecipient.get(recipientId);
            if (current == null || rank > current.rank()) byRecipient.put(recipientId, new Notice(rank, text));
        }

        public boolean isEmpty() {
            return byRecipient.isEmpty();
        }
    }

    private final NotificationService notifications;
    private final TaskRepository tasks;

    public TaskNotificationService(NotificationService notifications, TaskRepository tasks) {
        this.notifications = notifications;
        this.tasks = tasks;
    }

    /** "HR-12", or "Personal task" for a task without a project. */
    public static String label(String key) {
        return key == null ? "Personal task" : key;
    }

    /** Where the bell line leads: {@code /tasks/HR-12}, or {@code /tasks/<id>} for a personal task. */
    public static String link(String key, UUID taskId) {
        return "/tasks/" + (key == null ? taskId.toString() : key);
    }

    /** Sends the collected notifications (joins the caller's transaction, so a rolled-back change sends nothing). */
    public void deliver(Notices notices, UUID actorId, String link) {
        notices.byRecipient.remove(actorId);
        if (notices.isEmpty()) return;
        for (UUID recipientId : tasks.findNotifiable(new ArrayList<>(notices.byRecipient.keySet()))) {
            notifications.notify(recipientId, notices.byRecipient.get(recipientId).text(), link);
        }
    }
}
