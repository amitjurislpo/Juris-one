package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AuditService;
import com.jurislpo.jurisone.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/audit/page-views: the web app reports each page the signed-in user opens, so the trail shows where people went. */
@RestController
public class AuditController {

    public record PageView(String path) {}

    static final int MAX_PER_MINUTE = 120;

    private final Auth auth;
    private final AuditService audit;
    private final RateLimiter limiter;

    public AuditController(Auth auth, AuditService audit, RateLimiter limiter) {
        this.auth = auth;
        this.audit = audit;
        this.limiter = limiter;
    }

    /** Any signed-in user. Path must start with "/" (the query is dropped, 200 characters at most). Over the per-session rate it is quietly ignored. */
    @PostMapping("/api/v1/audit/page-views")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> pageView(@RequestBody PageView body, HttpServletRequest request) {
        CurrentUser user = auth.assertPerm();
        String path = body == null || body.path() == null ? "" : body.path().trim();
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) path = path.substring(0, queryStart);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        if (!path.startsWith("/") || path.length() > 200 || path.chars().anyMatch(Character::isISOControl)) {
            throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.BAD_REQUEST, "Not a valid page path.");
        }
        if (limiter.rateLimit("page-views:" + user.sessionId(), MAX_PER_MINUTE, 60_000L).limited()) return ApiResult.message("ok");
        audit.log(user.id(), "page.view", "page", path, RequestMeta.of(request).ipAddress());
        return ApiResult.message("ok");
    }
}
