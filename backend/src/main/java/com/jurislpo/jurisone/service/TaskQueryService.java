package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Mentions;
import com.jurislpo.jurisone.engine.TaskAccess;
import com.jurislpo.jurisone.engine.TaskAccess.TaskPermissions;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.repository.ProjectRepository.ProjectFacts;
import com.jurislpo.jurisone.repository.TaskAttachmentRepository;
import com.jurislpo.jurisone.repository.TaskListRepository;
import com.jurislpo.jurisone.repository.TaskQuery;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.TaskFacts;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository.StatusRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task lists, the board, the people picker and the task panel (plan §12.1 T1–T4, T6, P15; §13). */
@Service
@Transactional
public class TaskQueryService {

    static final int BOARD_OPEN_LIMIT = 500, BOARD_DONE_LIMIT = 500;

    private final TaskListRepository lists;
    private final TaskRepository tasks;
    private final WorkflowStatusRepository statuses;
    private final TaskAttachmentRepository attachments;
    private final TaskService taskService;
    private final TaskActivityService activity;
    private final ProjectService projects;
    private final AuditService audit;

    public TaskQueryService(TaskListRepository lists, TaskRepository tasks, WorkflowStatusRepository statuses, TaskAttachmentRepository attachments,
                            TaskService taskService, TaskActivityService activity, ProjectService projects, AuditService audit) {
        this.lists = lists;
        this.tasks = tasks;
        this.statuses = statuses;
        this.attachments = attachments;
        this.taskService = taskService;
        this.activity = activity;
        this.projects = projects;
        this.audit = audit;
    }

    /** Adds {@code labels: [{id, name, color}]} to every TaskRow (one query for the page). */
    List<Map<String, Object>> withLabels(List<Map<String, Object>> rows) {
        List<UUID> ids = rows.stream().map(r -> UUID.fromString((String) r.get("id"))).toList();
        Map<String, List<Map<String, Object>>> byTask = new HashMap<>();
        for (Map<String, Object> label : tasks.findLabelsOfTasks(ids)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", label.get("id"));
            view.put("name", label.get("name"));
            view.put("color", label.get("color"));
            byTask.computeIfAbsent((String) label.get("taskId"), k -> new ArrayList<>()).add(view);
        }
        for (Map<String, Object> row : rows) row.put("labels", byTask.getOrDefault((String) row.get("id"), List.of()));
        return rows;
    }

    private Map<String, Object> page(TaskQuery query) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", query.listParams.page());
        out.put("take", query.listParams.take());
        out.put("total", lists.count(query));
        out.put("rows", withLabels(lists.findRows(query)));
        return out;
    }

    /** {@code GET /api/tasks}: every task the user can see (the Owner: all), filtered. */
    public Map<String, Object> list(CurrentUser user, Map<String, String> queryParams) {
        return page(new TaskQuery(queryParams, user.id(), ProjectService.isTaskAdmin(user), TaskQuery.Scope.VISIBLE, 2, true));
    }

    /** {@code GET /api/tasks/mine}: my tasks by relation, with the due bucket counts (open tasks by default). */
    public Map<String, Object> mine(CurrentUser user, Map<String, String> queryParams) {
        Map<String, Object> out = page(new TaskQuery(queryParams, user.id(), ProjectService.isTaskAdmin(user), TaskQuery.Scope.MINE, 2, true));
        out.put("counts", lists.bucketCounts(new TaskQuery(queryParams, user.id(), ProjectService.isTaskAdmin(user), TaskQuery.Scope.MINE, 2, false)));
        return out;
    }

    /** {@code GET /api/tasks/team}: the tasks of my reports (direct, or {@code depth=2} for the next level too), with a per-person summary. */
    public Map<String, Object> team(CurrentUser user, Map<String, String> queryParams) {
        int depth = "2".equals(ListParams.text(queryParams, "depth", "1")) ? 2 : 1;
        Map<String, String> params = new HashMap<>(queryParams);
        String employeeId = ListParams.text(queryParams, "employeeId", "");
        if (!employeeId.isEmpty()) {
            boolean isMember = employeeId.matches("^[0-9a-fA-F-]{36}$") && lists.isInTeam(user.id(), UUID.fromString(employeeId), depth);
            params.put("assigneeId", isMember ? employeeId : UUID.randomUUID().toString()); // someone outside the team: nothing
        }
        Map<String, Object> out = page(new TaskQuery(params, user.id(), ProjectService.isTaskAdmin(user), TaskQuery.Scope.TEAM, depth, true));
        out.put("people", lists.findTeamPeople(user.id(), depth));
        return out;
    }

    /** {@code GET /api/tasks/assignees}: active employees matching {@code search} (at most 20), with the reporting manager for the suggestion. */
    public List<Map<String, Object>> assignees(CurrentUser user, Map<String, String> queryParams) {
        UUID projectId = TaskInputs.optionalUuid(ListParams.text(queryParams, "projectId", ""), "Invalid UUID");
        if (projectId != null) projects.visible(user, projectId);
        String search = ListParams.text(queryParams, "search", "").trim();
        return lists.findAssignees(projectId, search.isEmpty() ? null : PeopleQuery.like(search));
    }

    /** One TaskRow, read without the list's visibility rule (the caller checked the access). */
    Map<String, Object> row(CurrentUser user, UUID taskId) {
        TaskQuery query = new TaskQuery(Map.of(), user.id(), true, TaskQuery.Scope.PROJECT, 2, false).restrictToTask(taskId);
        List<Map<String, Object>> rows = withLabels(lists.findRows(query));
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, TaskService.NOT_FOUND);
        return rows.get(0);
    }

    static Map<String, Object> permissionsView(TaskPermissions permissions) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canEdit", permissions.canEdit());
        out.put("canComment", permissions.canComment());
        out.put("canChangeStatus", permissions.canChangeStatus());
        out.put("canChangePriority", permissions.canChangePriority());
        out.put("canChangeDirectAssignee", permissions.canChangeDirectAssignee());
        out.put("canChangeIndirectAssignee", permissions.canChangeIndirectAssignee());
        out.put("canDelete", permissions.canDelete());
        out.put("canManageProject", permissions.canManageProject());
        return out;
    }

    /** {@code {id: name}} of the people mentioned in a text. */
    Map<String, Object> mentionedPeople(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> person : tasks.findNames(new ArrayList<>(Mentions.find(text)))) out.put((String) person.get("id"), person.get("name"));
        return out;
    }

    /** TaskDetail (plan §12.4); the Owner opening a private task they are not part of is audited. */
    public Map<String, Object> detail(CurrentUser user, String taskRef, String ipAddress) {
        UUID taskId = taskService.resolve(taskRef);
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        TaskFacts task = access.task();
        if (TaskAccess.isPrivateAccess(access.facts())) audit.log(user.id(), "task.private_access", "task", taskId.toString(), null, null, ipAddress);
        Map<String, Object> out = new LinkedHashMap<>(row(user, taskId));
        out.putAll(tasks.findDetailExtras(taskId));
        List<Map<String, Object>> watchers = tasks.findWatchers(taskId);
        out.put("watchers", watchers);
        out.put("isWatching", watchers.stream().anyMatch(w -> user.id().toString().equals(w.get("employeeId"))));
        Map<String, String> subtaskParams = new HashMap<>();
        subtaskParams.put("parentTaskId", taskId.toString());
        subtaskParams.put("sort", "createdAt");
        subtaskParams.put("pageSize", "100");
        subtaskParams.put("isArchivedProjectIncluded", "true");
        out.put("subtasks", withLabels(lists.findRows(new TaskQuery(subtaskParams, user.id(), ProjectService.isTaskAdmin(user), TaskQuery.Scope.VISIBLE, 2, true))));
        out.put("checklist", tasks.findChecklist(taskId));
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map<String, Object> attachment : attachments.findForTask(taskId)) {
            Map<String, Object> view = new LinkedHashMap<>(attachment);
            Object uploadedById = view.remove("uploadedById");
            view.put("canDelete", TaskAttachmentService.canDelete(user, access, uploadedById == null ? null : UUID.fromString((String) uploadedById)));
            files.add(view);
        }
        out.put("attachments", files);
        out.put("mentionedPeople", mentionedPeople(task.description()));
        out.put("statuses", statuses.findSetWithCounts(task.projectId()));
        out.put("permissions", permissionsView(access.permissions()));
        return out;
    }

    /** The History tab: {@code {page, take, total, rows}}, 50 per page, newest first. */
    public Map<String, Object> activity(CurrentUser user, UUID taskId, String pageRaw) {
        taskService.access(user, taskId);
        return activity.page(taskId, TaskInputs.page(pageRaw));
    }

    /** {@code GET /api/projects/{projectId}/board}: open cards (at most 500) and the cards done in the last {@code doneWithinDays} days, by step. */
    public Map<String, Object> board(CurrentUser user, UUID projectId, Map<String, String> queryParams) {
        ProjectFacts project = projects.visible(user, projectId);
        int doneWithinDays;
        try {
            doneWithinDays = Math.max(1, Math.min(90, Integer.parseInt(ListParams.text(queryParams, "doneWithinDays", "14").trim())));
        } catch (NumberFormatException e) {
            doneWithinDays = 14;
        }
        Map<String, String> openParams = new HashMap<>(queryParams);
        openParams.remove("statusId");
        openParams.remove("parentTaskId");
        openParams.put("projectId", projectId.toString());
        openParams.put("statusCategory", "TODO,IN_PROGRESS");
        Map<String, String> doneParams = new HashMap<>(openParams);
        doneParams.put("statusCategory", "DONE");
        TaskQuery open = new TaskQuery(openParams, user.id(), true, TaskQuery.Scope.PROJECT, 2, true);
        TaskQuery done = new TaskQuery(doneParams, user.id(), true, TaskQuery.Scope.PROJECT, 2, true).restrictToCompleted(doneWithinDays, true);
        List<Map<String, Object>> cards = new ArrayList<>(withLabels(lists.findBoard(open, BOARD_OPEN_LIMIT)));
        cards.addAll(withLabels(lists.findBoard(done, BOARD_DONE_LIMIT)));
        Map<UUID, Long> counts = new HashMap<>(lists.countByStatus(open));
        counts.putAll(lists.countByStatus(done));
        boolean hasMoreDone = lists.count(new TaskQuery(doneParams, user.id(), true, TaskQuery.Scope.PROJECT, 2, true).restrictToCompleted(doneWithinDays, false)) > 0;

        List<Map<String, Object>> columns = new ArrayList<>();
        for (StatusRow status : statuses.findSet(projectId)) {
            Map<String, Object> statusView = new LinkedHashMap<>();
            statusView.put("id", status.id().toString());
            statusView.put("name", status.name());
            statusView.put("category", status.category());
            statusView.put("color", status.color());
            statusView.put("position", status.position());
            statusView.put("isInitial", status.isInitial());
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("status", statusView);
            column.put("taskCount", counts.getOrDefault(status.id(), 0L));
            column.put("tasks", cards.stream().filter(card -> status.id().toString().equals(card.get("statusId"))).toList());
            columns.add(column);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", projects.row(user, project.id()));
        out.put("columns", columns);
        out.put("hasMoreDone", hasMoreDone);
        return out;
    }
}
