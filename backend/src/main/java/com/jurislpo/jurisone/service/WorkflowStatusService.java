package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.WorkflowRules;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ProjectRepository.ProjectFacts;
import com.jurislpo.jurisone.repository.TaskLabelRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository.StatusRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workflow steps (plan §8.4): each project has its own set, copied from the business default set (Task settings) when it is created.
 * Changing the default set affects personal tasks and new projects only.
 */
@Service
@Transactional
public class WorkflowStatusService {

    public record WorkflowStatusInput(String name, String category, String color, Boolean isInitial) {}

    public record WorkflowStatusOrderInput(List<String> statusIds) {}

    private final WorkflowStatusRepository repo;
    private final ProjectService projects;
    private final TaskLabelRepository labels;
    private final AuditService audit;

    public WorkflowStatusService(WorkflowStatusRepository repo, ProjectService projects, TaskLabelRepository labels, AuditService audit) {
        this.repo = repo;
        this.projects = projects;
        this.labels = labels;
        this.audit = audit;
    }

    /** Creates the business default set on the first write that needs it (no GET writes it). */
    public void ensureDefaultStatuses() {
        repo.insertDefaultSetIfMissing(WorkflowRules.DEFAULT_STATUSES);
    }

    /**
     * Task settings: the default steps (built-in ones with {@code id: null} until the business's first task write), the business
     * labels and how many projects and tasks there are.
     */
    public Map<String, Object> settings() {
        List<Map<String, Object>> defaults = repo.findSetWithCounts(null);
        if (defaults.isEmpty()) {
            defaults = new ArrayList<>();
            for (int i = 0; i < WorkflowRules.DEFAULT_STATUSES.size(); i++) {
                WorkflowRules.Status status = WorkflowRules.DEFAULT_STATUSES.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", null);
                row.put("name", status.name());
                row.put("category", status.category());
                row.put("color", status.color());
                row.put("position", i + 1);
                row.put("isInitial", status.isInitial());
                row.put("taskCount", 0);
                defaults.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultStatuses", defaults);
        out.put("labels", labels.findBusinessLabels());
        out.put("projectCount", repo.countProjects());
        out.put("taskCount", repo.countAllTasks());
        return out;
    }

    /** The set a write works on: a project the user owns (active), or the default set (the caller checked {@code tasks.manage}). */
    private UUID setOf(CurrentUser user, UUID projectId) {
        if (projectId == null) {
            ensureDefaultStatuses();
            return null;
        }
        ProjectFacts project = projects.manageable(user, projectId);
        ProjectService.requireActive(project);
        return projectId;
    }

    private static StatusRow inSet(List<StatusRow> set, UUID statusId) {
        return set.stream().filter(s -> s.id().equals(statusId)).findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Step not found."));
    }

    private static String name(String value) {
        return Check.trimmed(value, 1, "Enter a name for the step.", 30, "Keep the step name under 30 characters.");
    }

    private static void requireUniqueName(List<StatusRow> set, String name, UUID exceptId) {
        if (set.stream().anyMatch(s -> !s.id().equals(exceptId) && s.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))) {
            throw new UserException(HttpStatus.CONFLICT, "A step with that name already exists.");
        }
    }

    private static void requireValid(List<WorkflowRules.Status> statuses) {
        String problem = WorkflowRules.problem(statuses);
        if (problem != null) throw new UserException(problem);
    }

    private static WorkflowRules.Status shape(StatusRow row) {
        return new WorkflowRules.Status(row.name(), row.category(), row.color(), row.isInitial());
    }

    private static Map<String, Object> describe(UUID projectId, String name, String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId == null ? null : projectId.toString());
        out.put("name", name);
        out.put("category", category);
        return out;
    }

    public String create(CurrentUser user, UUID projectIdOrDefault, WorkflowStatusInput input, String ipAddress) {
        UUID projectId = setOf(user, projectIdOrDefault);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Step details are missing.");
        String name = name(input.name());
        String category = Check.oneOf(input.category(), WorkflowRules.CATEGORIES);
        String color = TaskInputs.optionalColor(input.color());
        List<StatusRow> set = repo.findSet(projectId);
        if (set.size() >= WorkflowRules.MAX_STATUSES) throw new UserException("A workflow can have at most " + WorkflowRules.MAX_STATUSES + " steps.");
        requireUniqueName(set, name, null);
        UUID id = UUID.randomUUID();
        int position = set.stream().mapToInt(StatusRow::position).max().orElse(0) + 1;
        repo.insert(id, projectId, name, category, color, position, false);
        audit.log(user.id(), "workflow_status.create", "workflow_status", id.toString(), null, describe(projectId, name, category), ipAddress);
        return id.toString();
    }

    public void update(CurrentUser user, UUID projectIdOrDefault, UUID statusId, WorkflowStatusInput input, String ipAddress) {
        UUID projectId = setOf(user, projectIdOrDefault);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Step details are missing.");
        List<StatusRow> set = repo.findSet(projectId);
        StatusRow current = inSet(set, statusId);
        String name = name(input.name());
        String category = Check.oneOf(input.category(), WorkflowRules.CATEGORIES);
        String color = TaskInputs.optionalColor(input.color());
        boolean isInitial = input.isInitial() == null ? current.isInitial() : input.isInitial();
        requireUniqueName(set, name, statusId);
        List<WorkflowRules.Status> after = new ArrayList<>();
        for (StatusRow row : set) {
            if (row.id().equals(statusId)) after.add(new WorkflowRules.Status(name, category, color, isInitial));
            else after.add(new WorkflowRules.Status(row.name(), row.category(), row.color(), row.isInitial() && !isInitial));
        }
        requireValid(after);
        if (current.isInitial() && !isInitial) throw new UserException("Make another step the starting step first.");
        repo.update(statusId, name, category, color);
        if (isInitial && !current.isInitial()) repo.makeInitial(projectId, statusId);
        boolean wasDone = WorkflowRules.DONE.equals(current.category()), isDone = WorkflowRules.DONE.equals(category);
        if (wasDone != isDone) repo.syncCompletion(statusId, isDone, user.id());
        Map<String, Object> before = describe(projectId, current.name(), current.category());
        before.put("color", current.color());
        before.put("isInitial", current.isInitial());
        Map<String, Object> changed = describe(projectId, name, category);
        changed.put("color", color);
        changed.put("isInitial", isInitial);
        audit.log(user.id(), "workflow_status.update", "workflow_status", statusId.toString(), before, changed, ipAddress);
    }

    public void order(CurrentUser user, UUID projectIdOrDefault, WorkflowStatusOrderInput input, String ipAddress) {
        UUID projectId = setOf(user, projectIdOrDefault);
        List<UUID> ids = TaskInputs.uuids(input == null ? null : input.statusIds(), "Invalid UUID");
        List<StatusRow> set = repo.findSet(projectId);
        boolean isSameSet = ids.size() == set.size() && new HashSet<>(ids).equals(new HashSet<>(set.stream().map(StatusRow::id).toList()))
                && input.statusIds().size() == ids.size();
        if (!isSameSet) throw new UserException(HttpStatus.BAD_REQUEST, "List every step of the workflow once.");
        for (int i = 0; i < ids.size(); i++) repo.setPosition(ids.get(i), i + 1);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("projectId", projectId == null ? null : projectId.toString());
        after.put("statusIds", ids.stream().map(UUID::toString).toList());
        audit.log(user.id(), "workflow_status.order", "workflow_status", projectId == null ? "default" : projectId.toString(), null, after, ipAddress);
    }

    /** Deletes a step; its tasks move to {@code moveTasksToStatusId} (required when it has tasks). */
    public void delete(CurrentUser user, UUID projectIdOrDefault, UUID statusId, String moveTasksToStatusIdRaw, String ipAddress) {
        UUID projectId = setOf(user, projectIdOrDefault);
        List<StatusRow> set = repo.findSet(projectId);
        StatusRow current = inSet(set, statusId);
        if (current.isInitial()) throw new UserException("Make another step the starting step first.");
        requireValid(set.stream().filter(s -> !s.id().equals(statusId)).map(WorkflowStatusService::shape).toList());
        UUID targetId = TaskInputs.optionalUuid(moveTasksToStatusIdRaw, "Invalid UUID");
        StatusRow target = null;
        if (targetId != null) {
            if (targetId.equals(statusId) || set.stream().noneMatch(s -> s.id().equals(targetId))) {
                throw new UserException(HttpStatus.BAD_REQUEST, "Choose another step of the same workflow for its tasks.");
            }
            target = inSet(set, targetId);
        }
        int openTasks = repo.countTasks(statusId, false);
        if (openTasks > 0 && target == null) throw new UserException("This step has tasks. Choose the step to move them to.");
        if (target == null) target = set.stream().filter(StatusRow::isInitial).findFirst().orElseThrow();
        int moved = repo.moveTasks(statusId, target.id(), WorkflowRules.DONE.equals(target.category()), user.id());
        repo.delete(statusId);
        Map<String, Object> before = describe(projectId, current.name(), current.category());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("movedTasks", moved);
        after.put("movedToStatusId", target.id().toString());
        audit.log(user.id(), "workflow_status.delete", "workflow_status", statusId.toString(), before, after, ipAddress);
    }
}
