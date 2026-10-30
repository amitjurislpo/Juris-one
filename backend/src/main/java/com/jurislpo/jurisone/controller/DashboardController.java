package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.AuditViewService;
import com.jurislpo.jurisone.service.DashboardService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.security.Auth;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/api/v1/home")
    public ApiResult<Map<String, Object>> home() {
        return ApiResult.success(dash.home(auth.requirePage("home")));
    }

    @GetMapping("/api/v1/overview")
    public ApiResult<Map<String, Object>> overview() {
        auth.requirePage("overview");
        return ApiResult.success(dash.overviewData());
    }

    @GetMapping("/api/v1/team")
    public ApiResult<Map<String, Object>> team(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(dash.team(auth.requirePage("team"), queryParams));
    }

    @GetMapping("/api/v1/audit")
    public ApiResult<Map<String, Object>> audit(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(audit.rows(auth.requirePage("audit"), queryParams));
    }

    /** The audit log as CSV (same filters, up to 50,000 rows). */
    @GetMapping("/api/v1/audit/export")
    public ResponseEntity<byte[]> auditExport(@RequestParam Map<String, String> queryParams) {
        return PeopleController.csv("audit-log.csv", audit.exportCsv(auth.requirePage("audit"), queryParams, false));
    }

    /** The super admin's audit log: every business and the platform itself. Filters: days, ev, actor, biz (a business id, or "platform"), q, page. */
    @GetMapping("/api/v1/platform/audit")
    public ApiResult<Map<String, Object>> platformAudit(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(audit.platformRows(auth.requirePage("platform-audit"), queryParams));
    }

    @GetMapping("/api/v1/platform/audit/export")
    public ResponseEntity<byte[]> platformAuditExport(@RequestParam Map<String, String> queryParams) {
        return PeopleController.csv("platform-audit-log.csv", audit.exportCsv(auth.requirePage("platform-audit"), queryParams, true));
    }
}
