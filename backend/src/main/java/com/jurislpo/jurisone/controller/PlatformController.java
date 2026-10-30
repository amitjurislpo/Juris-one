package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.BusinessAdminService;
import com.jurislpo.jurisone.service.ModuleGrantService;
import com.jurislpo.jurisone.service.JurisTrackRosterService;
import com.jurislpo.jurisone.service.JurisTrackRosterTrigger;
import com.jurislpo.jurisone.service.RoleTemplateService;
import com.jurislpo.jurisone.service.SubscriptionService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.service.AuditService;
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
@RequestMapping("/api/v1/platform")
public class PlatformController {

    public record StatusBody(String status) {}

    public record SlugBody(String slug) {}

    public record AccessBody(Boolean enabled) {}

    public record TemplateKeyBody(String templateKey) {}

    private final Auth auth;
    private final BusinessAdminService businesses;
    private final SubscriptionService subscriptions;
    private final ModuleGrantService grants;
    private final AuditService audit;
    private final RoleTemplateService roleTemplates;
    private final JurisTrackRosterService roster;
    private final JurisTrackRosterTrigger rosterTrigger;

    public PlatformController(Auth auth, BusinessAdminService businesses, SubscriptionService subscriptions, ModuleGrantService grants, AuditService audit,
                              RoleTemplateService roleTemplates, JurisTrackRosterService roster, JurisTrackRosterTrigger rosterTrigger) {
        this.roster = roster;
        this.rosterTrigger = rosterTrigger;
        this.roleTemplates = roleTemplates;
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
    private static <T> T asPlatform(java.util.function.Supplier<T> work) {
        return com.jurislpo.jurisone.tenancy.TenantContext.call(null, work);
    }

    // businesses
    @GetMapping("/businesses")
    public ApiResult<List<BusinessAdminService.BusinessRow>> list() {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(businesses.list());
        });
    }

    /** {@code {business:{...}, subscription?:{planId, startsOn?, endsOn?, price?, notes?}, owner?:{name, email}, roles?:["hre", ...]}} — mandatory roles are always added. */
    @PostMapping("/businesses")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody BusinessAdminService.CreateInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            Map<String, String> ids = businesses.create(admin(), body, RequestMeta.of(request).ipAddress());
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.common.Check.uuid(ids.get("id")));
            return ApiResult.success("Business created", ids);
        });
    }

    @GetMapping("/businesses/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(businesses.detail(id));
        });
    }

    @PutMapping("/businesses/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody BusinessAdminService.ProfileInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            businesses.update(admin(), id, body, RequestMeta.of(request).ipAddress());
            return ApiResult.message("Business details saved");
        });
    }

    /** {@code {"status": "ACTIVE" | "SUSPENDED"}} */
    @PutMapping("/businesses/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody StatusBody body, HttpServletRequest request) {
        return asPlatform(() -> {
            return ApiResult.message(businesses.setStatus(admin(), id, body.status(), RequestMeta.of(request).ipAddress()));
        });
    }

    /** {@code {"slug": "<new short name>"}}: the business's web address changes; the old one is retired forever and everybody of the business is signed out. */
    @PostMapping("/businesses/{businessId}/slug-changes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> changeSlug(@PathVariable String businessId, @RequestBody SlugBody body, HttpServletRequest request) {
        return asPlatform(() -> {
            businesses.changeSlug(admin(), businessId, body.slug(), RequestMeta.of(request).ipAddress());
            return ApiResult.message("Web address changed; everybody of this business has been signed out");
        });
    }

    /** The business's retired web addresses, newest first. */
    @GetMapping("/businesses/{businessId}/slug-changes")
    public ApiResult<List<BusinessAdminService.SlugChange>> slugChanges(@PathVariable String businessId) {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(businesses.slugHistory(businessId));
        });
    }

    // roles of a business (from the role templates)
    /** {@code {templateKey}} — adds the role to the business. */
    @PostMapping("/businesses/{id}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addRole(@PathVariable String id, @RequestBody TemplateKeyBody body, HttpServletRequest request) {
        return asPlatform(() -> ApiResult.success("Role added", businesses.addRole(admin(), id, body.templateKey(), RequestMeta.of(request).ipAddress())));
    }

    @DeleteMapping("/businesses/{id}/roles/{roleId}")
    public ApiResult<Void> removeRole(@PathVariable String id, @PathVariable String roleId, HttpServletRequest request) {
        return asPlatform(() -> {
            businesses.removeRole(admin(), id, roleId, RequestMeta.of(request).ipAddress());
            return ApiResult.message("Role removed");
        });
    }

    /** Copies the template's current permissions over the business's role. */
    @PostMapping("/businesses/{id}/roles/{roleId}/template-syncs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> syncRole(@PathVariable String id, @PathVariable String roleId, HttpServletRequest request) {
        return asPlatform(() -> ApiResult.message(businesses.syncRole(admin(), id, roleId, RequestMeta.of(request).ipAddress())));
    }

    // role templates
    @GetMapping("/role-templates")
    public ApiResult<Map<String, Object>> roleTemplates() {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(roleTemplates.list());
        });
    }

    /** {@code {name, permissions[], assignable[]}} — the key is made from the name. */
    @PostMapping("/role-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> createRoleTemplate(@RequestBody RoleTemplateService.TemplateInput body, HttpServletRequest request) {
        return asPlatform(() -> ApiResult.success("Role template created", roleTemplates.create(admin(), body, RequestMeta.of(request).ipAddress())));
    }

    /** {@code {name, permissions[], assignable[], active}} — businesses that already have the role keep their copy until it is synced. */
    @PutMapping("/role-templates/{key}")
    public ApiResult<Void> updateRoleTemplate(@PathVariable String key, @RequestBody RoleTemplateService.TemplateInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            roleTemplates.update(admin(), key, body, RequestMeta.of(request).ipAddress());
            return ApiResult.message("Role template saved");
        });
    }

    @DeleteMapping("/role-templates/{key}")
    public ApiResult<Void> deleteRoleTemplate(@PathVariable String key, HttpServletRequest request) {
        return asPlatform(() -> {
            roleTemplates.delete(admin(), key, RequestMeta.of(request).ipAddress());
            return ApiResult.message("Role template deleted");
        });
    }

    // sub admins
    /** {@code {name, email}} — the sub admin signs in with the code emailed to them. */
    @PostMapping("/businesses/{id}/owners")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addOwner(@PathVariable String id, @RequestBody BusinessAdminService.OwnerInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            Map<String, String> owner = businesses.addOwner(admin(), id, body, RequestMeta.of(request).ipAddress());
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.common.Check.uuid(id));
            return ApiResult.success("Sub admin added as " + owner.get("code"), owner);
        });
    }

    @PutMapping("/businesses/{id}/owners/{ownerId}/access")
    public ApiResult<Void> ownerAccess(@PathVariable String id, @PathVariable String ownerId, @RequestBody AccessBody body, HttpServletRequest request) {
        return asPlatform(() -> {
            String message = businesses.setOwnerAccess(admin(), id, ownerId, body.enabled(), RequestMeta.of(request).ipAddress());
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.common.Check.uuid(id));
            return ApiResult.message(message);
        });
    }

    /**
     * Sends the business's roster (shifts and employees) to Juris Track now: 201 with Juris Track's summary, 502 when Juris Track is unreachable or refuses it,
     * 409 when the business has no Juris Track, 404 for an unknown business. Audited with the counts.
     */
    @PostMapping("/businesses/{id}/juris-track-syncs")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> syncJurisTrack(@PathVariable String id, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            Map<String, Object> summary = roster.syncBusiness(com.jurislpo.jurisone.common.Check.uuid(id));
            audit.log(user.id(), "juristrack.roster_synced", "business", id, null, summary, RequestMeta.of(request).ipAddress());
            return ApiResult.success("Roster sent to Juris Track", summary);
        });
    }

    // subscriptions
    @PostMapping("/businesses/{id}/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addSubscription(@PathVariable String id, @RequestBody SubscriptionService.SubscriptionInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            java.util.UUID business = com.jurislpo.jurisone.common.Check.uuid(id);
            String subscriptionId = subscriptions.addSubscription(business, body, user.id());
            rosterTrigger.rosterChanged(business); // a plan with Juris Track: its roster goes now
            audit.log(user.id(), "subscription.add", "business_subscription", subscriptionId, null, Map.of("business", id, "plan", String.valueOf(body.planId())), RequestMeta.of(request).ipAddress());
            return ApiResult.success("Subscription added", Map.of("id", subscriptionId));
        });
    }

    /** Change dates / notes, or {@code "status": "CANCELLED"}. */
    @PutMapping("/subscriptions/{id}")
    public ApiResult<Void> updateSubscription(@PathVariable String id, @RequestBody SubscriptionService.SubscriptionUpdate body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            subscriptions.updateSubscription(id, body);
            audit.log(user.id(), "subscription.update", "business_subscription", id, null, Map.of("status", String.valueOf(body.status())), RequestMeta.of(request).ipAddress());
            return ApiResult.message("CANCELLED".equals(body.status()) ? "Subscription cancelled" : "Subscription updated");
        });
    }

    // modules granted on their own (Juris Track: complimentary or paid)
    @GetMapping("/businesses/{id}/modules")
    public ApiResult<List<ModuleGrantService.Grant>> modules(@PathVariable String id) {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(grants.list(com.jurislpo.jurisone.common.Check.uuid(id)));
        });
    }

    /** {@code {module?: "JURIS_TRACK", source: "COMPLIMENTARY" | "PAID", price? (₹, paid), startsOn?, endsOn? (required when paid), notes?}} */
    @PostMapping("/businesses/{id}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> grantModule(@PathVariable String id, @RequestBody ModuleGrantService.GrantInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            String grantId = grants.grant(com.jurislpo.jurisone.common.Check.uuid(id), body, user.id());
            rosterTrigger.rosterChanged(com.jurislpo.jurisone.common.Check.uuid(id));
            audit.log(user.id(), "module.grant", "business_module", grantId, null, Map.of("business", id, "source", String.valueOf(body.source())), RequestMeta.of(request).ipAddress());
            return ApiResult.success("Juris Track granted", Map.of("id", grantId));
        });
    }

    @PutMapping("/modules/{id}")
    public ApiResult<Void> updateModule(@PathVariable String id, @RequestBody ModuleGrantService.GrantInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            rosterTrigger.rosterChanged(grants.update(id, body));
            audit.log(user.id(), "module.update", "business_module", id, null, Map.of("source", String.valueOf(body.source())), RequestMeta.of(request).ipAddress());
            return ApiResult.message("Grant saved");
        });
    }

    /** Revokes the grant at once (the record is kept). */
    @DeleteMapping("/modules/{id}")
    public ApiResult<Void> revokeModule(@PathVariable String id, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            grants.revoke(id);
            audit.log(user.id(), "module.revoke", "business_module", id, null, Map.of(), RequestMeta.of(request).ipAddress());
            return ApiResult.message("Grant revoked");
        });
    }

    // plans
    @GetMapping("/plans")
    public ApiResult<List<SubscriptionService.Plan>> plans() {
        return asPlatform(() -> {
            admin();
            return ApiResult.success(subscriptions.plans());
        });
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> createPlan(@RequestBody SubscriptionService.PlanInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            String id = subscriptions.createPlan(body);
            audit.log(user.id(), "plan.create", "subscription_plan", id, null, Map.of("name", String.valueOf(body.name())), RequestMeta.of(request).ipAddress());
            return ApiResult.success("Plan created", Map.of("id", id));
        });
    }

    @PutMapping("/plans/{id}")
    public ApiResult<Void> updatePlan(@PathVariable String id, @RequestBody SubscriptionService.PlanInput body, HttpServletRequest request) {
        return asPlatform(() -> {
            CurrentUser user = admin();
            subscriptions.updatePlan(id, body);
            audit.log(user.id(), "plan.update", "subscription_plan", id, null, Map.of("name", String.valueOf(body.name())), RequestMeta.of(request).ipAddress());
            return ApiResult.message("Plan saved");
        });
    }
}
