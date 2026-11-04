package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.AttendanceSummaryService;
import com.jurislpo.jurisone.service.MyAttendanceService;
import com.jurislpo.jurisone.service.RegisterService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.JurisTrackGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AttendanceSummaryService summary;
    private final RegisterService register;
    private final JurisTrackGatewayService jurisTrack;

    public AttendanceController(Auth auth, MyAttendanceService mine, AttendanceSummaryService summary, RegisterService register, JurisTrackGatewayService jurisTrack) {
        this.auth = auth;
        this.mine = mine;
        this.summary = summary;
        this.register = register;
        this.jurisTrack = jurisTrack;
    }

    /** ?month=YYYY-MM&date=YYYY-MM-DD; {@code tracked:false} when attendance isn't tracked for the user. */
    @GetMapping("/api/v1/attendance/mine")
    public ApiResult<MyAttendanceService.Page> me(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(mine.page(auth.requirePage("attendance"), queryParams));
    }

    /** The signed-in employee's days for a period: {@code ?from=&to=&view=list|tabular|calendar&status=} (defaults: this week, or this month for the calendar). */
    @GetMapping("/api/v1/attendance/summary")
    public ApiResult<AttendanceSummaryService.Summary> summary(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(summary.summary(auth.requirePage("attendance"), queryParams));
    }

    /** The same rows as a file: {@code ?format=csv|pdf} plus the summary's filters. */
    @GetMapping("/api/v1/attendance/summary/export")
    public ResponseEntity<byte[]> summaryExport(@RequestParam Map<String, String> queryParams) {
        AttendanceSummaryService.Export file = summary.export(auth.requirePage("attendance"), queryParams);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.body());
    }

    /** Check in / check out ({@code {"expect": "IN" | "OUT"}} — the button the user saw). */
    @PostMapping("/api/v1/attendance/punches")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> punch(@RequestBody PunchRequest body, HttpServletRequest request) {
        CurrentUser user = auth.assertPerm("self");
        String ipAddress = RequestMeta.of(request).ipAddress();
        MyAttendanceService.PunchResult punchResult = mine.punch(user, body.expect(), ipAddress);
        jurisTrack.mirrorPunchAsTimeEvent(user, punchResult.kind(), ipAddress); // best effort: never fails the punch
        return ApiResult.success(punchResult.message(), Map.of("kind", punchResult.kind()));
    }

    /** Register page data: daily log (?date=&timeZone=&status=) or monthly grid (?view=month&month=), paged 50. */
    @GetMapping("/api/v1/attendance/register")
    public ApiResult<Map<String, Object>> registerPage(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(register.page(auth.requirePage("register"), queryParams));
    }

    /** CSV (att.all) — same filters as the page. */
    @GetMapping("/api/v1/attendance/register/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> queryParams) {
        RegisterService.Export exportFile = register.export(auth.assertPerm("att.all"), queryParams);
        return PeopleController.csv(exportFile.filename(), exportFile.body());
    }
}
