package com.jurislpo.jurisone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.EmployeeOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The employee onboarding wizard: the signed-in employee's own onboarding under {@code /api/onboarding/mine} (files and secrets are in
 * {@link OnboardingAttachmentController}); HR under {@code /api/people/{id}/onboarding} and the files on an employee's record under
 * {@code /api/people/{id}/documents} (view: {@code people.view}; approving or sending back: {@link OnboardingReviewController}).
 */
@RestController
public class EmployeeOnboardingController {

    public record StepChange(Integer step) {}

    private final Auth auth;
    private final EmployeeOnboardingService onboarding;

    public EmployeeOnboardingController(Auth auth, EmployeeOnboardingService onboarding) {
        this.auth = auth;
        this.onboarding = onboarding;
    }

    @GetMapping("/api/onboarding/mine")
    public ApiResult<Map<String, Object>> mine() {
        return ApiResult.success(onboarding.mine(auth.assertPerm()));
    }

    /** Body = that step's object (see references/ONBOARDING_WIZARD_API.md); answers the whole payload with the next step as the current one. */
    @PutMapping("/api/onboarding/mine/steps/{step}")
    public ApiResult<Map<String, Object>> saveStep(@PathVariable int step, @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return ApiResult.success(onboarding.saveStep(auth.assertPerm(), step, body, RequestMeta.of(request).ipAddress()));
    }

    @PutMapping("/api/onboarding/mine/current-step")
    public ApiResult<Map<String, Object>> currentStep(@RequestBody StepChange body, HttpServletRequest request) {
        return ApiResult.success(onboarding.saveCurrentStep(auth.assertPerm(), body.step(), RequestMeta.of(request).ipAddress()));
    }

    @PostMapping("/api/onboarding/mine/submission")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> submit(HttpServletRequest request) {
        return ApiResult.success(onboarding.submit(auth.assertPerm(), RequestMeta.of(request).ipAddress()));
    }

    // ───────────── HR ─────────────

    @GetMapping("/api/people/{id}/onboarding")
    public ApiResult<Map<String, Object>> forEmployee(@PathVariable String id) {
        auth.assertPerm("people.view", "people.add");
        return ApiResult.success(onboarding.forEmployee(id));
    }

    /** A file of the employee's onboarding (HR); {@code download=true} sends it as an attachment. */
    @GetMapping("/api/people/{id}/onboarding/attachments/{fileId}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @PathVariable String fileId, @RequestParam(defaultValue = "false") boolean download) {
        auth.assertPerm("people.view", "people.add");
        return stream(onboarding.document(id, fileId), download);
    }

    @GetMapping("/api/people/{id}/documents")
    public ApiResult<List<Map<String, Object>>> documents(@PathVariable String id) {
        auth.assertPerm("people.view");
        return ApiResult.success(onboarding.documents(id));
    }

    @GetMapping("/api/people/{id}/documents/{docId}/file")
    public ResponseEntity<byte[]> document(@PathVariable String id, @PathVariable String docId) {
        auth.assertPerm("people.view");
        return stream(onboarding.document(id, docId), false);
    }

    static ResponseEntity<byte[]> stream(EmployeeOnboardingService.FileOut file, boolean download) {
        if (file == null) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime())).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
}
