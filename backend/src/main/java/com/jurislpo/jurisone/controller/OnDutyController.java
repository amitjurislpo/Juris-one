package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.repository.OnDutyRepository.Item;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.OnDutyService;
import com.jurislpo.jurisone.service.OnDutyService.CreateInput;
import com.jurislpo.jurisone.service.OnDutyService.Created;
import com.jurislpo.jurisone.service.OnDutyService.DecisionResult;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** On Duty requests: own (self) or the whole business (appr.reg, Admin and HR), single and bulk decisions (appr.reg). */
@RestController
public class OnDutyController {

    public record DecisionBody(Boolean approve, String note) {}

    public record BulkDecisionBody(List<String> ids, Boolean approve, String note) {}

    private final Auth auth;
    private final OnDutyService onDuty;

    public OnDutyController(Auth auth, OnDutyService onDuty) {
        this.auth = auth;
        this.onDuty = onDuty;
    }

    /** ?scope=mine|all&status=&month=YYYY-MM&search=&departmentId=&employeeId=&page=; scope=all needs appr.reg. */
    @GetMapping("/api/v1/attendance/on-duty-requests")
    public ApiResult<Map<String, Object>> list(@RequestParam Map<String, String> queryParams) {
        boolean everyone = "all".equals(queryParams.get("scope"));
        return ApiResult.success(onDuty.list(auth.assertPerm(everyone ? "appr.reg" : "self"), everyone, queryParams));
    }

    @PostMapping("/api/v1/attendance/on-duty-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<List<OnDutyService.DayOutcome>> create(@RequestBody CreateInput body, HttpServletRequest request) {
        Created created = onDuty.create(auth.assertPerm("self"), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success(created.message(), created.days());
    }

    @GetMapping("/api/v1/attendance/on-duty-requests/{id}")
    public ApiResult<Item> details(@PathVariable String id) {
        return ApiResult.success(onDuty.details(auth.assertPerm("self", "appr.reg"), id));
    }

    @DeleteMapping("/api/v1/attendance/on-duty-requests/{id}")
    public ApiResult<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.message(onDuty.cancel(auth.assertPerm("self"), id, RequestMeta.of(request).ipAddress()));
    }

    /** Approve or reject one request ({@code {approve, note?}}); the first decision wins. */
    @PutMapping("/api/v1/attendance/on-duty-requests/{id}/decision")
    public ApiResult<Void> decide(@PathVariable String id, @RequestBody DecisionBody body, HttpServletRequest request) {
        return ApiResult.message(onDuty.decide(auth.assertPerm("appr.reg"), id, body.approve(), body.note(), RequestMeta.of(request).ipAddress()));
    }

    /** Approve or reject several requests at once ({ids, approve, note}); one result per request. */
    @PostMapping("/api/v1/attendance/on-duty-decisions")
    public ApiResult<List<DecisionResult>> decideMany(@RequestBody BulkDecisionBody body, HttpServletRequest request) {
        return ApiResult.success(onDuty.decideMany(auth.assertPerm("appr.reg"), body.ids(), body.approve(), body.note(), RequestMeta.of(request).ipAddress()));
    }
}
