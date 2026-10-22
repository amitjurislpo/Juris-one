package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.MyAttendanceService;
import com.jurislpo.jurisone.service.RegisterService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** My attendance + check-in (self), the register (att.all) and its CSV export. */
@RestController
public class AttendanceController {

    public record PunchRequest(String expect) {}

    private final Auth auth;
    private final MyAttendanceService mine;
    private final RegisterService register;

    public AttendanceController(Auth auth, MyAttendanceService mine, RegisterService register) {
        this.auth = auth;
        this.mine = mine;
        this.register = register;
    }

    /** ?month=YYYY-MM&date=YYYY-MM-DD; {@code tracked:false} when attendance isn't tracked for the user. */
    @GetMapping("/api/attendance/mine")
    public ApiResult<MyAttendanceService.Page> me(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(mine.page(auth.requirePage("attendance"), queryParams));
    }

    @PostMapping("/api/attendance/regularizations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> requestRegularization(@RequestBody MyAttendanceService.RegularizationInput body, HttpServletRequest request) {
        return ApiResult.message(mine.requestRegularization(auth.assertPerm("self"), body, RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/api/attendance/regularizations/{id}")
    public ApiResult<Void> cancelRegularization(@PathVariable String id) {
        return ApiResult.message(mine.cancelRegularization(auth.assertPerm("self"), id));
    }

    /** Check in / check out ({@code {"expect": "IN" | "OUT"}} — the button the user saw). */
    @PostMapping("/api/attendance/punches")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> punch(@RequestBody PunchRequest body, HttpServletRequest request) {
        MyAttendanceService.PunchResult punchResult = mine.punch(auth.assertPerm("self"), body.expect(), RequestMeta.of(request).ipAddress());
        return ApiResult.success(punchResult.message(), Map.of("kind", punchResult.kind()));
    }

    /** Register page data: daily log (?date=&timeZone=&status=) or monthly grid (?view=month&month=), paged 50. */
    @GetMapping("/api/attendance/register")
    public ApiResult<Map<String, Object>> registerPage(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(register.page(auth.requirePage("register"), queryParams));
    }

    /** CSV (att.all) — same filters as the page. */
    @GetMapping("/api/attendance/register/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> queryParams) {
        RegisterService.Export exportFile = register.export(auth.assertPerm("att.all"), queryParams);
        return PeopleController.csv(exportFile.filename(), exportFile.body());
    }
}
