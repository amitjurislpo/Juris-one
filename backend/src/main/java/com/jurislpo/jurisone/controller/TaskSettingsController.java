package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.WorkflowStatusService;
import jakarta.servlet.http.HttpServletRequest;
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
 * Task settings (page {@code task-settings}, permission {@code tasks.manage}): the default workflow steps (for personal tasks and new
 * projects; existing projects keep their copy). Business labels are managed through {@code /api/task-labels}.
 */
@RestController
@RequestMapping("/api/v1/task-settings")
public class TaskSettingsController {

    private final Auth auth;
    private final WorkflowStatusService workflowStatuses;

    public TaskSettingsController(Auth auth, WorkflowStatusService workflowStatuses) {
        this.auth = auth;
        this.workflowStatuses = workflowStatuses;
    }

    private CurrentUser user() {
        return auth.requirePage("task-settings");
    }

    @GetMapping
    public ApiResult<Map<String, Object>> settings() {
        user();
        return ApiResult.success(workflowStatuses.settings());
    }

    @PostMapping("/workflow-statuses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addStatus(@RequestBody WorkflowStatusService.WorkflowStatusInput body, HttpServletRequest request) {
        return ApiResult.success("Step added", Map.of("id", workflowStatuses.create(user(), null, body, RequestMeta.of(request).ipAddress())));
    }

    @PutMapping("/workflow-statuses/{statusId}")
    public ApiResult<Void> updateStatus(@PathVariable String statusId, @RequestBody WorkflowStatusService.WorkflowStatusInput body, HttpServletRequest request) {
        workflowStatuses.update(user(), null, Check.uuid(statusId), body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Step saved");
    }

    @PutMapping("/workflow-status-order")
    public ApiResult<Void> orderStatuses(@RequestBody WorkflowStatusService.WorkflowStatusOrderInput body, HttpServletRequest request) {
        workflowStatuses.order(user(), null, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Steps reordered");
    }

    @DeleteMapping("/workflow-statuses/{statusId}")
    public ApiResult<Void> deleteStatus(@PathVariable String statusId, @RequestParam(name = "moveTasksToStatusId", required = false) String moveTasksToStatusId,
                                        HttpServletRequest request) {
        workflowStatuses.delete(user(), null, Check.uuid(statusId), moveTasksToStatusId, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Step deleted");
    }
}
