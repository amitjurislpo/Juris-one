package com.jurislpo.jurisone.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.RegularizationRepository.Item;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.RegularizationService;
import com.jurislpo.jurisone.service.RegularizationService.CreateInput;
import com.jurislpo.jurisone.service.RegularizationService.DayOutcome;
import com.jurislpo.jurisone.service.RegularizationService.DayPreview;
import com.jurislpo.jurisone.service.RegularizationService.DecisionResult;
import com.jurislpo.jurisone.service.RegularizationService.FileOut;
import com.jurislpo.jurisone.service.RegularizationService.ReasonInput;
import com.jurislpo.jurisone.service.RegularizationService.Upload;
import com.jurislpo.jurisone.service.SettingsService.RegularizationPolicy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attendance regularization: requests (own: self; whole business: appr.reg), the rules and reason list (read: self; change: appr.reg),
 * bulk decisions and the export (appr.reg). A single decision is PUT /api/v1/attendance/regularizations/{id}/decision (LeaveController).
 */
@RestController
public class RegularizationController {

    public record BulkDecisionBody(List<String> ids, Boolean approve, String note) {}

    private final Auth auth;
    private final RegularizationService regularizations;
    private final ObjectMapper json;

    public RegularizationController(Auth auth, RegularizationService regularizations, ObjectMapper json) {
        this.auth = auth;
        this.regularizations = regularizations;
        this.json = json;
    }

    /** What is recorded for one of my days, and which request types fit it (prefills the form). */
    @GetMapping("/api/v1/attendance/mine/days/{date}")
    public ApiResult<DayPreview> day(@PathVariable String date, @RequestParam(required = false) String employeeId) {
        return ApiResult.success(regularizations.dayPreview(auth.assertPerm("self", "appr.reg"), date, employeeId));
    }

    @PostMapping(value = "/api/v1/attendance/regularizations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<List<DayOutcome>> create(@RequestBody CreateInput body, HttpServletRequest request) {
        return created(regularizations.create(auth.assertPerm("self", "appr.reg"), body, null, RequestMeta.of(request).ipAddress()));
    }

    /** The same with one attachment: parts "request" (the JSON body) and "file". */
    @PostMapping(value = "/api/v1/attendance/regularizations", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<List<DayOutcome>> createWithAttachment(@RequestParam("request") String requestJson, @RequestParam(value = "file", required = false) MultipartFile file, HttpServletRequest request)
            throws IOException {
        CreateInput body;
        try {
            body = json.readValue(requestJson, CreateInput.class);
        } catch (JsonProcessingException e) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        }
        Upload upload = file == null || file.isEmpty() ? null : new Upload(file.getOriginalFilename(), file.getBytes());
        return created(regularizations.create(auth.assertPerm("self", "appr.reg"), body, upload, RequestMeta.of(request).ipAddress()));
    }

    private static ApiResult<List<DayOutcome>> created(RegularizationService.Created created) {
        return ApiResult.success(created.message(), created.days());
    }

    /** ?scope=mine|all&status=&month=YYYY-MM&search=&departmentId=&employeeId=&page=; scope=all needs appr.reg. */
    @GetMapping("/api/v1/attendance/regularizations")
    public ApiResult<Map<String, Object>> list(@RequestParam Map<String, String> queryParams) {
        boolean everyone = "all".equals(queryParams.get("scope"));
        return ApiResult.success(regularizations.list(auth.assertPerm(everyone ? "appr.reg" : "self"), everyone, queryParams));
    }

    @GetMapping("/api/v1/attendance/regularizations/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> queryParams) {
        RegularizationService.Export file = regularizations.export(auth.assertPerm("appr.reg"), queryParams);
        return PeopleController.csv(file.filename(), file.body());
    }

    @GetMapping("/api/v1/attendance/regularizations/{id}")
    public ApiResult<Item> details(@PathVariable String id) {
        return ApiResult.success(regularizations.details(auth.assertPerm("self", "appr.reg"), id));
    }

    @DeleteMapping("/api/v1/attendance/regularizations/{id}")
    public ApiResult<Void> cancel(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.message(regularizations.cancel(auth.assertPerm("self"), id, RequestMeta.of(request).ipAddress()));
    }

    @GetMapping("/api/v1/attendance/regularizations/{id}/attachment")
    public ResponseEntity<byte[]> attachment(@PathVariable String id, @RequestParam(defaultValue = "false") boolean download) {
        FileOut file = regularizations.attachment(auth.assertPerm("self", "appr.reg"), id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    /** Approve or reject several requests at once ({ids, approve, note}); one result per request. */
    @PostMapping("/api/v1/attendance/regularization-decisions")
    public ApiResult<List<DecisionResult>> decideMany(@RequestBody BulkDecisionBody body, HttpServletRequest request) {
        return ApiResult.success(regularizations.decideMany(auth.assertPerm("appr.reg"), body.ids(), body.approve(), body.note(), RequestMeta.of(request).ipAddress()));
    }

    /** The rules (back-date limit, monthly limit, attachment) with my use of the month (?month=YYYY-MM) and the reasons. */
    @GetMapping("/api/v1/attendance/regularization-rules")
    public ApiResult<Map<String, Object>> rules(@RequestParam(required = false) String month) {
        return ApiResult.success(regularizations.rules(auth.assertPerm("self", "appr.reg"), month));
    }

    @PutMapping("/api/v1/attendance/regularization-rules")
    public ApiResult<Void> saveRules(@RequestBody RegularizationPolicy body, HttpServletRequest request) {
        return ApiResult.message(regularizations.saveRules(auth.assertPerm("appr.reg"), body, RequestMeta.of(request).ipAddress()));
    }

    @PostMapping("/api/v1/attendance/regularization-reasons")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> addReason(@RequestBody ReasonInput body, HttpServletRequest request) {
        return ApiResult.message(regularizations.addReason(auth.assertPerm("appr.reg"), body, RequestMeta.of(request).ipAddress()));
    }

    @PatchMapping("/api/v1/attendance/regularization-reasons/{id}")
    public ApiResult<Void> updateReason(@PathVariable String id, @RequestBody ReasonInput body, HttpServletRequest request) {
        return ApiResult.message(regularizations.updateReason(auth.assertPerm("appr.reg"), id, body, RequestMeta.of(request).ipAddress()));
    }
}
