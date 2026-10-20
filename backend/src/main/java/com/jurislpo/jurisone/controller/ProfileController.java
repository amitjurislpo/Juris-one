package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.ProfileService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.service.OnboardingService;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's own profile and documents (everyone in a business). */
@RestController
public class ProfileController {

    private final Auth auth;
    private final ProfileService profiles;
    private final OnboardingService onboarding;

    public ProfileController(Auth auth, ProfileService profiles, OnboardingService onboarding) {
        this.auth = auth;
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @GetMapping("/api/profile")
    public ApiResult<Map<String, Object>> mine() {
        CurrentUser u = auth.requirePage("profile");
        Map<String, Object> p = new java.util.LinkedHashMap<>(profiles.mine(u));
        p.put("documents", onboarding.employeeDocuments(u.id().toString()));
        return ApiResult.success(p);
    }

    /** {@code {fatherName, dateOfBirth, currentAddress, permanentAddress, emergencyName, emergencyRelation, emergencyPhoneCountry, emergencyPhone}} */
    @PutMapping("/api/profile")
    public ApiResult<Void> update(@RequestBody ProfileService.ProfileInput body, HttpServletRequest req) {
        return ApiResult.message(profiles.update(auth.requirePage("profile"), body, RequestMeta.of(req).ip()));
    }

    @GetMapping("/api/profile/documents/{docId}/file")
    public ResponseEntity<byte[]> document(@PathVariable String docId) {
        CurrentUser u = auth.requirePage("profile");
        OnboardingService.FileOut f = onboarding.employeeDocument(u.id().toString(), docId);
        if (f == null) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.NOT_FOUND, "File not found.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(f.bytes());
    }
}
