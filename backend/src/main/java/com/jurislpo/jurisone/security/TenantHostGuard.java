package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.service.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.jurislpo.jurisone.repository.PlatformCompanyRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-business addresses, step 5: binds sign-in, the sign-in code, the access token and the refresh token to the business the request's
 * <em>real</em> {@code Host} header names ({@link TenantHostFilter} resolves it; forwarding headers are never read). With
 * {@code jurisone.tenant-host.enabled=false} {@link #bindingOf} returns {@code null} and every caller behaves exactly as before.
 *
 * <p>The rule: a business host admits only people of that business; the platform host ({@code admin.<base>}) admits only platform users
 * (no business); an unknown host admits nobody. Every refusal is written to the audit trail ({@code auth.host_refused}) and counted in
 * {@code jurisone.tenant_host.refused}; the audit entry holds the reason and the kind of host (plus the slug of a known business host),
 * never the raw host, a token or an unmasked email.
 */
@Component
public class TenantHostGuard {

    public static final String AUDIT_ACTION = "auth.host_refused";
    public static final String COUNTER_NAME = "jurisone.tenant_host.refused";
    /** Value of the access token's {@code bid} claim for a platform user (no business). */
    public static final String PLATFORM_BINDING = "platform";

    private static final Logger log = LoggerFactory.getLogger(TenantHostGuard.class);

    private final boolean isEnabled;
    private final AuditService audit;
    private final MeterRegistry meters;

    private static final long PLATFORM_IDS_TTL_MILLIS = 60_000L;

    private final PlatformCompanyRepository platformCompanies;
    private volatile Set<UUID> platformBusinessIds = Set.of();
    private volatile long platformBusinessIdsReadAt;

    public TenantHostGuard(JurisOneProperties props, AuditService audit, MeterRegistry meters, PlatformCompanyRepository platformCompanies) {
        this.isEnabled = props.tenantHost().enabled();
        this.audit = audit;
        this.meters = meters;
        this.platformCompanies = platformCompanies;
    }

    /**
     * The business a person counts as for host binding: {@code null} (a platform user, admitted on the admin host only) when {@code businessId} is the
     * platform company, where the super admins are placed; otherwise {@code businessId} itself. Binding off: always {@code businessId}.
     * A failing lookup keeps the last known answer (the platform company never changes after it is created).
     */
    public UUID bindingBusiness(UUID businessId) {
        if (!isEnabled || businessId == null) return businessId;
        long now = System.currentTimeMillis();
        if (now - platformBusinessIdsReadAt > PLATFORM_IDS_TTL_MILLIS) {
            try {
                platformBusinessIds = Set.copyOf(TenantContext.call(null, platformCompanies::findPlatformIds));
                platformBusinessIdsReadAt = now;
            } catch (RuntimeException e) {
                log.warn("could not read the platform company for host binding", e);
            }
        }
        return platformBusinessIds.contains(businessId) ? null : businessId;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * What the request's host names, or {@code null} when binding is off. When binding is on and the host could not be resolved (the filter
     * failed) the answer is {@link HostBusiness#UNKNOWN}: the guard fails closed.
     */
    public HostBusiness bindingOf(HttpServletRequest request) {
        if (!isEnabled) return null;
        return request.getAttribute(HostBusiness.REQUEST_ATTRIBUTE) instanceof HostBusiness host ? host : HostBusiness.UNKNOWN;
    }

    /** Whether a person of {@code businessId} (null = platform user) may use this host. A {@code null} host means binding is off. */
    public static boolean permits(HostBusiness host, UUID businessId) {
        if (host == null) return true;
        return switch (host.kind()) {
            case BUSINESS -> host.businessId().equals(businessId);
            case PLATFORM -> businessId == null;
            case UNKNOWN -> false;
        };
    }

    /** The {@code bid} claim a person of {@code businessId} (null = platform user) gets in an access token. */
    public static String bindingClaim(UUID businessId) {
        return businessId == null ? PLATFORM_BINDING : businessId.toString();
    }

    /** Whether an access token's {@code bid} claim fits the host. A missing claim never fits while binding is on. */
    public static boolean permitsClaim(HostBusiness host, String claim) {
        if (host == null) return true;
        if (claim == null) return false;
        return switch (host.kind()) {
            case BUSINESS -> claim.equals(host.businessId().toString());
            case PLATFORM -> PLATFORM_BINDING.equals(claim);
            case UNKNOWN -> false;
        };
    }

    /** Records a refusal: audit entry (actor may be unknown) and counter. Never throws. */
    public void refused(UUID actorId, String step, String reason, HostBusiness host, String ipAddress) {
        try {
            meters.counter(COUNTER_NAME, "step", step, "reason", reason).increment();
            Map<String, Object> details = new HashMap<>();
            details.put("step", step);
            details.put("reason", reason);
            details.put("hostKind", host == null ? "NONE" : host.kind().name());
            if (host != null && host.slug() != null) details.put("hostSlug", host.slug());
            audit.log(actorId, AUDIT_ACTION, "session", actorId == null ? null : actorId.toString(), null, details, ipAddress);
        } catch (RuntimeException e) {
            log.warn("could not record a refused host binding ({} / {})", step, reason, e);
        }
    }
}
