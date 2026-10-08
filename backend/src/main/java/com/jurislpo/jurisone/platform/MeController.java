package com.jurislpo.jurisone.platform;

import com.jurislpo.jurisone.business.SubscriptionService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user and the app shell. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final Auth auth;
    private final ShellService shell;
    private final SubscriptionService subscriptions;
    private final com.jurislpo.jurisone.policy.PolicyService policies;

    public MeController(Auth auth, ShellService shell, SubscriptionService subscriptions, com.jurislpo.jurisone.policy.PolicyService policies) {
        this.auth = auth;
        this.shell = shell;
        this.subscriptions = subscriptions;
        this.policies = policies;
    }

    /**
     * The current user (permissions, region, time zone, direct reports) and their landing page. 401 when signed out.
     * {@code subscriptionActive}: whether the user's business may use JurisOne today (null for the super admin).
     */
    @GetMapping("/me")
    public ApiResult<MeResponse> me() {
        CurrentUser u = auth.assertPerm();
        Boolean active = u.businessId() == null ? null : subscriptions.isActive(u.businessId());
        return ApiResult.success(new MeResponse(u, u.landingPath(), active, java.util.List.copyOf(new java.util.TreeSet<>(subscriptions.activeModules(u.businessId()))),
                policies.pendingCount(u)));
    }

    /**
     * {@code modules}: modules the business can use today (empty for the super admin or without an active subscription).
     * {@code pendingPolicies}: published policies the user still has to accept before using JurisOne.
     */
    public record MeResponse(CurrentUser user, String landing, Boolean subscriptionActive, java.util.List<String> modules, int pendingPolicies) {}

    @GetMapping("/shell")
    public ApiResult<ShellService.Shell> shell() {
        return ApiResult.success(shell.shell(auth.assertPerm()));
    }
}
