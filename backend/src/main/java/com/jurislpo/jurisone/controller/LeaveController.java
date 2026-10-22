package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.ApprovalsService;
import com.jurislpo.jurisone.service.LeaveService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/leave (self) and /api/approvals (appr.leave / appr.reg). */
@RestController
public class LeaveController {

    private final Auth auth;
    private final LeaveService leave;
    private final ApprovalsService approvals;

    public LeaveController(Auth auth, LeaveService leave, ApprovalsService approvals) {
        this.auth = auth;
        this.leave = leave;
        this.approvals = approvals;
    }

    @GetMapping("/api/leave")
    public ApiResult<Map<String, Object>> page(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(leave.page(auth.requirePage("leave"), queryParams));
    }

    /** Live numbers for the apply form ({@code ?type=&from=&to=&part=}); rule failures come back in {@code data.error} (200). */
    @GetMapping("/api/leave/previews")
    public ApiResult<LeaveService.Preview> preview(@RequestParam(required = false) String type, @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to, @RequestParam(required = false) String part) {
        return ApiResult.success(leave.preview(auth.assertPerm("self"), new LeaveService.ApplyInput(type, from, to, part, null)));
    }

    @PostMapping("/api/leave/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> apply(@RequestBody LeaveService.ApplyInput body, HttpServletRequest request) {
        return ApiResult.message(leave.apply(auth.assertPerm("self"), body, RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/api/leave/requests/{id}")
    public ApiResult<Void> cancel(@PathVariable String id) {
        return ApiResult.message(leave.cancel(auth.assertPerm("self"), id));
    }

    @GetMapping("/api/approvals")
    public ApiResult<Map<String, Object>> approvals(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(approvals.page(auth.requirePage("approvals"), queryParams));
    }

    /** Approve or reject a leave request ({@code {approve, note?}}); the first decision wins. */
    @PutMapping("/api/leave/requests/{id}/decision")
    public ApiResult<Void> decideLeave(@PathVariable String id, @RequestBody DecisionBody body, HttpServletRequest request) {
        return ApiResult.message(approvals.decideLeave(auth.assertPerm("appr.leave"), body.with(id), RequestMeta.of(request).ipAddress()));
    }

    /** Approve or reject a regularization request ({@code {approve, note?}}); the first decision wins. */
    @PutMapping("/api/attendance/regularizations/{id}/decision")
    public ApiResult<Void> decideRegularization(@PathVariable String id, @RequestBody DecisionBody body, HttpServletRequest request) {
        return ApiResult.message(approvals.decideRegularization(auth.assertPerm("appr.reg"), body.with(id), RequestMeta.of(request).ipAddress()));
    }

    public record DecisionBody(Boolean approve, String note) {
        ApprovalsService.Decision with(String id) {
            return new ApprovalsService.Decision(id, approve, note);
        }
    }
}
