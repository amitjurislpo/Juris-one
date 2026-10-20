package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.AuditViewService;
import com.jurislpo.jurisone.service.DashboardService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.security.Auth;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Home, company overview, My team and the audit log (read-only pages). */
@RestController
public class DashboardController {

    private final Auth auth;
    private final DashboardService dash;
    private final AuditViewService audit;

    public DashboardController(Auth auth, DashboardService dash, AuditViewService audit) {
        this.auth = auth;
        this.dash = dash;
        this.audit = audit;
    }

    @GetMapping("/api/home")
    public ApiResult<Map<String, Object>> home() {
        return ApiResult.success(dash.home(auth.requirePage("home")));
    }

    @GetMapping("/api/overview")
    public ApiResult<Map<String, Object>> overview() {
        auth.requirePage("overview");
        return ApiResult.success(dash.overviewData());
    }

    @GetMapping("/api/team")
    public ApiResult<Map<String, Object>> team(@RequestParam Map<String, String> sp) {
        return ApiResult.success(dash.team(auth.requirePage("team"), sp));
    }

    @GetMapping("/api/audit")
    public ApiResult<Map<String, Object>> audit(@RequestParam Map<String, String> sp) {
        return ApiResult.success(audit.rows(auth.requirePage("audit"), sp));
    }
}
