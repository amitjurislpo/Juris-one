package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.SubscriptionService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Juris Track. Only businesses whose plan includes the module, or that were granted it, can reach it; for everyone
 * else it doesn't exist (404). The module itself is still to come — the page says "Coming soon".
 */
@RestController
public class JurisTrackController {

    private final Auth auth;
    private final SubscriptionService subscriptions;

    public JurisTrackController(Auth auth, SubscriptionService subscriptions) {
        this.auth = auth;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/api/juris-track")
    public ApiResult<Map<String, Object>> get() {
        CurrentUser user = auth.requirePage("juris-track");
        if (!subscriptions.activeModules(user.businessId()).contains(Nav.JURIS_TRACK)) throw new UserException(HttpStatus.NOT_FOUND, "Not found.");
        return ApiResult.success(Map.of("module", Nav.JURIS_TRACK, "status", "COMING_SOON"));
    }
}
