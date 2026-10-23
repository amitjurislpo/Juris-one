package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Mentions;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.TaskCommentRepository;
import com.jurislpo.jurisone.repository.TaskCommentRepository.CommentRow;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.TaskFacts;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments on tasks with {@code @mentions} (plan §10.3, §12.1 C1–C4). Commenters and mentioned people (who can see the task) follow
 * it; mentioned people and followers are told in the bell. A deleted comment keeps its place ("Comment deleted").
 */
@Service
@Transactional
public class TaskCommentService {

    public static final int PAGE_SIZE = 50;

    public record CommentInput(String body) {}

    private final TaskCommentRepository repo;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final TaskActivityService activity;
    private final TaskNotificationService notifications;
    private final AuditService audit;

    public TaskCommentService(TaskCommentRepository repo, TaskRepository tasks, TaskService taskService, TaskActivityService activity,
                              TaskNotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.tasks = tasks;
        this.taskService = taskService;
        this.activity = activity;
        this.notifications = notifications;
        this.audit = audit;
    }

    static String body(CommentInput input) {
        return Check.trimmed(input == null ? null : input.body(), 1, "Write a comment.", 5000, "Keep the comment under 5,000 characters.");
    }

    /** {@code {page, take, total, rows}}, oldest first, with each comment's mentioned people and the viewer's rights on it. */
    public Map<String, Object> page(CurrentUser user, UUID taskId, String pageRaw) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        int page = TaskInputs.page(pageRaw);
        List<Map<String, Object>> rows = repo.findPage(taskId, PAGE_SIZE, (page - 1) * PAGE_SIZE);
        Set<UUID> mentionedIds = new HashSet<>();
        for (Map<String, Object> row : rows) for (Object id : (List<?>) row.get("mentionedEmployeeIds")) mentionedIds.add(UUID.fromString((String) id));
        Map<String, Object> names = new LinkedHashMap<>();
        for (Map<String, Object> person : tasks.findNames(new ArrayList<>(mentionedIds))) names.put((String) person.get("id"), person.get("name"));
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> view = new LinkedHashMap<>(row);
            Map<String, Object> mentionedPeople = new LinkedHashMap<>();
            for (Object id : (List<?>) view.remove("mentionedEmployeeIds")) mentionedPeople.put((String) id, names.get(id));
            view.put("mentionedPeople", mentionedPeople);
            boolean isDeleted = Boolean.TRUE.equals(row.get("isDeleted"));
            boolean isAuthor = user.id().toString().equals(row.get("authorId"));
            view.put("canEdit", !isDeleted && isAuthor);
            view.put("canDelete", !isDeleted && (isAuthor || canModerate(user, access)));
            views.add(view);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", page);
        out.put("take", PAGE_SIZE);
        out.put("total", repo.count(taskId));
        out.put("rows", views);
        return out;
    }

    /** Project owners and the Owner may delete anyone's comment. */
    static boolean canModerate(CurrentUser user, TaskService.AccessibleTask access) {
        return access.permissions().canManageProject() || ProjectService.isTaskAdmin(user);
    }

    private static void requireWritable(TaskFacts task) {
        if (task.projectId() != null && "ARCHIVED".equals(task.projectStatus())) throw new UserException(ProjectService.ARCHIVED);
    }

    public String add(CurrentUser user, UUID taskId, CommentInput input, String ipAddress) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        if (!access.permissions().canComment()) throw new ForbiddenException("You can't comment on this task.");
        TaskFacts task = access.task();
        requireWritable(task);
        String body = body(input);
        Set<UUID> mentioned = taskService.visibleMentions(taskId, Mentions.find(body));
        UUID id = UUID.randomUUID();
        repo.insert(id, taskId, user.id(), body, mentioned);
        tasks.addWatcher(taskId, user.id());
        for (UUID mentionedId : mentioned) tasks.addWatcher(taskId, mentionedId);
        tasks.touch(taskId);
        Map<String, Object> after = new LinkedHashMap<>(TaskActivityService.length(body));
        after.put("commentId", id.toString());
        after.put("mentions", mentioned.size());
        audit.log(user.id(), "task.comment_add", "task", taskId.toString(), null, after, ipAddress);

        String label = TaskNotificationService.label(task.key());
        TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
        for (UUID mentionedId : mentioned) notices.add(mentionedId, TaskNotificationService.MENTION, user.name() + " mentioned you on " + label + ": " + task.title());
        for (UUID followerId : tasks.findWatcherIds(taskId)) notices.add(followerId, TaskNotificationService.COMMENT, user.name() + " commented on " + label + ": " + task.title());
        notifications.deliver(notices, user.id(), TaskNotificationService.link(task.key(), taskId));
        return id.toString();
    }

    private CommentRow comment(UUID commentId, UUID taskId) {
        CommentRow comment = repo.find(commentId, taskId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Comment not found."));
        if (comment.isDeleted()) throw new UserException(HttpStatus.NOT_FOUND, "Comment not found.");
        return comment;
    }

    /** Only the author edits a comment; people mentioned for the first time are told. */
    public void edit(CurrentUser user, UUID taskId, UUID commentId, CommentInput input, String ipAddress) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        CommentRow comment = comment(commentId, taskId);
        if (!user.id().equals(comment.authorId())) throw new ForbiddenException("Only the author can edit a comment.");
        TaskFacts task = access.task();
        requireWritable(task);
        String body = body(input);
        Set<UUID> mentioned = taskService.visibleMentions(taskId, Mentions.find(body));
        repo.updateBody(commentId, body, mentioned);
        Set<UUID> newlyMentioned = new HashSet<>(mentioned);
        for (Object id : comment.mentionedEmployeeIds()) newlyMentioned.remove(UUID.fromString((String) id));
        for (UUID mentionedId : newlyMentioned) tasks.addWatcher(taskId, mentionedId);
        Map<String, Object> before = new LinkedHashMap<>(TaskActivityService.length(comment.body()));
        before.put("commentId", commentId.toString());
        Map<String, Object> after = new LinkedHashMap<>(TaskActivityService.length(body));
        after.put("commentId", commentId.toString());
        audit.log(user.id(), "task.comment_edit", "task", taskId.toString(), before, after, ipAddress);
        TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
        String label = TaskNotificationService.label(task.key());
        for (UUID mentionedId : newlyMentioned) notices.add(mentionedId, TaskNotificationService.MENTION, user.name() + " mentioned you on " + label + ": " + task.title());
        notifications.deliver(notices, user.id(), TaskNotificationService.link(task.key(), taskId));
    }

    /** The author, a project owner or the Owner hides a comment (soft delete). */
    public void delete(CurrentUser user, UUID taskId, UUID commentId, String ipAddress) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        CommentRow comment = comment(commentId, taskId);
        if (!user.id().equals(comment.authorId()) && !canModerate(user, access)) throw new ForbiddenException("You can't delete this comment.");
        TaskFacts task = access.task();
        requireWritable(task);
        repo.softDelete(commentId, user.id());
        Map<String, Object> before = new LinkedHashMap<>(TaskActivityService.length(comment.body()));
        before.put("commentId", commentId.toString());
        before.put("authorId", comment.authorId().toString());
        activity.record(user.id(), taskId, task.projectId(), "COMMENT_DELETED", before, null);
        audit.log(user.id(), "task.comment_delete", "task", taskId.toString(), before, null, ipAddress);
    }
}
