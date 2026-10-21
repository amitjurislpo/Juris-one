package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AuditService;
import com.jurislpo.jurisone.service.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    @PostMapping("/api/audit/page-views")
    public ApiResult<Void> pageView(@RequestBody PageView body, HttpServletRequest req) {
        CurrentUser u = auth.assertPerm();
        String path = body == null || body.path() == null ? "" : body.path().trim();
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        if (!path.startsWith("/") || path.length() > 200 || path.chars().anyMatch(Character::isISOControl)) {
            throw new com.jurislpo.jurisone.exception.UserException(org.springframework.http.HttpStatus.BAD_REQUEST, "Not a valid page path.");
        }
        if (limiter.rateLimit("page-views:" + u.sessionId(), MAX_PER_MINUTE, 60_000L).limited()) return ApiResult.message("ok");
        audit.log(u.id(), "page.view", "page", path, RequestMeta.of(req).ip());
        return ApiResult.message("ok");
    }
}
