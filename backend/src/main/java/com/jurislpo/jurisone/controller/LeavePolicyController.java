package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.LeavePolicyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/settings/leave-policy — the leave year and the year-end choice per leave type. Admin (roles.manage) and HR (holidays.manage). */
@RestController
@RequestMapping("/api/v1/settings/leave-policy")
public class LeavePolicyController {

    private static final String[] ADMIN_OR_HR = {"roles.manage", "holidays.manage"};

    private final Auth auth;
    private final LeavePolicyService leavePolicy;

    public LeavePolicyController(Auth auth, LeavePolicyService leavePolicy) {
        this.auth = auth;
        this.leavePolicy = leavePolicy;
    }

    @GetMapping
    public ApiResult<LeavePolicyService.PolicyView> read() {
        auth.assertPerm(ADMIN_OR_HR);
        return ApiResult.success(leavePolicy.view());
    }

    /** Sets the month and day the leave year starts on (default 1 January). */
    @PutMapping("/year")
    public ApiResult<Void> updateYear(@RequestBody LeavePolicyService.YearInput body, HttpServletRequest request) {
        leavePolicy.updateYear(auth.assertPerm(ADMIN_OR_HR), body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Leave year saved");
    }

    /** Changes what happens to one leave type's balance when a leave year ends. */
    @PatchMapping("/types/{code}")
    public ApiResult<Void> updateYearEnd(@PathVariable String code, @RequestBody LeavePolicyService.YearEndInput body, HttpServletRequest request) {
        leavePolicy.updateYearEnd(auth.assertPerm(ADMIN_OR_HR), code, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Year-end choice saved");
    }
}
