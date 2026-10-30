package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.JurisTrackGatewayService;
import com.jurislpo.jurisone.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Juris Track. Only businesses whose plan includes the module, or that were granted it, can reach it; for everyone else it doesn't
 * exist (404). {@code GET /api/juris-track} says the module is there; everything below {@code /api/juris-track/} is the gateway:
 * the request goes on to JurisTrack as the signed-in employee and JurisTrack's answer comes back as it is.
 */
@RestController
public class JurisTrackController {

    private final Auth auth;
    private final SubscriptionService subscriptions;
    private final JurisTrackGatewayService gateway;

    public JurisTrackController(Auth auth, SubscriptionService subscriptions, JurisTrackGatewayService gateway) {
        this.auth = auth;
        this.subscriptions = subscriptions;
        this.gateway = gateway;
    }

    @GetMapping("/api/v1/juris-track")
    public ApiResult<Map<String, Object>> get() {
        requireModule();
        return ApiResult.success(Map.of("module", Nav.JURIS_TRACK, "status", "ACTIVE"));
    }

    @RequestMapping("/api/v1/juris-track/**")
    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        gateway.forward(requireModule(), request, response);
    }

    private CurrentUser requireModule() {
        CurrentUser user = auth.requirePage("juris-track");
        if (!subscriptions.activeModules(user.businessId()).contains(Nav.JURIS_TRACK)) throw new UserException(HttpStatus.NOT_FOUND, "Not found.");
        return user;
    }
}
