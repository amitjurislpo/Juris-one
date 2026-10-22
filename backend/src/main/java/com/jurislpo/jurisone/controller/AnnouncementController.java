package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Announcements: everyone reads the active ones; HR / the owner ({@code announcements.manage}) post, edit and unpublish them. */
@RestController
@RequestMapping("/api/announcements")
public class AnnouncementController {

    public record StatusBody(String status) {}

    private final Auth auth;
    private final AnnouncementService announcements;

    public AnnouncementController(Auth auth, AnnouncementService announcements) {
        this.auth = auth;
        this.announcements = announcements;
    }

    private CurrentUser manager() {
        return auth.assertPerm(Permissions.ANNOUNCEMENTS_MANAGE);
    }

    /** The active announcements for the signed-in user; {@code ?scope=all} (managers) lists everything ever posted. */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(name = "scope", required = false) String scope,
                                                     @RequestParam(name = "limit", required = false) Integer limit) {
        if ("all".equals(scope)) {
            manager();
            return ApiResult.success(announcements.all());
        }
        return ApiResult.success(announcements.active(auth.assertPerm(), limit));
    }

    /** {@code {title, body, audience?, pinned?, expiresOn?}} — published at once. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody AnnouncementService.AnnouncementInput body, HttpServletRequest request) {
        String id = announcements.create(manager(), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Announcement published", Map.of("id", id));
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody AnnouncementService.AnnouncementInput body, HttpServletRequest request) {
        announcements.update(manager(), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Announcement saved");
    }

    /** {@code {"status": "PUBLISHED" | "UNPUBLISHED"}} */
    @PutMapping("/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody StatusBody body, HttpServletRequest request) {
        return ApiResult.message(announcements.setStatus(manager(), id, body.status(), RequestMeta.of(request).ipAddress()));
    }
}
