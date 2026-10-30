package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.ProfileService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.service.EmployeeOnboardingService;
import com.jurislpo.jurisone.service.PhotoService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's own profile and documents (everyone in a business). */
@RestController
public class ProfileController {

    private final Auth auth;
    private final ProfileService profiles;
    private final EmployeeOnboardingService onboarding;
    private final PhotoService photos;

    public ProfileController(Auth auth, ProfileService profiles, EmployeeOnboardingService onboarding, PhotoService photos) {
        this.photos = photos;
        this.auth = auth;
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @GetMapping("/api/v1/profile")
    public ApiResult<Map<String, Object>> mine() {
        CurrentUser user = auth.requirePage("profile");
        Map<String, Object> row = new java.util.LinkedHashMap<>(profiles.mine(user));
        row.put("documents", onboarding.documents(user.id().toString()));
        return ApiResult.success(row);
    }

    /** {@code {fatherName, dateOfBirth, currentAddress, permanentAddress, emergencyName, emergencyRelation, emergencyPhoneCountry, emergencyPhone}} */
    @PutMapping("/api/v1/profile")
    public ApiResult<Void> update(@RequestBody ProfileService.ProfileInput body, HttpServletRequest request) {
        return ApiResult.message(profiles.update(auth.requirePage("profile"), body, RequestMeta.of(request).ipAddress()));
    }

    /** My own photo: the raw image bytes (JPEG, PNG or WebP, at most 400 KB) as the request body. Answers {@code {photo: <version>}}. */
    @PutMapping("/api/v1/profile/photo")
    public ApiResult<Map<String, Long>> setPhoto(@RequestBody(required = false) byte[] body, @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String type, HttpServletRequest request) {
        long photoVersion = photos.setOwn(auth.requirePage("profile"), type, body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Photo saved", Map.of("photo", photoVersion));
    }

    @DeleteMapping("/api/v1/profile/photo")
    public ApiResult<Void> removePhoto(HttpServletRequest request) {
        photos.removeOwn(auth.requirePage("profile"), RequestMeta.of(request).ipAddress());
        return ApiResult.message("Photo removed");
    }

    @GetMapping("/api/v1/profile/documents/{docId}/file")
    public ResponseEntity<byte[]> document(@PathVariable String docId) {
        CurrentUser user = auth.requirePage("profile");
        EmployeeOnboardingService.FileOut file = onboarding.document(user.id().toString(), docId);
        if (file == null) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.NOT_FOUND, "File not found.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
}
