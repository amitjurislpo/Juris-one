package com.jurislpo.jurisone.leave;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ApiResult<Map<String, Object>> page(@RequestParam Map<String, String> sp) {
        return ApiResult.success(leave.page(auth.requirePage("leave"), sp));
    }

    /** Live numbers for the apply form; rule failures come back in {@code data.error} (200), like the TS action. */
    @PostMapping("/api/leave/preview")
    public ApiResult<LeaveService.Preview> preview(@RequestBody LeaveService.ApplyInput body) {
        return ApiResult.success(leave.preview(auth.assertPerm("self"), body));
    }

    @PostMapping("/api/leave/requests")
    public ApiResult<Void> apply(@RequestBody LeaveService.ApplyInput body, HttpServletRequest req) {
        return ApiResult.message(leave.apply(auth.assertPerm("self"), body, RequestMeta.of(req).ip()));
    }

    @DeleteMapping("/api/leave/requests/{id}")
    public ApiResult<Void> cancel(@PathVariable String id) {
        return ApiResult.message(leave.cancel(auth.assertPerm("self"), id));
    }

    @GetMapping("/api/approvals")
    public ApiResult<Map<String, Object>> approvals(@RequestParam Map<String, String> sp) {
        return ApiResult.success(approvals.page(auth.requirePage("approvals"), sp));
    }

    @PostMapping("/api/approvals/leave")
    public ApiResult<Void> decideLeave(@RequestBody ApprovalsService.Decision body, HttpServletRequest req) {
        return ApiResult.message(approvals.decideLeave(auth.assertPerm("appr.leave"), body, RequestMeta.of(req).ip()));
    }

    @PostMapping("/api/approvals/regularization")
    public ApiResult<Void> decideRegularization(@RequestBody ApprovalsService.Decision body, HttpServletRequest req) {
        return ApiResult.message(approvals.decideRegularization(auth.assertPerm("appr.reg"), body, RequestMeta.of(req).ip()));
    }
}
