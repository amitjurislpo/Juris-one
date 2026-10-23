package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ProjectRepository.ProjectFacts;
import com.jurislpo.jurisone.repository.TaskLabelRepository;
import com.jurislpo.jurisone.repository.TaskLabelRepository.LabelRow;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Task labels: business-wide ones are kept in Task settings ({@code tasks.manage}), a project's by its owners. */
@Service
@Transactional
public class TaskLabelService {

    static final int MAX_LABELS_PER_SET = 50;

    public record LabelInput(String name, String color, String projectId) {}

    private final TaskLabelRepository repo;
    private final ProjectService projects;
    private final AuditService audit;

    public TaskLabelService(TaskLabelRepository repo, ProjectService projects, AuditService audit) {
        this.repo = repo;
        this.projects = projects;
        this.audit = audit;
    }

    /** Business labels, plus the project's when {@code projectId} is given (the project must be visible). */
    public List<Map<String, Object>> list(CurrentUser user, String projectIdRaw) {
        UUID projectId = TaskInputs.optionalUuid(projectIdRaw, "Invalid UUID");
        if (projectId == null) return repo.findBusinessLabels();
        projects.visible(user, projectId);
        return repo.findForProject(projectId);
    }

    /** Business labels need {@code tasks.manage}; a project's labels need a project owner of an active project. */
    private void requireManage(CurrentUser user, UUID projectId) {
        if (projectId == null) {
            if (!user.can(Permissions.TASKS_MANAGE)) throw new ForbiddenException();
            return;
        }
        ProjectFacts project = projects.manageable(user, projectId);
        ProjectService.requireActive(project);
    }

    private LabelRow label(CurrentUser user, UUID labelId) {
        LabelRow label = repo.findById(labelId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Label not found."));
        if (label.projectId() != null) {
            try {
                projects.visible(user, label.projectId());
            } catch (UserException e) {
                throw new UserException(HttpStatus.NOT_FOUND, "Label not found.");
            }
        }
        return label;
    }

    private static String name(String value) {
        return Check.trimmed(value, 1, "Enter a label name.", 40, "Keep the label name under 40 characters.");
    }

    private static Map<String, Object> describe(UUID projectId, String name, String color) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId == null ? null : projectId.toString());
        out.put("name", name);
        out.put("color", color);
        return out;
    }

    public String create(CurrentUser user, LabelInput input, String ipAddress) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Label details are missing.");
        UUID projectId = TaskInputs.optionalUuid(input.projectId(), "Choose the project.");
        requireManage(user, projectId);
        String name = name(input.name());
        String color = TaskInputs.optionalColor(input.color());
        if (repo.nameTaken(projectId, name, null)) throw new UserException(HttpStatus.CONFLICT, "A label with that name already exists.");
        if (repo.count(projectId) >= MAX_LABELS_PER_SET) throw new UserException("There can be at most " + MAX_LABELS_PER_SET + " labels here.");
        UUID id = UUID.randomUUID();
        repo.insert(id, projectId, name, color);
        audit.log(user.id(), "task_label.create", "task_label", id.toString(), null, describe(projectId, name, color), ipAddress);
        return id.toString();
    }

    public void update(CurrentUser user, UUID labelId, LabelInput input, String ipAddress) {
        LabelRow label = label(user, labelId);
        requireManage(user, label.projectId());
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Label details are missing.");
        String name = name(input.name());
        String color = TaskInputs.optionalColor(input.color());
        if (repo.nameTaken(label.projectId(), name, labelId)) throw new UserException(HttpStatus.CONFLICT, "A label with that name already exists.");
        repo.update(labelId, name, color);
        audit.log(user.id(), "task_label.update", "task_label", labelId.toString(), describe(label.projectId(), label.name(), label.color()),
                describe(label.projectId(), name, color), ipAddress);
    }

    public void delete(CurrentUser user, UUID labelId, String ipAddress) {
        LabelRow label = label(user, labelId);
        requireManage(user, label.projectId());
        List<UUID> taskIds = repo.delete(labelId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("removedFromTasks", taskIds.size());
        audit.log(user.id(), "task_label.delete", "task_label", labelId.toString(), describe(label.projectId(), label.name(), label.color()), after, ipAddress);
    }
}
