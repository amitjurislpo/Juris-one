package com.jurislpo.jurisone.platform;

import com.jurislpo.jurisone.business.SubscriptionService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user, the app shell, and the bell. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final Auth auth;
    private final ShellService shell;
    private final SubscriptionService subscriptions;

    public MeController(Auth auth, ShellService shell, SubscriptionService subscriptions) {
        this.auth = auth;
        this.shell = shell;
        this.subscriptions = subscriptions;
    }

    /**
     * The current user (permissions, region, time zone, direct reports) and their landing page. 401 when signed out.
     * {@code subscriptionActive}: whether the user's business may use JurisOne today (null for the super admin).
     */
    @GetMapping("/me")
    public ApiResult<MeResponse> me() {
        CurrentUser u = auth.assertPerm();
        Boolean active = u.businessId() == null ? null : subscriptions.isActive(u.businessId());
        return ApiResult.success(new MeResponse(u, u.landingPath(), active, java.util.List.copyOf(new java.util.TreeSet<>(subscriptions.activeModules(u.businessId())))));
    }

    /** {@code modules}: modules the business can use today (empty for the super admin or without an active subscription). */
    public record MeResponse(CurrentUser user, String landing, Boolean subscriptionActive, java.util.List<String> modules) {}

    @GetMapping("/shell")
    public ApiResult<ShellService.Shell> shell() {
        return ApiResult.success(shell.shell(auth.assertPerm()));
    }

    public record NotificationsPatch(Boolean read) {}

    /** Mark all of the signed-in user's notifications as read ({@code {"read": true}}). */
    @PatchMapping("/notifications")
    public ApiResult<Void> markRead(@RequestBody NotificationsPatch body) {
        CurrentUser u = auth.assertPerm();
        if (!Boolean.TRUE.equals(body.read())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected true");
        shell.markRead(u);
        return ApiResult.success();
    }
}
