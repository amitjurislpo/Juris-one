package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.engine.TaskAccess;
import com.jurislpo.jurisone.engine.TaskKeys;
import com.jurislpo.jurisone.engine.WorkflowRules;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.ProjectRepository;
import com.jurislpo.jurisone.repository.ProjectRepository.ProjectFacts;
import com.jurislpo.jurisone.repository.TaskLabelRepository;
import com.jurislpo.jurisone.repository.WorkflowStatusRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task projects and their members (plan §4.2, §12.1 P1–P10). A private project is seen by its members only (404 for others); the
 * Owner ({@code tasks.manage}) sees every project and opening a private one is audited.
 */
@Service
@Transactional
public class ProjectService {

    static final List<String> SORTS = List.of("name", "key", "openTasks", "updatedAt");
    static final List<String> VISIBILITIES = List.of(TaskAccess.PRIVATE, TaskAccess.BUSINESS);
    static final List<String> ROLES = List.of(TaskAccess.OWNER, TaskAccess.MEMBER, TaskAccess.VIEWER);
    static final int PAGE_SIZE = 50, MAX_MEMBERS = 500;
    static final String NOT_FOUND = "Project not found.";
    static final String ARCHIVED = "This project is archived. Restore it to make changes.";

    public record ProjectInput(Integer version, String key, String name, String description, String visibility, String color, String departmentId) {}

    public record ProjectStatusInput(String status) {}

    public record ProjectMemberInput(String employeeId, String role) {}

    record ValidatedProject(String key, String name, String description, String visibility, String color, UUID departmentId) {}

    private final ProjectRepository repo;
    private final WorkflowStatusRepository statuses;
    private final TaskLabelRepository labels;
    private final NotificationService notifications;
    private final AuditService audit;

    public ProjectService(ProjectRepository repo, WorkflowStatusRepository statuses, TaskLabelRepository labels, NotificationService notifications, AuditService audit) {
        this.repo = repo;
        this.statuses = statuses;
        this.labels = labels;
        this.notifications = notifications;
        this.audit = audit;
    }

    static boolean isTaskAdmin(CurrentUser user) {
        return user.can(Permissions.TASKS_MANAGE);
    }

    // ───────────── access ─────────────

    /** The project as the user sees it; 404 when it does not exist, was deleted or is private to others. */
    public ProjectFacts visible(CurrentUser user, UUID projectId) {
        ProjectFacts project = repo.findFacts(projectId, user.id()).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, NOT_FOUND));
        if (TaskAccess.effectiveProjectRole(isTaskAdmin(user), project.visibility(), project.myRole()) == null) throw new UserException(HttpStatus.NOT_FOUND, NOT_FOUND);
        return project;
    }

    public static String role(CurrentUser user, ProjectFacts project) {
        return TaskAccess.effectiveProjectRole(isTaskAdmin(user), project.visibility(), project.myRole());
    }

    /** A project owner (or the Owner); 403 for someone who only sees it. */
    public ProjectFacts manageable(CurrentUser user, UUID projectId) {
        ProjectFacts project = visible(user, projectId);
        if (!TaskAccess.OWNER.equals(role(user, project))) throw new ForbiddenException("Only the project's owners can do that.");
        return project;
    }

    public static void requireActive(ProjectFacts project) {
        if (project.isArchived()) throw new UserException(ARCHIVED);
    }

    /** Adds the employee as a member unless they already are one (setting a direct assignee does this); audited. */
    public void ensureMember(UUID actorId, UUID projectId, UUID employeeId, String ipAddress) {
        if (repo.addMember(projectId, employeeId, TaskAccess.MEMBER, actorId)) {
            repo.touch(projectId);
            audit.log(actorId, "project.member_add", "project", projectId.toString(), null, Map.of("employeeId", employeeId.toString(), "role", TaskAccess.MEMBER,
                    "reason", "assignee"), ipAddress);
        }
    }

    // ───────────── projects ─────────────

    /** {@code {page, take, total, rows}} of the projects the user can see. */
    public Map<String, Object> list(CurrentUser user, Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, SORTS, "name", PAGE_SIZE, "asc");
        String status = ListParams.pick(queryParams, "status", List.of("ACTIVE", "ARCHIVED", "ALL"), "ACTIVE");
        String direction = listParams.asc() ? "ASC" : "DESC";
        String orderBy = switch (listParams.sort()) {
            case "key" -> "p.key " + direction;
            case "openTasks" -> "stats.open " + direction;
            case "updatedAt" -> "p.updated_at " + direction;
            default -> "lower(p.name) " + direction;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", listParams.page());
        out.put("take", listParams.take());
        out.put("total", repo.countRows(user.id(), isTaskAdmin(user), status, listParams.search()));
        out.put("rows", repo.findRows(user.id(), isTaskAdmin(user), status, listParams.search(), orderBy, listParams.take(), listParams.skip()));
        return out;
    }

    ValidatedProject validate(ProjectInput input) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Project details are missing.");
        String key = Check.requireString(input.key()).trim().toUpperCase(Locale.ROOT);
        if (!TaskKeys.isProjectKey(key)) throw new UserException(HttpStatus.BAD_REQUEST, "Use 2 to 6 letters or digits for the key, starting with a letter (for example HR or PAY).");
        String name = Check.trimmed(input.name(), 1, "Enter a project name.", 80, "Keep the project name under 80 characters.");
        String description = TaskInputs.optionalText(input.description(), 2000, "Keep the description under 2,000 characters.");
        String visibility = Check.oneOf(input.visibility() == null ? TaskAccess.PRIVATE : input.visibility(), VISIBILITIES);
        String color = TaskInputs.optionalColor(input.color());
        UUID departmentId = TaskInputs.optionalUuid(input.departmentId(), "Choose the department.");
        if (departmentId != null && !repo.departmentExists(departmentId)) throw new UserException(HttpStatus.BAD_REQUEST, "Choose the department.");
        return new ValidatedProject(key, name, description, visibility, color, departmentId);
    }

    public Map<String, String> create(CurrentUser user, ProjectInput input, String ipAddress) {
        if (!user.can(Permissions.PROJECTS_CREATE, Permissions.TASKS_MANAGE)) throw new ForbiddenException();
        ValidatedProject project = validate(input);
        if (repo.keyTaken(project.key(), null)) throw new UserException(HttpStatus.CONFLICT, "The key " + project.key() + " is already used by another project.");
        if (repo.nameTaken(project.name(), null)) throw new UserException(HttpStatus.CONFLICT, "A project with that name already exists.");
        UUID id = UUID.randomUUID();
        repo.insert(id, project.key(), project.name(), project.description(), project.visibility(), project.color(), project.departmentId(), user.id());
        repo.addMember(id, user.id(), TaskAccess.OWNER, user.id());
        statuses.insertDefaultSetIfMissing(WorkflowRules.DEFAULT_STATUSES);
        statuses.copyDefaultSet(id);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("key", project.key());
        after.put("name", project.name());
        after.put("visibility", project.visibility());
        audit.log(user.id(), "project.create", "project", id.toString(), null, after, ipAddress);
        return Map.of("id", id.toString(), "key", project.key());
    }

    /** ProjectDetail (plan §12.4); the Owner opening a private project they are not a member of is audited. */
    public Map<String, Object> detail(CurrentUser user, UUID projectId, String ipAddress) {
        ProjectFacts project = visible(user, projectId);
        if (isTaskAdmin(user) && project.myRole() == null && TaskAccess.PRIVATE.equals(project.visibility())) {
            audit.log(user.id(), "project.private_access", "project", projectId.toString(), null, null, ipAddress);
        }
        Map<String, Object> out = new LinkedHashMap<>(repo.findRowById(projectId, user.id()).get(0));
        String role = role(user, project);
        out.put("version", project.version());
        out.put("statuses", statuses.findSetWithCounts(projectId));
        out.put("labels", labels.findForProject(projectId));
        out.put("canManage", TaskAccess.OWNER.equals(role));
        out.put("canCreateTasks", !project.isArchived() && (TaskAccess.OWNER.equals(role) || TaskAccess.MEMBER.equals(role)));
        return out;
    }

    /** ProjectRow of one visible project. */
    public Map<String, Object> row(CurrentUser user, UUID projectId) {
        visible(user, projectId);
        return repo.findRowById(projectId, user.id()).get(0);
    }

    public Map<String, Object> update(CurrentUser user, UUID projectId, ProjectInput input, String ipAddress) {
        ProjectFacts project = manageable(user, projectId);
        requireActive(project);
        if (input == null || input.version() == null) throw new UserException(HttpStatus.BAD_REQUEST, "Send the version of the project you changed.");
        ValidatedProject changed = validate(input);
        Map<String, Object> before = repo.findEditable(projectId);
        if (!changed.key().equals(project.key())) {
            if (repo.countTasks(projectId, true) > 0) throw new UserException("The key can't change once the project has tasks.");
            if (repo.keyTaken(changed.key(), projectId)) throw new UserException(HttpStatus.CONFLICT, "The key " + changed.key() + " is already used by another project.");
        }
        if (repo.nameTaken(changed.name(), projectId)) throw new UserException(HttpStatus.CONFLICT, "A project with that name already exists.");
        Integer version = repo.update(projectId, input.version(), changed.key(), changed.name(), changed.description(), changed.visibility(), changed.color(),
                changed.departmentId());
        if (version == null) throw new UserException(HttpStatus.CONFLICT, "Someone else changed this project. Reload to see their changes.");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", version);
        after.put("key", changed.key());
        after.put("name", changed.name());
        after.put("description", changed.description());
        after.put("visibility", changed.visibility());
        after.put("color", changed.color());
        after.put("departmentId", changed.departmentId() == null ? null : changed.departmentId().toString());
        Map<String, Object> changedBefore = new LinkedHashMap<>(), changedAfter = new LinkedHashMap<>();
        for (String field : List.of("key", "name", "description", "visibility", "color", "departmentId")) {
            if (!Objects.equals(before.get(field), after.get(field))) {
                changedBefore.put(field, "description".equals(field) ? TaskActivityService.length((String) before.get(field)) : before.get(field));
                changedAfter.put(field, "description".equals(field) ? TaskActivityService.length((String) after.get(field)) : after.get(field));
            }
        }
        audit.log(user.id(), "project.update", "project", projectId.toString(), changedBefore, changedAfter, ipAddress);
        return Map.of("version", version);
    }

    public String setStatus(CurrentUser user, UUID projectId, ProjectStatusInput input, String ipAddress) {
        ProjectFacts project = manageable(user, projectId);
        String status = Check.oneOf(input == null ? null : input.status(), List.of("ACTIVE", "ARCHIVED"));
        if (status.equals(project.status())) return "ARCHIVED".equals(status) ? "Project archived" : "Project restored";
        repo.setStatus(projectId, status);
        boolean isArchive = "ARCHIVED".equals(status);
        audit.log(user.id(), isArchive ? "project.archive" : "project.restore", "project", projectId.toString(), Map.of("status", project.status()), Map.of("status", status),
                ipAddress);
        return isArchive ? "Project archived" : "Project restored";
    }

    /** Soft-deletes an empty project; a project with tasks is archived instead. */
    public void delete(CurrentUser user, UUID projectId, String ipAddress) {
        ProjectFacts project = manageable(user, projectId);
        if (repo.countTasks(projectId, false) > 0) throw new UserException("This project has tasks. Archive it instead.");
        repo.softDelete(projectId, user.id());
        audit.log(user.id(), "project.delete", "project", projectId.toString(), Map.of("key", project.key(), "name", project.name()), null, ipAddress);
    }

    // ───────────── members ─────────────

    public List<Map<String, Object>> members(CurrentUser user, UUID projectId) {
        visible(user, projectId);
        return repo.findMembers(projectId);
    }

    public void addMember(CurrentUser user, UUID projectId, ProjectMemberInput input, String ipAddress) {
        ProjectFacts project = manageable(user, projectId);
        requireActive(project);
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Member details are missing.");
        UUID employeeId = Check.uuid(input.employeeId(), "Choose the employee.");
        String role = Check.oneOf(input.role() == null ? TaskAccess.MEMBER : input.role(), ROLES);
        if (!repo.isActiveEmployee(employeeId)) throw new UserException(HttpStatus.NOT_FOUND, "Employee not found.");
        if (repo.findMemberRole(projectId, employeeId) != null) throw new UserException(HttpStatus.CONFLICT, "That person is already a member of this project.");
        if (repo.countMembers(projectId) >= MAX_MEMBERS) throw new UserException("A project can have at most " + MAX_MEMBERS + " members.");
        repo.addMember(projectId, employeeId, role, user.id());
        repo.touch(projectId);
        audit.log(user.id(), "project.member_add", "project", projectId.toString(), null, Map.of("employeeId", employeeId.toString(), "role", role), ipAddress);
        if (!employeeId.equals(user.id())) notifications.notify(employeeId, "You were added to the project " + project.name(), "/projects/" + projectId);
    }

    public void setMemberRole(CurrentUser user, UUID projectId, UUID employeeId, ProjectMemberInput input, String ipAddress) {
        ProjectFacts project = manageable(user, projectId);
        requireActive(project);
        String role = Check.oneOf(input == null ? null : input.role(), ROLES);
        String current = repo.findMemberRole(projectId, employeeId);
        if (current == null) throw new UserException(HttpStatus.NOT_FOUND, "That person is not a member of this project.");
        if (current.equals(role)) return;
        if (TaskAccess.OWNER.equals(current) && repo.countOwners(projectId) <= 1) throw new UserException("A project needs at least one owner.");
        repo.setMemberRole(projectId, employeeId, role);
        audit.log(user.id(), "project.member_role", "project", projectId.toString(), Map.of("employeeId", employeeId.toString(), "role", current),
                Map.of("employeeId", employeeId.toString(), "role", role), ipAddress);
    }

    /** A project owner removes a member, or a member leaves. */
    public void removeMember(CurrentUser user, UUID projectId, UUID employeeId, String ipAddress) {
        ProjectFacts project = employeeId.equals(user.id()) ? visible(user, projectId) : manageable(user, projectId);
        requireActive(project);
        String current = repo.findMemberRole(projectId, employeeId);
        if (current == null) throw new UserException(HttpStatus.NOT_FOUND, "That person is not a member of this project.");
        if (TaskAccess.OWNER.equals(current) && repo.countOwners(projectId) <= 1) throw new UserException("A project needs at least one owner.");
        repo.removeMember(projectId, employeeId);
        repo.touch(projectId);
        audit.log(user.id(), "project.member_remove", "project", projectId.toString(), Map.of("employeeId", employeeId.toString(), "role", current), null, ipAddress);
    }
}
