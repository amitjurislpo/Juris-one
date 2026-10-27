package com.jurislpo.jurisone.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.OnboardingHrService;
import com.jurislpo.jurisone.service.OnboardingHrService.OnboardingStart;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * HR (permission {@code people.add}) starts onboardings, lists them and fills the wizard on the employee's behalf. The payloads are those of
 * {@link EmployeeOnboardingController} and {@link OnboardingAttachmentController}; reading the onboarding and streaming a file stay in the former.
 */
@RestController
public class OnboardingHrController {

    private static final String PERMISSION = "people.add";

    private final Auth auth;
    private final OnboardingHrService hr;

    public OnboardingHrController(Auth auth, OnboardingHrService hr) {
        this.auth = auth;
        this.hr = hr;
    }

    @GetMapping("/api/onboardings")
    public ApiResult<OnboardingHrService.Page> list(@RequestParam Map<String, String> queryParams) {
        auth.assertPerm(PERMISSION);
        return ApiResult.success(hr.list(queryParams));
    }

    @PostMapping("/api/onboardings")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> start(@RequestBody OnboardingStart body, HttpServletRequest request) {
        return ApiResult.success("Onboarding started", Map.of("employeeId", hr.start(auth.assertPerm(PERMISSION), body, RequestMeta.of(request).ipAddress()).toString()));
    }

    @PutMapping("/api/people/{id}/onboarding/steps/{step}")
    public ApiResult<Map<String, Object>> saveStep(@PathVariable String id, @PathVariable int step, @RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        return ApiResult.success(hr.saveStep(auth.assertPerm(PERMISSION), id, step, body, RequestMeta.of(request).ipAddress()));
    }

    @PutMapping("/api/people/{id}/onboarding/current-step")
    public ApiResult<Map<String, Object>> currentStep(@PathVariable String id, @RequestBody EmployeeOnboardingController.StepChange body) {
        auth.assertPerm(PERMISSION);
        return ApiResult.success(hr.saveCurrentStep(id, body.step()));
    }

    @PostMapping("/api/people/{id}/onboarding/submission")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> submit(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.success(hr.submit(auth.assertPerm(PERMISSION), id, RequestMeta.of(request).ipAddress()));
    }

    @PostMapping("/api/people/{id}/onboarding/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> upload(@PathVariable String id, @RequestParam String slotKey, @RequestParam MultipartFile file, HttpServletRequest request) throws IOException {
        return ApiResult.success(hr.upload(auth.assertPerm(PERMISSION), id, slotKey, file.getOriginalFilename(), file.getBytes(), RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/api/people/{id}/onboarding/attachments/{fileId}")
    public ApiResult<Void> deleteAttachment(@PathVariable String id, @PathVariable String fileId, HttpServletRequest request) {
        hr.deleteAttachment(auth.assertPerm(PERMISSION), id, fileId, RequestMeta.of(request).ipAddress());
        return ApiResult.message("File removed");
    }

    /** The full bank account number, PAN and Aadhaar, for the HR form; audited. */
    @GetMapping("/api/people/{id}/onboarding/secrets")
    public ResponseEntity<ApiResult<Map<String, String>>> secrets(@PathVariable String id, HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResult.success(hr.secrets(auth.assertPerm(PERMISSION), id, RequestMeta.of(request).ipAddress())));
    }
}
