package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.ProjectService;
import com.jurislpo.jurisone.service.TaskQueryService;
import com.jurislpo.jurisone.service.WorkflowStatusService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task projects (page {@code projects}): the projects, their members, their workflow steps and their board. Creating a project needs
 * {@code projects.create} (or {@code tasks.manage}); everything else depends on the person's role in the project.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final Auth auth;
    private final ProjectService projects;
    private final WorkflowStatusService workflowStatuses;
    private final TaskQueryService taskQueries;

    public ProjectController(Auth auth, ProjectService projects, WorkflowStatusService workflowStatuses, TaskQueryService taskQueries) {
        this.auth = auth;
        this.projects = projects;
        this.workflowStatuses = workflowStatuses;
        this.taskQueries = taskQueries;
    }

    private CurrentUser user() {
        return auth.requirePage("projects");
    }

    private static String ipAddress(HttpServletRequest request) {
        return RequestMeta.of(request).ipAddress();
    }

    /** {@code ?status=ACTIVE|ARCHIVED|ALL&search=&sort=name|key|openTasks|updatedAt&sortDirection=&page=} */
    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(projects.list(user(), queryParams));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody ProjectService.ProjectInput body, HttpServletRequest request) {
        return ApiResult.success("Project created", projects.create(user(), body, ipAddress(request)));
    }

    @GetMapping("/{projectId}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String projectId, HttpServletRequest request) {
        return ApiResult.success(projects.detail(user(), Check.uuid(projectId), ipAddress(request)));
    }

    @PutMapping("/{projectId}")
    public ApiResult<Map<String, Object>> update(@PathVariable String projectId, @RequestBody ProjectService.ProjectInput body, HttpServletRequest request) {
        return ApiResult.success("Project saved", projects.update(user(), Check.uuid(projectId), body, ipAddress(request)));
    }

    /** {@code {"status": "ACTIVE" | "ARCHIVED"}} */
    @PutMapping("/{projectId}/status")
    public ApiResult<Void> setStatus(@PathVariable String projectId, @RequestBody ProjectService.ProjectStatusInput body, HttpServletRequest request) {
        return ApiResult.message(projects.setStatus(user(), Check.uuid(projectId), body, ipAddress(request)));
    }

    @DeleteMapping("/{projectId}")
    public ApiResult<Void> delete(@PathVariable String projectId, HttpServletRequest request) {
        projects.delete(user(), Check.uuid(projectId), ipAddress(request));
        return ApiResult.message("Project deleted");
    }

    // ───────────── members ─────────────

    @GetMapping("/{projectId}/members")
    public ApiResult<List<Map<String, Object>>> members(@PathVariable String projectId) {
        return ApiResult.success(projects.members(user(), Check.uuid(projectId)));
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> addMember(@PathVariable String projectId, @RequestBody ProjectService.ProjectMemberInput body, HttpServletRequest request) {
        projects.addMember(user(), Check.uuid(projectId), body, ipAddress(request));
        return ApiResult.message("Member added");
    }

    @PutMapping("/{projectId}/members/{employeeId}")
    public ApiResult<Void> setMemberRole(@PathVariable String projectId, @PathVariable String employeeId, @RequestBody ProjectService.ProjectMemberInput body,
                                         HttpServletRequest request) {
        projects.setMemberRole(user(), Check.uuid(projectId), Check.uuid(employeeId), body, ipAddress(request));
        return ApiResult.message("Role changed");
    }

    @DeleteMapping("/{projectId}/members/{employeeId}")
    public ApiResult<Void> removeMember(@PathVariable String projectId, @PathVariable String employeeId, HttpServletRequest request) {
        projects.removeMember(user(), Check.uuid(projectId), Check.uuid(employeeId), ipAddress(request));
        return ApiResult.message("Member removed");
    }

    // ───────────── workflow steps ─────────────

    @PostMapping("/{projectId}/workflow-statuses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addStatus(@PathVariable String projectId, @RequestBody WorkflowStatusService.WorkflowStatusInput body,
                                                    HttpServletRequest request) {
        return ApiResult.success("Step added", Map.of("id", workflowStatuses.create(user(), Check.uuid(projectId), body, ipAddress(request))));
    }

    @PutMapping("/{projectId}/workflow-statuses/{statusId}")
    public ApiResult<Void> updateStatus(@PathVariable String projectId, @PathVariable String statusId, @RequestBody WorkflowStatusService.WorkflowStatusInput body,
                                        HttpServletRequest request) {
        workflowStatuses.update(user(), Check.uuid(projectId), Check.uuid(statusId), body, ipAddress(request));
        return ApiResult.message("Step saved");
    }

    /** {@code {"statusIds": [...]}}: every step of the project, in the new order. */
    @PutMapping("/{projectId}/workflow-status-order")
    public ApiResult<Void> orderStatuses(@PathVariable String projectId, @RequestBody WorkflowStatusService.WorkflowStatusOrderInput body, HttpServletRequest request) {
        workflowStatuses.order(user(), Check.uuid(projectId), body, ipAddress(request));
        return ApiResult.message("Steps reordered");
    }

    @DeleteMapping("/{projectId}/workflow-statuses/{statusId}")
    public ApiResult<Void> deleteStatus(@PathVariable String projectId, @PathVariable String statusId,
                                        @RequestParam(name = "moveTasksToStatusId", required = false) String moveTasksToStatusId, HttpServletRequest request) {
        workflowStatuses.delete(user(), Check.uuid(projectId), Check.uuid(statusId), moveTasksToStatusId, ipAddress(request));
        return ApiResult.message("Step deleted");
    }

    // ───────────── board ─────────────

    /** {@code ?assigneeId=&indirectAssigneeId=&labelId=&priority=&type=&search=&doneWithinDays=} */
    @GetMapping("/{projectId}/board")
    public ApiResult<Map<String, Object>> board(@PathVariable String projectId, @RequestParam Map<String, String> queryParams) {
        return ApiResult.success(taskQueries.board(user(), Check.uuid(projectId), queryParams));
    }
}
