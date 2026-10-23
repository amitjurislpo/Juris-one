package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Mentions;
import com.jurislpo.jurisone.engine.TaskAccess;
import com.jurislpo.jurisone.engine.TaskAccess.TaskPermissions;
import com.jurislpo.jurisone.engine.TaskDueDates;
import com.jurislpo.jurisone.engine.TaskKeys;
import com.jurislpo.jurisone.engine.TaskPositions;
import com.jurislpo.jurisone.engine.WorkflowRules;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ProjectRepository.ProjectFacts;
import com.jurislpo.jurisone.repository.TaskLabelRepository;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.NewTask;
import com.jurislpo.jurisone.repository.TaskRepository.TaskFacts;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository.StatusRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and changing tasks (plan §8, §12.1 T5–T14): field-level rights (§4.3/§4.4), numbering, card order, soft delete, followers,
 * checklist. Every change writes the task's history and the audit log, and tells the people concerned (bell).
 */
@Service
@Transactional
public class TaskService {

    public static final List<String> TYPES = List.of("TASK", "ISSUE", "REQUEST", "MILESTONE");
    public static final List<String> PRIORITIES = List.of("URGENT", "HIGH", "MEDIUM", "LOW", "NONE");
    static final int MAX_LABELS = 20, MAX_SUBTASKS = 100, MAX_CHECKLIST = 100, MAX_WATCHERS = 50;
    static final String NOT_FOUND = "Task not found.";
    static final String CONFLICT = "Someone else changed this task. Reload to see their changes.";

    public record TaskInput(String projectId, String title, String description, String type, String statusId, String priority, String assigneeId,
                            String indirectAssigneeId, String startDate, String dueDate, String dueTime, Integer estimateMinutes, Integer reminderBeforeMinutes, List<String> labelIds,
                            String parentTaskId, List<String> watcherIds, List<String> checklist) {}

    public record TaskPositionInput(String statusId, String previousTaskId, String nextTaskId) {}

    public record ChecklistItemInput(String text, Boolean isDone, Integer position) {}

    /** A task the user can see, with their rights on it. */
    public record AccessibleTask(TaskFacts task, TaskAccess.Facts facts, TaskPermissions permissions) {
        public boolean isDirectAssignee() {
            return facts.isDirectAssignee();
        }
    }

    private final TaskRepository repo;
    private final WorkflowStatusRepository statuses;
    private final TaskLabelRepository labels;
    private final ProjectService projects;
    private final TaskActivityService activity;
    private final TaskNotificationService notifications;
    private final AuditService audit;

    public TaskService(TaskRepository repo, WorkflowStatusRepository statuses, TaskLabelRepository labels, ProjectService projects, TaskActivityService activity,
                       TaskNotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.statuses = statuses;
        this.labels = labels;
        this.projects = projects;
        this.activity = activity;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ───────────── access ─────────────

    static TaskAccess.Facts factsOf(TaskFacts task, UUID employeeId) {
        return new TaskAccess.Facts(task.isTaskAdmin(), task.projectId() != null, task.visibility(), task.projectRole(), employeeId.equals(task.assigneeId()),
                employeeId.equals(task.indirectAssigneeId()), employeeId.equals(task.reporterId()), task.isWatcher(), task.isManagerOfAssignee());
    }

    /** What {@code employeeId} may do with the task (empty when it does not exist). */
    Optional<AccessibleTask> accessFor(UUID taskId, UUID employeeId) {
        return repo.findFacts(taskId, employeeId).stream().findFirst().map(task -> {
            TaskAccess.Facts facts = factsOf(task, employeeId);
            return new AccessibleTask(task, facts, TaskAccess.of(facts));
        });
    }

    /** The task as the user sees it; 404 when it does not exist, was deleted or is not visible to them. */
    public AccessibleTask access(CurrentUser user, UUID taskId) {
        AccessibleTask task = accessFor(taskId, user.id()).orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (!task.permissions().canView()) throw new UserException(HttpStatus.NOT_FOUND, NOT_FOUND);
        return task;
    }

    /** A task id from a uuid or a key such as {@code HR-12} (also a key the task had before it moved project). */
    public UUID resolve(String taskRef) {
        if (taskRef != null && taskRef.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) return UUID.fromString(taskRef);
        Optional<TaskKeys.ParsedKey> key = TaskKeys.parseKey(taskRef);
        if (key.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, NOT_FOUND);
        List<UUID> found = repo.findIdByKey(key.get().projectKey(), key.get().number(), TaskKeys.formatKey(key.get().projectKey(), key.get().number()));
        if (found.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, NOT_FOUND);
        return found.get(0);
    }

    private static void require(boolean isAllowed, String message) {
        if (!isAllowed) throw new ForbiddenException(message);
    }

    /** Writes on a task of an archived project are refused. */
    private void requireActiveProject(TaskFacts task) {
        if (task.projectId() != null && "ARCHIVED".equals(task.projectStatus())) throw new UserException(ProjectService.ARCHIVED);
    }

    // ───────────── input checks ─────────────

    static String title(String value) {
        return Check.trimmed(value, 1, "Enter a title.", 200, "Keep the title under 200 characters.");
    }

    static String description(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        if (text.length() > 20_000) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the description under 20,000 characters.");
        return text;
    }

    static Integer estimate(Integer value) {
        if (value != null && (value < 0 || value > 100_000)) throw new UserException(HttpStatus.BAD_REQUEST, "Enter an estimate between 0 and 100,000 minutes.");
        return value;
    }

    /** The extra reminder before the due moment: null (none) or one of 15, 60, 240, 1440 minutes; it needs a due date. */
    static Integer reminderBeforeMinutes(Integer value, LocalDate dueDate) {
        if (value == null) return null;
        if (!TaskDueDates.REMINDER_BEFORE_MINUTES.contains(value)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time.");
        if (dueDate == null) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a due date first.");
        return value;
    }

    static void checkDates(LocalDate startDate, LocalDate dueDate, LocalTime dueTime) {
        if (dueTime != null && dueDate == null) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a due date first.");
        if (startDate != null && dueDate != null && dueDate.isBefore(startDate)) throw new UserException(HttpStatus.BAD_REQUEST, "The due date can't be before the start date.");
    }

    private UUID person(String value, String message) {
        UUID id = TaskInputs.optionalUuid(value, message);
        if (id != null && !repo.isActiveEmployee(id)) throw new UserException(HttpStatus.BAD_REQUEST, message);
        return id;
    }

    static void checkAssigneesDiffer(UUID assigneeId, UUID indirectAssigneeId) {
        if (indirectAssigneeId != null && indirectAssigneeId.equals(assigneeId)) {
            throw new UserException(HttpStatus.BAD_REQUEST, "The accountable person must be someone other than the assignee.");
        }
    }

    private List<UUID> labelIds(List<String> values, UUID projectId) {
        List<UUID> ids = TaskInputs.uuids(values, "Choose labels of this project or of the business.");
        if (ids.size() > MAX_LABELS) throw new UserException(HttpStatus.BAD_REQUEST, "A task can have at most " + MAX_LABELS + " labels.");
        if (labels.countUsable(ids, projectId) != ids.size()) throw new UserException(HttpStatus.BAD_REQUEST, "Choose labels of this project or of the business.");
        return ids;
    }

    private static String checklistText(String value) {
        return Check.trimmed(value, 1, "Enter the checklist item.", 300, "Keep checklist items under 300 characters.");
    }

    /** The parent of a sub-task: visible, in the same project (or both personal), not itself a sub-task, with room for one more. */
    private UUID parent(CurrentUser user, String value, UUID projectId, UUID taskId) {
        UUID parentId = TaskInputs.optionalUuid(value, "Choose the parent task.");
        if (parentId == null) return null;
        if (parentId.equals(taskId)) throw new UserException("A task can't be its own sub-task.");
        TaskFacts parent = access(user, parentId).task();
        if (!Objects.equals(parent.projectId(), projectId)) throw new UserException("A sub-task must be in the same project as its parent.");
        if (parent.parentTaskId() != null) throw new UserException("A sub-task can't have its own sub-tasks.");
        if (taskId != null && repo.countSubtasks(taskId) > 0) throw new UserException("A task with sub-tasks can't become a sub-task.");
        if (repo.countSubtasks(parentId) >= MAX_SUBTASKS) throw new UserException("A task can have at most " + MAX_SUBTASKS + " sub-tasks.");
        return parentId;
    }

    /** The steps a task of {@code projectId} can be in (the default set for a personal task, created if missing). */
    private List<StatusRow> statusSet(UUID projectId) {
        if (projectId == null) statuses.insertDefaultSetIfMissing(WorkflowRules.DEFAULT_STATUSES);
        return statuses.findSet(projectId);
    }

    private static StatusRow statusIn(List<StatusRow> set, UUID statusId) {
        return set.stream().filter(s -> s.id().equals(statusId)).findFirst()
                .orElseThrow(() -> new UserException(HttpStatus.BAD_REQUEST, "Choose a step of this task's workflow."));
    }

    /** The first step of a category: the starting step for to-do, else the leftmost one (plan §8.2). */
    static StatusRow firstOf(List<StatusRow> set, String category) {
        if (WorkflowRules.TODO.equals(category)) {
            Optional<StatusRow> initial = set.stream().filter(StatusRow::isInitial).findFirst();
            if (initial.isPresent()) return initial.get();
        }
        return set.stream().filter(s -> s.category().equals(category)).findFirst()
                .orElseThrow(() -> new UserException("This workflow has no " + category.toLowerCase().replace('_', ' ') + " step."));
    }

    private static StatusRow initialOf(List<StatusRow> set) {
        return set.stream().filter(StatusRow::isInitial).findFirst().orElse(set.get(0));
    }

    /** Whether {@code employeeId} can see the task now (a follower of a project task must already be able to see it). */
    private boolean canSee(UUID taskId, UUID employeeId) {
        return accessFor(taskId, employeeId).map(a -> a.permissions().canView()).orElse(false);
    }

    // ───────────── create ─────────────

    public Map<String, String> create(CurrentUser user, TaskInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Task details are missing.");
        UUID projectId = TaskInputs.optionalUuid(input.projectId(), "Choose the project.");
        ProjectFacts project = null;
        if (projectId != null) {
            project = projects.visible(user, projectId);
            String role = ProjectService.role(user, project);
            require(TaskAccess.OWNER.equals(role) || TaskAccess.MEMBER.equals(role), "Only members of this project can add tasks to it.");
            ProjectService.requireActive(project);
        }
        String title = title(input.title());
        String description = description(input.description());
        String type = Check.oneOf(input.type() == null ? "TASK" : input.type(), TYPES);
        String priority = Check.oneOf(input.priority() == null ? "NONE" : input.priority(), PRIORITIES);
        UUID assigneeId = person(input.assigneeId(), "Choose an active employee as the assignee.");
        UUID indirectAssigneeId = person(input.indirectAssigneeId(), "Choose an active employee as the accountable person.");
        checkAssigneesDiffer(assigneeId, indirectAssigneeId);
        LocalDate startDate = TaskInputs.optionalDate(input.startDate(), "Enter the start date as YYYY-MM-DD.");
        LocalDate dueDate = TaskInputs.optionalDate(input.dueDate(), "Enter the due date as YYYY-MM-DD.");
        LocalTime dueTime = TaskInputs.optionalTime(input.dueTime());
        checkDates(startDate, dueDate, dueTime);
        Integer reminderBeforeMinutes = reminderBeforeMinutes(input.reminderBeforeMinutes(), dueDate);
        Integer estimateMinutes = estimate(input.estimateMinutes());
        List<UUID> labelIds = labelIds(input.labelIds(), projectId);
        UUID parentTaskId = parent(user, input.parentTaskId(), projectId, null);
        List<UUID> watcherIds = TaskInputs.uuids(input.watcherIds(), "Choose the followers.");
        List<String> checklist = new ArrayList<>();
        if (input.checklist() != null) for (String text : input.checklist()) checklist.add(checklistText(text));
        if (checklist.size() > MAX_CHECKLIST) throw new UserException("A task can have at most " + MAX_CHECKLIST + " checklist items.");
        List<StatusRow> set = statusSet(projectId);
        StatusRow status = input.statusId() == null || input.statusId().isBlank() ? initialOf(set) : statusIn(set, Check.uuid(input.statusId(), "Choose the step."));

        UUID id = UUID.randomUUID();
        Integer number = projectId == null ? null : repo.takeNextNumber(projectId);
        long position = TaskPositions.append(repo.findMaxPosition(projectId, status.id()));
        repo.insert(new NewTask(id, projectId, number, parentTaskId, title, description, type, status.id(), priority, assigneeId, indirectAssigneeId, user.id(),
                startDate, dueDate, dueTime, estimateMinutes, reminderBeforeMinutes, position, WorkflowRules.DONE.equals(status.category())));
        if (!labelIds.isEmpty()) repo.setLabels(id, labelIds);
        for (String text : checklist) repo.insertChecklistItem(UUID.randomUUID(), id, text);
        if (assigneeId != null && projectId != null) projects.ensureMember(user.id(), projectId, assigneeId, ipAddress);
        String key = project == null ? null : TaskKeys.formatKey(project.key(), number);

        // followers: the reporter, both assignees, the people asked for, the people mentioned (who can see it)
        for (UUID followerId : new LinkedHashSet<>(java.util.Arrays.asList(user.id(), assigneeId, indirectAssigneeId))) if (followerId != null) repo.addWatcher(id, followerId);
        for (UUID watcherId : watcherIds) {
            if (!repo.isActiveEmployee(watcherId)) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
            if (projectId != null && !canSee(id, watcherId)) throw new UserException("That person can't see this task.");
            repo.addWatcher(id, watcherId);
        }
        if (repo.countWatchers(id) > MAX_WATCHERS) throw new UserException("A task can have at most " + MAX_WATCHERS + " followers.");
        Set<UUID> mentioned = visibleMentions(id, Mentions.find(description));
        for (UUID mentionedId : mentioned) repo.addWatcher(id, mentionedId);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("key", key);
        after.put("title", title);
        after.put("statusName", status.name());
        after.put("assigneeId", assigneeId == null ? null : assigneeId.toString());
        after.put("indirectAssigneeId", indirectAssigneeId == null ? null : indirectAssigneeId.toString());
        after.put("dueDate", dueDate == null ? null : dueDate.toString());
        if (reminderBeforeMinutes != null) after.put("reminderBeforeMinutes", reminderBeforeMinutes);
        activity.record(user.id(), id, projectId, "CREATED", null, after);
        Map<String, Object> audited = new LinkedHashMap<>(after);
        audited.put("projectId", projectId == null ? null : projectId.toString());
        audited.put("description", TaskActivityService.length(description));
        audit.log(user.id(), "task.create", "task", id.toString(), null, audited, ipAddress);

        TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
        String label = TaskNotificationService.label(key);
        for (UUID mentionedId : mentioned) notices.add(mentionedId, TaskNotificationService.MENTION, user.name() + " mentioned you on " + label + ": " + title);
        notices.add(assigneeId, TaskNotificationService.ASSIGNMENT, user.name() + " assigned you " + label + ": " + title);
        notices.add(indirectAssigneeId, TaskNotificationService.ASSIGNMENT, user.name() + " made you accountable for " + label + ": " + title);
        notifications.deliver(notices, user.id(), TaskNotificationService.link(key, id));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("key", key);
        return out;
    }

    /** The mentioned people who are active and can see the task (others are neither told nor given access). */
    Set<UUID> visibleMentions(UUID taskId, Set<UUID> mentioned) {
        Set<UUID> visible = new LinkedHashSet<>();
        for (UUID employeeId : mentioned) if (repo.isActiveEmployee(employeeId) && canSee(taskId, employeeId)) visible.add(employeeId);
        return visible;
    }

    // ───────────── patch ─────────────

    private static boolean has(JsonNode body, String field) {
        return body.has(field);
    }

    /** A string field: null for JSON null; 400 for another type. */
    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected string for " + field);
        return node.asText();
    }

    private static String nameOf(UUID id) {
        return id == null ? null : id.toString();
    }

    /** {@code PATCH /api/tasks/{taskId}}: only the fields sent change ({@code null} clears); each change needs its own right. */
    public Map<String, Object> patch(CurrentUser user, UUID taskId, JsonNode body, String ipAddress) {
        AccessibleTask access = access(user, taskId);
        TaskFacts task = access.task();
        TaskPermissions rights = access.permissions();
        if (body == null || !body.isObject()) throw new UserException(HttpStatus.BAD_REQUEST, "Send the changes as a JSON object.");
        JsonNode versionNode = body.get("version");
        if (versionNode == null || !versionNode.canConvertToInt()) throw new UserException(HttpStatus.BAD_REQUEST, "Send the version of the task you changed.");
        int version = versionNode.asInt();
        requireActiveProject(task);

        List<String> clauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        List<Object[]> changes = new ArrayList<>(); // {kind, before, after}
        Map<String, Object> auditBefore = new LinkedHashMap<>(), auditAfter = new LinkedHashMap<>();
        TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
        String forbidden = "You can't change this field of the task.";

        // project first: the status and labels are checked against the target project's sets
        UUID projectId = task.projectId();
        ProjectFacts targetProject = null;
        boolean isMoved = false;
        if (has(body, "projectId")) {
            UUID requested = TaskInputs.optionalUuid(text(body, "projectId"), "Choose the project.");
            if (!Objects.equals(requested, task.projectId())) {
                require(rights.canEdit(), forbidden);
                if (task.parentTaskId() != null || repo.countSubtasks(taskId) > 0) {
                    throw new UserException("Only a task without a parent task or sub-tasks can move to another project.");
                }
                if (requested != null) {
                    targetProject = projects.visible(user, requested);
                    String role = ProjectService.role(user, targetProject);
                    require(TaskAccess.OWNER.equals(role) || TaskAccess.MEMBER.equals(role), "Only members of that project can add tasks to it.");
                    ProjectService.requireActive(targetProject);
                }
                projectId = requested;
                isMoved = true;
            }
        }
        List<StatusRow> set = statusSet(projectId);

        if (has(body, "title")) {
            String title = title(text(body, "title"));
            if (!title.equals(task.title())) {
                require(rights.canEdit(), forbidden);
                clauses.add("title = ?");
                values.add(title);
                changes.add(new Object[] {"TITLE_CHANGED", Map.of("title", task.title()), Map.of("title", title)});
                auditBefore.put("title", task.title());
                auditAfter.put("title", title);
            }
        }
        String newTitle = has(body, "title") ? title(text(body, "title")) : task.title();

        Set<UUID> newlyMentioned = new LinkedHashSet<>();
        if (has(body, "description")) {
            String description = description(text(body, "description"));
            if (!Objects.equals(description, task.description())) {
                require(rights.canEdit(), forbidden);
                clauses.add("description = ?");
                values.add(description);
                changes.add(new Object[] {"DESCRIPTION_CHANGED", TaskActivityService.length(task.description()), TaskActivityService.length(description)});
                auditBefore.put("description", TaskActivityService.length(task.description()));
                auditAfter.put("description", TaskActivityService.length(description));
                newlyMentioned.addAll(Mentions.find(description));
                newlyMentioned.removeAll(Mentions.find(task.description()));
            }
        }

        if (has(body, "type")) {
            String type = Check.oneOf(text(body, "type"), TYPES);
            if (!type.equals(task.type())) {
                require(rights.canEdit(), forbidden);
                clauses.add("type = ?");
                values.add(type);
                changes.add(new Object[] {"TYPE_CHANGED", Map.of("type", task.type()), Map.of("type", type)});
                auditBefore.put("type", task.type());
                auditAfter.put("type", type);
            }
        }

        // status: statusId or statusCategory (never both); a move to another project keeps the category
        StatusRow newStatus = null;
        if (has(body, "statusId") && has(body, "statusCategory")) throw new UserException(HttpStatus.BAD_REQUEST, "Send either statusId or statusCategory, not both.");
        if (has(body, "statusId")) newStatus = statusIn(set, Check.uuid(text(body, "statusId"), "Choose the step."));
        else if (has(body, "statusCategory")) {
            String category = Check.oneOf(text(body, "statusCategory"), WorkflowRules.CATEGORIES);
            newStatus = category.equals(task.statusCategory()) && !isMoved ? statusIn(set, task.statusId()) : firstOf(set, category);
        } else if (isMoved) newStatus = firstOf(set, task.statusCategory());
        boolean isStatusChanged = newStatus != null && !newStatus.id().equals(task.statusId());
        if (isStatusChanged) {
            if (!isMoved) require(rights.canChangeStatus(), forbidden);
            boolean isDone = WorkflowRules.DONE.equals(newStatus.category());
            clauses.add("status_id = ?");
            values.add(newStatus.id());
            clauses.add("completed_at = CASE WHEN ? THEN coalesce(completed_at, now()) END");
            values.add(isDone);
            clauses.add("completed_by = CASE WHEN ? THEN coalesce(completed_by, ?::uuid) END");
            values.add(isDone);
            values.add(user.id());
            clauses.add("position = ?");
            values.add(TaskPositions.append(repo.findMaxPosition(projectId, newStatus.id())));
            changes.add(new Object[] {"STATUS_CHANGED", Map.of("statusName", task.statusName()), Map.of("statusName", newStatus.name())});
            auditBefore.put("statusId", task.statusId().toString());
            auditAfter.put("statusId", newStatus.id().toString());
        }

        if (has(body, "priority")) {
            String priority = Check.oneOf(text(body, "priority"), PRIORITIES);
            if (!priority.equals(task.priority())) {
                require(rights.canChangePriority(), forbidden);
                clauses.add("priority = ?");
                values.add(priority);
                changes.add(new Object[] {"PRIORITY_CHANGED", Map.of("priority", task.priority()), Map.of("priority", priority)});
                auditBefore.put("priority", task.priority());
                auditAfter.put("priority", priority);
            }
        }

        UUID assigneeId = task.assigneeId();
        if (has(body, "assigneeId")) {
            UUID requested = person(text(body, "assigneeId"), "Choose an active employee as the assignee.");
            if (!Objects.equals(requested, task.assigneeId())) {
                require(rights.canChangeDirectAssignee(), forbidden);
                assigneeId = requested;
                clauses.add("assignee_id = ?");
                values.add(assigneeId);
                Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                before.put("assigneeId", nameOf(task.assigneeId()));
                after.put("assigneeId", nameOf(assigneeId));
                changes.add(new Object[] {"ASSIGNEE_CHANGED", before, after});
                auditBefore.putAll(before);
                auditAfter.putAll(after);
            }
        }
        UUID indirectAssigneeId = task.indirectAssigneeId();
        if (has(body, "indirectAssigneeId")) {
            UUID requested = person(text(body, "indirectAssigneeId"), "Choose an active employee as the accountable person.");
            if (!Objects.equals(requested, task.indirectAssigneeId())) {
                boolean isLeavingThemself = requested == null && user.id().equals(task.indirectAssigneeId());
                require(rights.canChangeIndirectAssignee() || isLeavingThemself, forbidden);
                indirectAssigneeId = requested;
                clauses.add("indirect_assignee_id = ?");
                values.add(indirectAssigneeId);
                Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                before.put("indirectAssigneeId", nameOf(task.indirectAssigneeId()));
                after.put("indirectAssigneeId", nameOf(indirectAssigneeId));
                changes.add(new Object[] {"INDIRECT_ASSIGNEE_CHANGED", before, after});
                auditBefore.putAll(before);
                auditAfter.putAll(after);
            }
        }
        checkAssigneesDiffer(assigneeId, indirectAssigneeId);

        LocalDate startDate = has(body, "startDate") ? TaskInputs.optionalDate(text(body, "startDate"), "Enter the start date as YYYY-MM-DD.") : task.startDate();
        LocalDate dueDate = has(body, "dueDate") ? TaskInputs.optionalDate(text(body, "dueDate"), "Enter the due date as YYYY-MM-DD.") : task.dueDate();
        LocalTime dueTime = has(body, "dueTime") ? TaskInputs.optionalTime(text(body, "dueTime")) : task.dueTime();
        if (has(body, "dueDate") && dueDate == null) dueTime = null; // clearing the due date clears the time
        checkDates(startDate, dueDate, dueTime);
        Integer reminderBeforeMinutes = task.reminderBeforeMinutes();
        if (has(body, "reminderBeforeMinutes")) {
            JsonNode node = body.get("reminderBeforeMinutes");
            if (!node.isNull() && !node.isInt()) throw new UserException(HttpStatus.BAD_REQUEST, "Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time.");
            reminderBeforeMinutes = node.isNull() ? null : node.asInt();
        }
        if (dueDate == null) reminderBeforeMinutes = null; // clearing the due date clears the reminder
        reminderBeforeMinutes = reminderBeforeMinutes(reminderBeforeMinutes, dueDate);
        if (!Objects.equals(startDate, task.startDate()) || !Objects.equals(dueDate, task.dueDate()) || !Objects.equals(dueTime, task.dueTime())
                || !Objects.equals(reminderBeforeMinutes, task.reminderBeforeMinutes())) {
            require(rights.canEdit(), forbidden);
            clauses.add("start_date = ?");
            values.add(startDate == null ? null : Date.valueOf(startDate));
            clauses.add("due_date = ?");
            values.add(dueDate == null ? null : Date.valueOf(dueDate));
            clauses.add("due_time = ?");
            values.add(dueTime == null ? null : Time.valueOf(dueTime));
            // a new due date or time re-arms the reminders
            clauses.add("reminder_before_minutes = ?");
            values.add(reminderBeforeMinutes);
            clauses.add("due_soon_notified_for = NULL, due_today_notified_for = NULL, overdue_notified_for = NULL, reminder_notified_for = NULL");
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            before.put("startDate", task.startDate() == null ? null : task.startDate().toString());
            before.put("dueDate", task.dueDate() == null ? null : task.dueDate().toString());
            before.put("dueTime", task.dueTime() == null ? null : task.dueTime().toString());
            before.put("reminderBeforeMinutes", task.reminderBeforeMinutes());
            after.put("startDate", startDate == null ? null : startDate.toString());
            after.put("dueDate", dueDate == null ? null : dueDate.toString());
            after.put("dueTime", dueTime == null ? null : dueTime.toString());
            after.put("reminderBeforeMinutes", reminderBeforeMinutes);
            changes.add(new Object[] {"DATES_CHANGED", before, after});
            auditBefore.putAll(before);
            auditAfter.putAll(after);
        }

        if (has(body, "estimateMinutes")) {
            JsonNode node = body.get("estimateMinutes");
            if (!node.isNull() && !node.canConvertToInt()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter an estimate between 0 and 100,000 minutes.");
            Integer estimateMinutes = estimate(node.isNull() ? null : node.asInt());
            if (!Objects.equals(estimateMinutes, task.estimateMinutes())) {
                require(rights.canEdit(), forbidden);
                clauses.add("estimate_minutes = ?");
                values.add(estimateMinutes);
                Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                before.put("estimateMinutes", task.estimateMinutes());
                after.put("estimateMinutes", estimateMinutes);
                changes.add(new Object[] {"ESTIMATE_CHANGED", before, after});
                auditBefore.putAll(before);
                auditAfter.putAll(after);
            }
        }

        List<UUID> currentLabelIds = repo.findLabelIds(taskId);
        List<UUID> newLabelIds = null;
        if (has(body, "labelIds")) {
            JsonNode node = body.get("labelIds");
            List<String> raw = new ArrayList<>();
            if (!node.isNull()) {
                if (!node.isArray()) throw new UserException(HttpStatus.BAD_REQUEST, "Choose labels of this project or of the business.");
                node.forEach(item -> raw.add(item.asText()));
            }
            List<UUID> requested = labelIds(raw, projectId);
            if (!new HashSet<>(requested).equals(new HashSet<>(currentLabelIds))) {
                require(rights.canEdit(), forbidden);
                newLabelIds = requested;
            }
        } else if (isMoved && !currentLabelIds.isEmpty()) {
            // labels of the old project do not follow the task
            List<UUID> kept = new ArrayList<>();
            for (UUID labelId : currentLabelIds) if (labels.countUsable(List.of(labelId), projectId) == 1) kept.add(labelId);
            if (kept.size() != currentLabelIds.size()) newLabelIds = kept;
        }
        if (newLabelIds != null) {
            changes.add(new Object[] {"LABELS_CHANGED", Map.of("labelIds", currentLabelIds.stream().map(UUID::toString).toList()),
                    Map.of("labelIds", newLabelIds.stream().map(UUID::toString).toList())});
            auditBefore.put("labelIds", currentLabelIds.stream().map(UUID::toString).toList());
            auditAfter.put("labelIds", newLabelIds.stream().map(UUID::toString).toList());
        }

        if (has(body, "parentTaskId")) {
            String raw = text(body, "parentTaskId");
            UUID requested = TaskInputs.optionalUuid(raw, "Choose the parent task.");
            if (!Objects.equals(requested, task.parentTaskId())) {
                require(rights.canEdit(), forbidden);
                UUID parentId = parent(user, raw, projectId, taskId);
                clauses.add("parent_task_id = ?");
                values.add(parentId);
                Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
                before.put("parentTaskId", nameOf(task.parentTaskId()));
                after.put("parentTaskId", nameOf(parentId));
                changes.add(new Object[] {"PARENT_CHANGED", before, after});
                auditBefore.putAll(before);
                auditAfter.putAll(after);
            }
        }

        String newKey = task.key();
        if (isMoved) {
            Integer number = projectId == null ? null : repo.takeNextNumber(projectId);
            clauses.add("project_id = ?");
            values.add(projectId);
            clauses.add("number = ?");
            values.add(number);
            if (task.key() != null) {
                clauses.add("previous_keys = array_append(previous_keys, ?)");
                values.add(task.key());
            }
            if (!isStatusChanged) {
                // same step id cannot exist in another set; keep the card at the bottom of its new column
                clauses.add("position = ?");
                values.add(TaskPositions.append(repo.findMaxPosition(projectId, newStatus.id())));
            }
            newKey = targetProject == null ? null : TaskKeys.formatKey(targetProject.key(), number);
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            before.put("projectId", nameOf(task.projectId()));
            before.put("key", task.key());
            after.put("projectId", nameOf(projectId));
            after.put("key", newKey);
            changes.add(new Object[] {"MOVED_PROJECT", before, after});
            auditBefore.putAll(before);
            auditAfter.putAll(after);
        }

        if (changes.isEmpty()) return Map.of("version", task.version());
        if (clauses.isEmpty()) clauses.add("title = title");
        Integer newVersion = repo.update(taskId, version, clauses, values);
        if (newVersion == null) throw new UserException(HttpStatus.CONFLICT, CONFLICT);
        if (newLabelIds != null) repo.setLabels(taskId, newLabelIds);
        if (projectId != null && assigneeId != null && (isMoved || !Objects.equals(assigneeId, task.assigneeId()))) {
            projects.ensureMember(user.id(), projectId, assigneeId, ipAddress);
        }
        if (assigneeId != null) repo.addWatcher(taskId, assigneeId);
        if (indirectAssigneeId != null) repo.addWatcher(taskId, indirectAssigneeId);
        Set<UUID> mentioned = visibleMentions(taskId, newlyMentioned);
        for (UUID mentionedId : mentioned) repo.addWatcher(taskId, mentionedId);
        for (Object[] change : changes) activity.record(user.id(), taskId, projectId, (String) change[0], change[1], change[2]);
        auditAfter.put("version", newVersion);
        audit.log(user.id(), "task.update", "task", taskId.toString(), auditBefore, auditAfter, ipAddress);

        String label = TaskNotificationService.label(newKey);
        if (isStatusChanged) {
            for (UUID followerId : repo.findWatcherIds(taskId)) {
                notices.add(followerId, TaskNotificationService.STATUS, label + " moved to " + newStatus.name() + " by " + user.name());
            }
        }
        if (!Objects.equals(assigneeId, task.assigneeId())) notices.add(assigneeId, TaskNotificationService.ASSIGNMENT, user.name() + " assigned you " + label + ": " + newTitle);
        if (!Objects.equals(indirectAssigneeId, task.indirectAssigneeId())) {
            notices.add(indirectAssigneeId, TaskNotificationService.ASSIGNMENT, user.name() + " made you accountable for " + label + ": " + newTitle);
        }
        for (UUID mentionedId : mentioned) notices.add(mentionedId, TaskNotificationService.MENTION, user.name() + " mentioned you on " + label + ": " + newTitle);
        notifications.deliver(notices, user.id(), TaskNotificationService.link(newKey, taskId));
        return Map.of("version", newVersion);
    }

    // ───────────── board position ─────────────

    private Long neighbour(String raw, UUID taskId, UUID projectId, UUID statusId) {
        UUID neighbourId = TaskInputs.optionalUuid(raw, "Invalid UUID");
        if (neighbourId == null) return null;
        Long position = neighbourId.equals(taskId) ? null : repo.findPositionInColumn(neighbourId, projectId, statusId);
        if (position == null) throw new UserException(HttpStatus.BAD_REQUEST, "The cards next to the drop place are not in that column.");
        return position;
    }

    /** A board drop: the step (column) and the cards around the drop place; the server computes the position (plan §8.2). */
    public Map<String, Object> move(CurrentUser user, UUID taskId, TaskPositionInput input, String ipAddress) {
        AccessibleTask access = access(user, taskId);
        TaskFacts task = access.task();
        require(access.permissions().canChangeStatus(), "You can't move this task.");
        requireActiveProject(task);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Choose the step.");
        StatusRow status = statusIn(statusSet(task.projectId()), Check.uuid(input.statusId(), "Choose the step."));
        Long previous = neighbour(input.previousTaskId(), taskId, task.projectId(), status.id());
        Long next = neighbour(input.nextTaskId(), taskId, task.projectId(), status.id());
        Long position = previous == null && next == null ? Long.valueOf(TaskPositions.append(repo.findMaxPosition(task.projectId(), status.id()))) : TaskPositions.between(previous, next);
        if (position == null) {
            // no room between the two cards: renumber the column (without this card), then place it
            List<Map<String, Object>> column = repo.findColumn(task.projectId(), status.id());
            int index = 0;
            for (Map<String, Object> card : column) {
                if (card.get("id").equals(taskId)) continue;
                repo.setPosition((UUID) card.get("id"), TaskPositions.renumbered(index++));
            }
            previous = neighbour(input.previousTaskId(), taskId, task.projectId(), status.id());
            next = neighbour(input.nextTaskId(), taskId, task.projectId(), status.id());
            position = TaskPositions.between(previous, next);
            if (position == null) throw new UserException(HttpStatus.BAD_REQUEST, "The cards next to the drop place are not in that column.");
        }
        boolean isStatusChanged = !status.id().equals(task.statusId());
        if (isStatusChanged) {
            repo.setStatusAndPosition(taskId, status.id(), position, WorkflowRules.DONE.equals(status.category()), user.id());
            activity.record(user.id(), taskId, task.projectId(), "STATUS_CHANGED", Map.of("statusName", task.statusName()), Map.of("statusName", status.name()));
            TaskNotificationService.Notices notices = new TaskNotificationService.Notices();
            for (UUID followerId : repo.findWatcherIds(taskId)) {
                notices.add(followerId, TaskNotificationService.STATUS, TaskNotificationService.label(task.key()) + " moved to " + status.name() + " by " + user.name());
            }
            notifications.deliver(notices, user.id(), TaskNotificationService.link(task.key(), taskId));
        } else {
            repo.setPosition(taskId, position);
            repo.touch(taskId);
        }
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        before.put("statusId", task.statusId().toString());
        after.put("statusId", status.id().toString());
        after.put("position", position);
        audit.log(user.id(), "task.move", "task", taskId.toString(), before, after, ipAddress);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusId", status.id().toString());
        out.put("position", position);
        return out;
    }

    // ───────────── delete ─────────────

    /** Soft delete (the task and its sub-tasks); nothing is ever hard-deleted. */
    public void delete(CurrentUser user, UUID taskId, String ipAddress) {
        AccessibleTask access = access(user, taskId);
        TaskFacts task = access.task();
        require(access.permissions().canDelete(), "You can't delete this task.");
        requireActiveProject(task);
        int deleted = repo.softDelete(taskId, user.id());
        activity.record(user.id(), taskId, task.projectId(), "DELETED", null, Map.of("deletedTasks", deleted));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", task.key());
        before.put("title", task.title());
        audit.log(user.id(), "task.delete", "task", taskId.toString(), before, Map.of("deletedTasks", deleted), ipAddress);
    }

    // ───────────── followers ─────────────

    public void follow(CurrentUser user, UUID taskId, UUID employeeId, String ipAddress) {
        AccessibleTask access = access(user, taskId);
        boolean isSelf = employeeId.equals(user.id());
        if (!isSelf) require(access.permissions().canEdit(), "You can't add followers to this task.");
        if (!repo.isActiveEmployee(employeeId)) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        if (repo.isWatcher(taskId, employeeId)) return;
        if (access.task().projectId() != null && !isSelf && !canSee(taskId, employeeId)) throw new UserException("That person can't see this task.");
        if (repo.countWatchers(taskId) >= MAX_WATCHERS) throw new UserException("A task can have at most " + MAX_WATCHERS + " followers.");
        repo.addWatcher(taskId, employeeId);
        audit.log(user.id(), "task.watcher_add", "task", taskId.toString(), null, Map.of("employeeId", employeeId.toString()), ipAddress);
    }

    public void unfollow(CurrentUser user, UUID taskId, UUID employeeId, String ipAddress) {
        AccessibleTask access = access(user, taskId);
        if (!employeeId.equals(user.id())) require(access.permissions().canEdit(), "You can't remove followers from this task.");
        if (repo.removeWatcher(taskId, employeeId) > 0) {
            audit.log(user.id(), "task.watcher_remove", "task", taskId.toString(), Map.of("employeeId", employeeId.toString()), null, ipAddress);
        }
    }

    // ───────────── checklist ─────────────

    /** The checklist can be changed by whoever may edit the task, and by the direct assignee. */
    private AccessibleTask checklistAccess(CurrentUser user, UUID taskId) {
        AccessibleTask access = access(user, taskId);
        require(access.permissions().canEdit() || access.isDirectAssignee(), "You can't change the checklist of this task.");
        requireActiveProject(access.task());
        return access;
    }

    private void checklistChanged(CurrentUser user, TaskFacts task, Object before, Object after, String ipAddress) {
        repo.touch(task.id());
        activity.record(user.id(), task.id(), task.projectId(), "CHECKLIST_CHANGED", before, after);
        audit.log(user.id(), "task.checklist_change", "task", task.id().toString(), before, after, ipAddress);
    }

    public String addChecklistItem(CurrentUser user, UUID taskId, ChecklistItemInput input, String ipAddress) {
        AccessibleTask access = checklistAccess(user, taskId);
        String text = checklistText(input == null ? null : input.text());
        if (repo.countChecklistItems(taskId) >= MAX_CHECKLIST) throw new UserException("A task can have at most " + MAX_CHECKLIST + " checklist items.");
        UUID id = UUID.randomUUID();
        repo.insertChecklistItem(id, taskId, text);
        checklistChanged(user, access.task(), null, Map.of("added", text), ipAddress);
        return id.toString();
    }

    private Map<String, Object> checklistItem(UUID taskId, UUID itemId) {
        return repo.findChecklistItem(taskId, itemId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Checklist item not found."));
    }

    public void changeChecklistItem(CurrentUser user, UUID taskId, UUID itemId, ChecklistItemInput input, String ipAddress) {
        AccessibleTask access = checklistAccess(user, taskId);
        Map<String, Object> item = checklistItem(taskId, itemId);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Checklist item details are missing.");
        Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        if (input.text() != null) {
            String text = checklistText(input.text());
            if (!text.equals(item.get("text"))) {
                repo.updateChecklistText(itemId, text);
                before.put("text", item.get("text"));
                after.put("text", text);
            }
        }
        if (input.isDone() != null && !input.isDone().equals(item.get("isDone"))) {
            repo.updateChecklistDone(itemId, input.isDone(), user.id());
            before.put("text", item.get("text"));
            before.put("isDone", item.get("isDone"));
            after.put("isDone", input.isDone());
        }
        if (input.position() != null) {
            if (input.position() < 1) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a position from 1.");
            repo.moveChecklistItem(taskId, itemId, input.position());
            after.put("position", input.position());
        }
        if (!after.isEmpty()) checklistChanged(user, access.task(), before.isEmpty() ? null : before, after, ipAddress);
    }

    public void deleteChecklistItem(CurrentUser user, UUID taskId, UUID itemId, String ipAddress) {
        AccessibleTask access = checklistAccess(user, taskId);
        Map<String, Object> item = checklistItem(taskId, itemId);
        repo.deleteChecklistItem(itemId);
        checklistChanged(user, access.task(), Map.of("removed", item.get("text")), null, ipAddress);
    }
}
