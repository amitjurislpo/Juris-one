package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The super admin's platform API: businesses, sub admins, plans and subscriptions (system mode). */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    public record StatusBody(String status) {}

    public record AccessBody(Boolean on) {}

    private final Auth auth;
    private final BusinessAdminService businesses;
    private final SubscriptionService subscriptions;
    private final ModuleGrantService grants;
    private final AuditService audit;

    public PlatformController(Auth auth, BusinessAdminService businesses, SubscriptionService subscriptions, ModuleGrantService grants, AuditService audit) {
        this.auth = auth;
        this.businesses = businesses;
        this.subscriptions = subscriptions;
        this.grants = grants;
        this.audit = audit;
    }

    private CurrentUser admin() {
        return auth.assertPerm(Permissions.PLATFORM_MANAGE);
    }

    /**
     * The super admins belong to the platform company, so their requests carry it; the console works across every
     * business, outside any of them (system mode).
     */
    private static <T> T sys(java.util.function.Supplier<T> work) {
        return com.jurislpo.jurisone.tenancy.TenantContext.call(null, work);
    }

    // businesses
    @GetMapping("/businesses")
    public ApiResult<List<BusinessAdminService.BusinessRow>> list() {
        return sys(() -> {
            admin();
            return ApiResult.success(businesses.list());
        });
    }

    /** {@code {business:{...}, subscription?:{planId, startsOn?, endsOn?, price?, notes?}, owner?:{name, email, password?}}} */
    @PostMapping("/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody BusinessAdminService.CreateInput body, HttpServletRequest req) {
        return sys(() -> {
            Map<String, String> ids = businesses.create(admin(), body, RequestMeta.of(req).ip());
            return ApiResult.success("Business created", ids);
        });
    }

    @GetMapping("/businesses/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return sys(() -> {
            admin();
            return ApiResult.success(businesses.detail(id));
        });
    }

    @PutMapping("/businesses/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody BusinessAdminService.ProfileInput body, HttpServletRequest req) {
        return sys(() -> {
            businesses.update(admin(), id, body, RequestMeta.of(req).ip());
            return ApiResult.message("Business details saved");
        });
    }

    /** {@code {"status": "ACTIVE" | "SUSPENDED"}} */
    @PutMapping("/businesses/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody StatusBody body, HttpServletRequest req) {
        return sys(() -> {
            return ApiResult.message(businesses.setStatus(admin(), id, body.status(), RequestMeta.of(req).ip()));
        });
    }

    // sub admins
    /** {@code {name, email, password?}} — without a password an invite email is sent. */
    @PostMapping("/businesses/{id}/owners")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addOwner(@PathVariable String id, @RequestBody BusinessAdminService.OwnerInput body, HttpServletRequest req) {
        return sys(() -> {
            Map<String, String> r = businesses.addOwner(admin(), id, body, RequestMeta.of(req).ip());
            return ApiResult.success("Sub admin added as " + r.get("code"), r);
        });
    }

    @PutMapping("/businesses/{id}/owners/{ownerId}/access")
    public ApiResult<Void> ownerAccess(@PathVariable String id, @PathVariable String ownerId, @RequestBody AccessBody body, HttpServletRequest req) {
        return sys(() -> {
            return ApiResult.message(businesses.setOwnerAccess(admin(), id, ownerId, body.on(), RequestMeta.of(req).ip()));
        });
    }

    // subscriptions
    @PostMapping("/businesses/{id}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addSubscription(@PathVariable String id, @RequestBody SubscriptionService.SubscriptionInput body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            java.util.UUID business = com.jurislpo.jurisone.common.Check.uuid(id);
            String sid = subscriptions.addSubscription(business, body, u.id());
            audit.log(u.id(), "subscription.add", "business_subscription", sid, null, Map.of("business", id, "plan", String.valueOf(body.planId())), RequestMeta.of(req).ip());
            return ApiResult.success("Subscription added", Map.of("id", sid));
        });
    }

    /** Change dates / notes, or {@code "status": "CANCELLED"}. */
    @PutMapping("/subscriptions/{id}")
    public ApiResult<Void> updateSubscription(@PathVariable String id, @RequestBody SubscriptionService.SubscriptionUpdate body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            subscriptions.updateSubscription(id, body);
            audit.log(u.id(), "subscription.update", "business_subscription", id, null, Map.of("status", String.valueOf(body.status())), RequestMeta.of(req).ip());
            return ApiResult.message("CANCELLED".equals(body.status()) ? "Subscription cancelled" : "Subscription updated");
        });
    }

    // modules granted on their own (Juris Track: complimentary or paid)
    @GetMapping("/businesses/{id}/modules")
    public ApiResult<List<ModuleGrantService.Grant>> modules(@PathVariable String id) {
        return sys(() -> {
            admin();
            return ApiResult.success(grants.list(com.jurislpo.jurisone.common.Check.uuid(id)));
        });
    }

    /** {@code {module?: "JURIS_TRACK", source: "COMPLIMENTARY" | "PAID", price? (₹, paid), startsOn?, endsOn? (required when paid), notes?}} */
    @PostMapping("/businesses/{id}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> grantModule(@PathVariable String id, @RequestBody ModuleGrantService.GrantInput body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            String gid = grants.grant(com.jurislpo.jurisone.common.Check.uuid(id), body, u.id());
            audit.log(u.id(), "module.grant", "business_module", gid, null, Map.of("business", id, "source", String.valueOf(body.source())), RequestMeta.of(req).ip());
            return ApiResult.success("Juris Track granted", Map.of("id", gid));
        });
    }

    @PutMapping("/modules/{id}")
    public ApiResult<Void> updateModule(@PathVariable String id, @RequestBody ModuleGrantService.GrantInput body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            grants.update(id, body);
            audit.log(u.id(), "module.update", "business_module", id, null, Map.of("source", String.valueOf(body.source())), RequestMeta.of(req).ip());
            return ApiResult.message("Grant saved");
        });
    }

    /** Revokes the grant at once (the record is kept). */
    @DeleteMapping("/modules/{id}")
    public ApiResult<Void> revokeModule(@PathVariable String id, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            grants.revoke(id);
            audit.log(u.id(), "module.revoke", "business_module", id, null, Map.of(), RequestMeta.of(req).ip());
            return ApiResult.message("Grant revoked");
        });
    }

    // plans
    @GetMapping("/plans")
    public ApiResult<List<SubscriptionService.Plan>> plans() {
        return sys(() -> {
            admin();
            return ApiResult.success(subscriptions.plans());
        });
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> createPlan(@RequestBody SubscriptionService.PlanInput body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            String id = subscriptions.createPlan(body);
            audit.log(u.id(), "plan.create", "subscription_plan", id, null, Map.of("name", String.valueOf(body.name())), RequestMeta.of(req).ip());
            return ApiResult.success("Plan created", Map.of("id", id));
        });
    }

    @PutMapping("/plans/{id}")
    public ApiResult<Void> updatePlan(@PathVariable String id, @RequestBody SubscriptionService.PlanInput body, HttpServletRequest req) {
        return sys(() -> {
            CurrentUser u = admin();
            subscriptions.updatePlan(id, body);
            audit.log(u.id(), "plan.update", "subscription_plan", id, null, Map.of("name", String.valueOf(body.name())), RequestMeta.of(req).ip());
            return ApiResult.message("Plan saved");
        });
    }
}
