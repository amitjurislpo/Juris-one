package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.AttendanceApprovalPolicy;
import com.jurislpo.jurisone.service.AttendanceApprovalPolicy.PendingCounts;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The pending-request badges of the Attendance page: how many requests of each kind wait for the signed-in user to decide. */
@RestController
public class AttendanceRequestsController {

    private final Auth auth;
    private final AttendanceApprovalPolicy approvalPolicy;

    public AttendanceRequestsController(Auth auth, AttendanceApprovalPolicy approvalPolicy) {
        this.auth = auth;
        this.approvalPolicy = approvalPolicy;
    }

    /** Zero for everybody who cannot decide anything; counted by the approval rule (Admin / HR, or the manager of an HR person), never by the browser. */
    @GetMapping("/api/v1/attendance/pending-counts")
    public ApiResult<PendingCounts> pendingCounts() {
        return ApiResult.success(approvalPolicy.pendingFor(auth.assertPerm()));
    }
}
