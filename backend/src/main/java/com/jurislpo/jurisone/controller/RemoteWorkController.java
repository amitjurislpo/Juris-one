package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.repository.RemoteWorkRepository.Item;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.RemoteWorkService;
import com.jurislpo.jurisone.service.RemoteWorkService.CreateInput;
import com.jurislpo.jurisone.service.RemoteWorkService.DecisionResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Remote work requests of the Attendance page: my own (self), the Team scope (Admin / HR: everyone; a manager: direct reports, read only),
 * and decisions (appr.reg: Admin and HR).
 */
@RestController
public class RemoteWorkController {

    public record DecisionBody(Boolean approve, String note) {}

    public record BulkDecisionBody(List<String> ids, Boolean approve, String note) {}

    private final Auth auth;
    private final RemoteWorkService remoteWork;

    public RemoteWorkController(Auth auth, RemoteWorkService remoteWork) {
        this.auth = auth;
        this.remoteWork = remoteWork;
    }

    /** ?scope=mine|team&status=&month=YYYY-MM&search=&departmentId=&employeeId=&page=; scope=team: appr.reg sees everyone, a manager the direct reports. */
    @GetMapping("/api/v1/attendance/remote-work-requests")
    public ApiResult<Map<String, Object>> list(@RequestParam Map<String, String> queryParams) {
        // the team scope is for approvers (the Admin has no attendance page of their own); the service refuses anyone who may not see it
        boolean isTeam = "team".equals(queryParams.get("scope"));
        return ApiResult.success(remoteWork.list(isTeam ? auth.assertPerm() : auth.requirePage("attendance"), queryParams));
    }

    @PostMapping("/api/v1/attendance/remote-work-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody CreateInput body, HttpServletRequest request) {
        String id = remoteWork.create(auth.assertPerm("self"), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Remote work request added successfully", Map.of("id", id));
    }

    /** Changes a request that is still pending (only the person who raised it). */
    @PatchMapping("/api/v1/attendance/remote-work-requests/{id}")
    public ApiResult<Void> edit(@PathVariable String id, @RequestBody RemoteWorkService.EditInput body, HttpServletRequest request) {
        return ApiResult.message(remoteWork.edit(auth.assertPerm("self"), id, body, RequestMeta.of(request).ipAddress()));
    }

    @GetMapping("/api/v1/attendance/remote-work-requests/{id}")
    public ApiResult<Item> details(@PathVariable String id) {
        return ApiResult.success(remoteWork.details(auth.assertPerm(), id));
    }

    /** Withdraws my own pending request. */
    @DeleteMapping("/api/v1/attendance/remote-work-requests/{id}")
    public ApiResult<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.message(remoteWork.cancel(auth.assertPerm("self"), id, RequestMeta.of(request).ipAddress()));
    }

    /** Approve or reject one request ({@code {approve, note?}}); the first decision wins; a rejection needs remarks. */
    @PutMapping("/api/v1/attendance/remote-work-requests/{id}/decision")
    public ApiResult<Void> decide(@PathVariable String id, @RequestBody DecisionBody body, HttpServletRequest request) {
        return ApiResult.message(remoteWork.decide(auth.assertPerm(), id, body.approve(), body.note(), RequestMeta.of(request).ipAddress()));
    }

    /** Approve or reject several requests at once ({ids, approve, note}); one result per request. */
    @PostMapping("/api/v1/attendance/remote-work-decisions")
    public ApiResult<List<DecisionResult>> decideMany(@RequestBody BulkDecisionBody body, HttpServletRequest request) {
        return ApiResult.success(remoteWork.decideMany(auth.assertPerm(), body.ids(), body.approve(), body.note(), RequestMeta.of(request).ipAddress()));
    }
}
