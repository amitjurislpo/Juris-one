package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.OnboardingAttachmentService;
import com.jurislpo.jurisone.service.OnboardingAttachmentService.FileOut;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The signed-in employee's own onboarding files and secrets (wizard step 6 and the Summary). */
@RestController
public class OnboardingAttachmentController {

    private final Auth auth;
    private final OnboardingAttachmentService attachments;

    public OnboardingAttachmentController(Auth auth, OnboardingAttachmentService attachments) {
        this.auth = auth;
        this.attachments = attachments;
    }

    @PostMapping("/api/onboarding/mine/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> upload(@RequestParam String slotKey, @RequestParam MultipartFile file, HttpServletRequest request) throws IOException {
        return ApiResult.success(attachments.upload(auth.assertPerm(), slotKey, file.getOriginalFilename(), file.getBytes(), RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/api/onboarding/mine/attachments/{id}")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        attachments.delete(auth.assertPerm(), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("File removed");
    }

    @GetMapping("/api/onboarding/mine/attachments/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @RequestParam(defaultValue = "false") boolean download) {
        FileOut file = attachments.file(auth.assertPerm(), id);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline()).filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    @GetMapping("/api/onboarding/mine/secrets")
    public ResponseEntity<ApiResult<Map<String, String>>> secrets(HttpServletRequest request) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResult.success(attachments.secrets(auth.assertPerm(), RequestMeta.of(request).ipAddress())));
    }
}
