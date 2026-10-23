package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaskLabelService;
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

/** Task labels (page {@code projects}): business labels need {@code tasks.manage}, a project's labels a project owner. */
@RestController
@RequestMapping("/api/task-labels")
public class TaskLabelController {

    private final Auth auth;
    private final TaskLabelService labels;

    public TaskLabelController(Auth auth, TaskLabelService labels) {
        this.auth = auth;
        this.labels = labels;
    }

    private CurrentUser user() {
        return auth.requirePage("projects");
    }

    /** {@code ?projectId=}: the business labels plus that project's. */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(name = "projectId", required = false) String projectId) {
        return ApiResult.success(labels.list(user(), projectId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody TaskLabelService.LabelInput body, HttpServletRequest request) {
        return ApiResult.success("Label added", Map.of("id", labels.create(user(), body, RequestMeta.of(request).ipAddress())));
    }

    @PutMapping("/{labelId}")
    public ApiResult<Void> update(@PathVariable String labelId, @RequestBody TaskLabelService.LabelInput body, HttpServletRequest request) {
        labels.update(user(), Check.uuid(labelId), body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Label saved");
    }

    @DeleteMapping("/{labelId}")
    public ApiResult<Void> delete(@PathVariable String labelId, HttpServletRequest request) {
        labels.delete(user(), Check.uuid(labelId), RequestMeta.of(request).ipAddress());
        return ApiResult.message("Label deleted");
    }
}
