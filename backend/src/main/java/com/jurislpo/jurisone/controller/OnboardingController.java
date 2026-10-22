package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.OnboardingService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.service.PeopleService;
import com.jurislpo.jurisone.security.Auth;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Onboarding: HR ({@code people.add}) under {@code /api/onboardings}; the candidate, signed out, under
 * {@code /api/public/onboardings/{token}} (the one-time link is the only credential); verified documents on an employee's record
 * under {@code /api/people/{id}/documents} ({@code people.view}).
 */
@RestController
public class OnboardingController {

    private final Auth auth;
    private final OnboardingService onboarding;

    public OnboardingController(Auth auth, OnboardingService onboarding) {
        this.auth = auth;
        this.onboarding = onboarding;
    }

    static ResponseEntity<byte[]> file(OnboardingService.FileOut file) {
        if (file == null) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    // ───────────── HR ─────────────

    @GetMapping("/api/onboardings")
    public ApiResult<List<Map<String, Object>>> list() {
        auth.requirePage("onboarding");
        return ApiResult.success(onboarding.list());
    }

    /** {@code {candidateName, personalEmail, designation?, departmentId?, roleId?, managerId?, joiningDate?, location?, requiredDocs?}} — emails the link. */
    @PostMapping("/api/onboardings")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> start(@RequestBody OnboardingService.Plan body, HttpServletRequest request) {
        String id = onboarding.start(auth.requirePage("onboarding"), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Onboarding link sent to " + body.personalEmail().trim().toLowerCase(), Map.of("id", id));
    }

    /** The business's default list of required documents (and every kind HR can pick). */
    @GetMapping("/api/onboardings/defaults")
    public ApiResult<Map<String, Object>> defaults() {
        auth.requirePage("onboarding");
        return ApiResult.success(onboarding.defaults());
    }

    public record DefaultsBody(List<String> requiredDocs) {}

    /** {@code {"requiredDocs": [...]}} — what new onboardings ask for unless HR changes it per candidate. */
    @PutMapping("/api/onboardings/defaults")
    public ApiResult<Void> saveDefaults(@RequestBody DefaultsBody body, HttpServletRequest request) {
        return ApiResult.message(onboarding.saveDefaults(auth.requirePage("onboarding"), body.requiredDocs(), RequestMeta.of(request).ipAddress()));
    }

    @GetMapping("/api/onboardings/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        auth.requirePage("onboarding");
        return ApiResult.success(onboarding.detail(id));
    }

    @PutMapping("/api/onboardings/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody OnboardingService.Plan body, HttpServletRequest request) {
        onboarding.update(auth.requirePage("onboarding"), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Saved");
    }

    /** A new link (the old one stops working). */
    @PostMapping("/api/onboardings/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> resend(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.message(onboarding.resend(auth.requirePage("onboarding"), id, RequestMeta.of(request).ipAddress()));
    }

    /** {@code {"status": "VERIFIED" | "REJECTED", "comment"?}} */
    @PutMapping("/api/onboardings/{id}/documents/{docId}/status")
    public ApiResult<Void> decide(@PathVariable String id, @PathVariable String docId, @RequestBody OnboardingService.DocDecision body, HttpServletRequest request) {
        return ApiResult.message(onboarding.decideDocument(auth.requirePage("onboarding"), id, docId, body, RequestMeta.of(request).ipAddress()));
    }

    @GetMapping("/api/onboardings/{id}/documents/{docId}/file")
    public ResponseEntity<byte[]> document(@PathVariable String id, @PathVariable String docId) {
        auth.requirePage("onboarding");
        return file(onboarding.file(id, docId));
    }

    /** {@code {"status": "CHANGES_REQUESTED" | "VERIFIED" | "CANCELLED", "comment"?}} */
    @PutMapping("/api/onboardings/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody OnboardingService.StatusInput body, HttpServletRequest request) {
        return ApiResult.message(onboarding.setStatus(auth.requirePage("onboarding"), id, body, RequestMeta.of(request).ipAddress()));
    }

    /** Creates the employee (portal access off, no invite yet). */
    @PostMapping("/api/onboardings/{id}/employee")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addEmployee(@PathVariable String id, @RequestBody OnboardingService.AddInput body, HttpServletRequest request) {
        PeopleService.Saved saved = onboarding.addEmployee(auth.requirePage("onboarding"), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.success(saved.message(), Map.of("id", saved.id(), "code", saved.code()));
    }

    // ───────────── employee record ─────────────

    @GetMapping("/api/people/{id}/documents")
    public ApiResult<List<Map<String, Object>>> employeeDocuments(@PathVariable String id) {
        auth.assertPerm("people.view");
        return ApiResult.success(onboarding.employeeDocuments(id));
    }

    @GetMapping("/api/people/{id}/documents/{docId}/file")
    public ResponseEntity<byte[]> employeeDocument(@PathVariable String id, @PathVariable String docId) {
        auth.assertPerm("people.view");
        return file(onboarding.employeeDocument(id, docId));
    }

    // ───────────── candidate (signed out; the link is the credential) ─────────────

    @GetMapping("/api/public/onboardings/{token}")
    public ApiResult<Map<String, Object>> candidate(@PathVariable String token) {
        return ApiResult.success(onboarding.candidateView(token));
    }

    @PutMapping("/api/public/onboardings/{token}")
    public ApiResult<Void> candidateSave(@PathVariable String token, @RequestBody OnboardingService.Form body) {
        return ApiResult.message(onboarding.candidateSave(token, body));
    }

    /** Multipart {@code kind} + {@code file} (PDF, JPG or PNG up to 10 MB). */
    @PostMapping(value = "/api/public/onboardings/{token}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> candidateUpload(@PathVariable String token, @RequestParam(name = "kind", required = false) String kind,
                                                         @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
        Map<String, String> uploaded = onboarding.candidateUpload(token, kind, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes());
        return ApiResult.success("Uploaded " + uploaded.get("name"), uploaded);
    }

    @DeleteMapping("/api/public/onboardings/{token}/documents/{docId}")
    public ApiResult<Void> candidateDelete(@PathVariable String token, @PathVariable String docId) {
        onboarding.candidateDelete(token, docId);
        return ApiResult.message("Removed");
    }

    @GetMapping("/api/public/onboardings/{token}/documents/{docId}/file")
    public ResponseEntity<byte[]> candidateFile(@PathVariable String token, @PathVariable String docId) {
        return file(onboarding.candidateFile(token, docId));
    }

    @PostMapping("/api/public/onboardings/{token}/submission")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> candidateSubmit(@PathVariable String token, HttpServletRequest request) {
        return ApiResult.message(onboarding.candidateSubmit(token, RequestMeta.of(request).ipAddress()));
    }
}
