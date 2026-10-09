package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.PoliciesService;
import com.jurislpo.jurisone.service.SettingsService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/settings — the Policies page (roles.manage). */
@RestController
@RequestMapping("/api/settings")
public class PoliciesController {

    private final Auth auth;
    private final PoliciesService policies;
    private final SettingsService settings;

    public PoliciesController(Auth auth, PoliciesService policies, SettingsService settings) {
        this.auth = auth;
        this.policies = policies;
        this.settings = settings;
    }

    @GetMapping("/access")
    public ApiResult<List<PoliciesService.RolePerms>> access() {
        auth.requirePage("settings");
        return ApiResult.success(policies.accessMatrix());
    }

    @GetMapping("/leave")
    public ApiResult<Map<String, Object>> leave() {
        auth.requirePage("settings");
        return ApiResult.success(Map.of("types", policies.leaveTypes(), "attendance", settings.get("attendance.policy")));
    }

    @GetMapping("/holidays")
    public ApiResult<List<PoliciesService.HolidayView>> holidays(@RequestParam int year) {
        auth.requirePage("settings");
        return ApiResult.success(policies.holidays(year));
    }

    @GetMapping("/attendance")
    public ApiResult<Map<String, Object>> attendance() {
        auth.requirePage("settings");
        return ApiResult.success(policies.attendance());
    }

    @PutMapping("/attendance")
    public ApiResult<Void> updateAttendance(@RequestBody PoliciesService.AttendancePolicyInput body, HttpServletRequest req) {
        policies.updateAttendancePolicy(auth.assertPerm("roles.manage"), body, RequestMeta.of(req).ip());
        return ApiResult.message("Attendance policy saved");
    }
}
