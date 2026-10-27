package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.OnboardingReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HR's decision on a submitted onboarding (permission {@code people.add}): an approval or a rejection is created under the employee's onboarding. */
@RestController
public class OnboardingReviewController {

    private final Auth auth;
    private final OnboardingReviewService review;

    public OnboardingReviewController(Auth auth, OnboardingReviewService review) {
        this.auth = auth;
        this.review = review;
    }

    /** The body is ignored (send {@code {}}). */
    @PostMapping("/api/people/{id}/onboarding/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> approve(@PathVariable String id, HttpServletRequest request) {
        review.approve(auth.assertPerm("people.add"), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Onboarding approved");
    }

    /** {@code {"comment": "...", "flags": [{"section": "ADDRESS", "comment": "..."}]}} */
    @PostMapping("/api/people/{id}/onboarding/rejections")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> reject(@PathVariable String id, @RequestBody OnboardingReviewService.RejectionInput body, HttpServletRequest request) {
        review.reject(auth.assertPerm("people.add"), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Changes requested");
    }
}
