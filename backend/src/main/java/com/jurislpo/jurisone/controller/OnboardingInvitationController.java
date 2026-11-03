package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.OnboardingInvitationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** "Initiate onboarding" for the selected employees of the Employees list (permission {@code people.add}); creating an invitation is the action. */
@RestController
public class OnboardingInvitationController {

    private final Auth auth;
    private final OnboardingInvitationService invitations;

    public OnboardingInvitationController(Auth auth, OnboardingInvitationService invitations) {
        this.auth = auth;
        this.invitations = invitations;
    }

    /** {@code {"employeeIds": ["..."], "filledBy": "EMPLOYEE" | "HR"}}; the answer lists one outcome per employee. */
    @PostMapping("/api/v1/onboarding/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<OnboardingInvitationService.Result> initiate(@RequestBody OnboardingInvitationService.InvitationRequest body, HttpServletRequest request) {
        return ApiResult.success(invitations.initiate(auth.assertPerm("people.add"), body, RequestMeta.of(request).ipAddress()));
    }
}
