package com.jurislpo.jurisone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaskQueryService;
import com.jurislpo.jurisone.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tasks (page {@code my-tasks}; Team tasks: page {@code team-tasks}). Using tasks needs no permission: what a person may do with a
 * task follows from their role in its project and their part in the task (assignee, accountable, reporter, follower, manager).
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final Auth auth;
    private final TaskService tasks;
    private final TaskQueryService taskQueries;

    public TaskController(Auth auth, TaskService tasks, TaskQueryService taskQueries) {
        this.auth = auth;
        this.tasks = tasks;
        this.taskQueries = taskQueries;
    }

    private CurrentUser user() {
        return auth.requirePage("my-tasks");
    }

    private static String ipAddress(HttpServletRequest request) {
        return RequestMeta.of(request).ipAddress();
    }

    /** Every task the user can see, with the task filters (plan §13.1). */
    @GetMapping
    public ApiResult<Map<String, Object>> list(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(taskQueries.list(user(), queryParams));
    }

    /** {@code ?relation=ASSIGNED|DIRECT|INDIRECT|REPORTED|WATCHING} plus the task filters; open tasks by default. */
    @GetMapping("/mine")
    public ApiResult<Map<String, Object>> mine(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(taskQueries.mine(user(), queryParams));
    }

    /** {@code ?depth=1|2&employeeId=} plus the task filters: the tasks of my reports. */
    @GetMapping("/team")
    public ApiResult<Map<String, Object>> team(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(taskQueries.team(auth.requirePage("team-tasks"), queryParams));
    }

    /** {@code ?projectId=&search=}: people who can be chosen as assignees (at most 20). */
    @GetMapping("/assignees")
    public ApiResult<List<Map<String, Object>>> assignees(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(taskQueries.assignees(user(), queryParams));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody TaskService.TaskInput body, HttpServletRequest request) {
        return ApiResult.success("Task created", tasks.create(user(), body, ipAddress(request)));
    }

    /** {@code taskRef} is the task's id or its key ({@code HR-12}). */
    @GetMapping("/{taskRef}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String taskRef, HttpServletRequest request) {
        return ApiResult.success(taskQueries.detail(user(), taskRef, ipAddress(request)));
    }

    /** Only the fields sent change ({@code null} clears one); {@code version} is required. */
    @PatchMapping("/{taskId}")
    public ApiResult<Map<String, Object>> patch(@PathVariable String taskId, @RequestBody JsonNode body, HttpServletRequest request) {
        return ApiResult.success("Task saved", tasks.patch(user(), Check.uuid(taskId), body, ipAddress(request)));
    }

    /** A board drop: {@code {statusId, previousTaskId?, nextTaskId?}}. */
    @PutMapping("/{taskId}/position")
    public ApiResult<Map<String, Object>> move(@PathVariable String taskId, @RequestBody TaskService.TaskPositionInput body, HttpServletRequest request) {
        return ApiResult.success(tasks.move(user(), Check.uuid(taskId), body, ipAddress(request)));
    }

    @DeleteMapping("/{taskId}")
    public ApiResult<Void> delete(@PathVariable String taskId, HttpServletRequest request) {
        tasks.delete(user(), Check.uuid(taskId), ipAddress(request));
        return ApiResult.message("Task deleted");
    }

    // ───────────── followers ─────────────

    @PutMapping("/{taskId}/watchers/{employeeId}")
    public ApiResult<Void> follow(@PathVariable String taskId, @PathVariable String employeeId, HttpServletRequest request) {
        tasks.follow(user(), Check.uuid(taskId), Check.uuid(employeeId), ipAddress(request));
        return ApiResult.message("Follower added");
    }

    @DeleteMapping("/{taskId}/watchers/{employeeId}")
    public ApiResult<Void> unfollow(@PathVariable String taskId, @PathVariable String employeeId, HttpServletRequest request) {
        tasks.unfollow(user(), Check.uuid(taskId), Check.uuid(employeeId), ipAddress(request));
        return ApiResult.message("Follower removed");
    }

    // ───────────── checklist ─────────────

    @PostMapping("/{taskId}/checklist-items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addChecklistItem(@PathVariable String taskId, @RequestBody TaskService.ChecklistItemInput body, HttpServletRequest request) {
        return ApiResult.success("Checklist item added", Map.of("id", tasks.addChecklistItem(user(), Check.uuid(taskId), body, ipAddress(request))));
    }

    /** {@code {text?, isDone?, position?}} ({@code position} from 1). */
    @PatchMapping("/{taskId}/checklist-items/{itemId}")
    public ApiResult<Void> changeChecklistItem(@PathVariable String taskId, @PathVariable String itemId, @RequestBody TaskService.ChecklistItemInput body,
                                               HttpServletRequest request) {
        tasks.changeChecklistItem(user(), Check.uuid(taskId), Check.uuid(itemId), body, ipAddress(request));
        return ApiResult.message("Checklist item saved");
    }

    @DeleteMapping("/{taskId}/checklist-items/{itemId}")
    public ApiResult<Void> deleteChecklistItem(@PathVariable String taskId, @PathVariable String itemId, HttpServletRequest request) {
        tasks.deleteChecklistItem(user(), Check.uuid(taskId), Check.uuid(itemId), ipAddress(request));
        return ApiResult.message("Checklist item removed");
    }
}
