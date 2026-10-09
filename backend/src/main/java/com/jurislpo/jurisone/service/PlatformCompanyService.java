package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.repository.PlatformCompanyRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * The platform company: the JurisOne operator's own business. The super admins belong to it (role {@code super_admin}:
 * the platform console plus everything a business admin does there), so its staff are managed with the normal pages;
 * only the super admins and that company's own HR / Finance see them. It needs no subscription and isn't listed among
 * the customers. {@link #ensure()} is idempotent: it runs at startup and from the seed.
 */
@Service
public class PlatformCompanyService {

    private static final Logger log = LoggerFactory.getLogger(PlatformCompanyService.class);
    public static final String SUPER_ADMIN = "super_admin";

    private final PlatformCompanyRepository repo;
    private final BusinessSetupService setup;
    private final String name;

    public PlatformCompanyService(PlatformCompanyRepository repo, BusinessSetupService setup, @Value("${PLATFORM_COMPANY_NAME:${APP_NAME:JurisOne}}") String name) {
        this.repo = repo;
        this.setup = setup;
        this.name = name == null || name.isBlank() ? "JurisOne" : name.trim();
    }

    /** The super admin's permissions inside the platform company: the console, and a business admin's (no attendance of their own). */
    static List<String> superAdminPerms() {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(Permissions.PLATFORM_MANAGE),
                Permissions.KEYS.stream().filter(p -> !Permissions.SELF.equals(p))).toList();
    }

    /** Creates the platform company if needed and puts every super admin in it. Returns its id. */
    public UUID ensure() {
        return TenantContext.call(null, () -> {
            List<UUID> found = repo.findPlatformIds();
            UUID business;
            if (found.isEmpty()) {
                String code = !repo.defaultCodeTaken() ? "jurisone-platform"
                        : "jurisone-platform-" + UUID.randomUUID().toString().substring(0, 6);
                business = setup.create(new BusinessSetupService.NewBusiness(name, null, code, "JO", "Asia/Kolkata", null, null, null, null), null);
                repo.markPlatform(business);
                log.info("Platform company \"{}\" created", name);
            } else {
                business = found.get(0);
            }
            // the super admin role of the platform company (locked; can assign every role there except owner)
            List<UUID> roles = repo.findRoleIds(business, SUPER_ADMIN);
            UUID role = roles.isEmpty() ? UUID.randomUUID() : roles.get(0);
            if (roles.isEmpty()) {
                repo.insertSuperAdminRole(role, SUPER_ADMIN, business);
            }
            for (String p : superAdminPerms()) {
                repo.grantPermission(role, p);
            }
            repo.grantAssignableRoles(role, business, SUPER_ADMIN);
            int moved = repo.placeSuperAdmins(business, role);
            if (moved > 0) log.info("{} super admin(s) placed in the platform company", moved);
            return business;
        });
    }

    /** Whether this is the platform company. */
    public boolean isPlatform(UUID businessId) {
        return businessId != null && Boolean.TRUE.equals(TenantContext.call(null,
                () -> repo.isPlatform(businessId)));
    }

    /** Keeps the platform company in place on every start of the API. */
    @Component
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class OnStartup {
        private final PlatformCompanyService platform;

        OnStartup(PlatformCompanyService platform) {
            this.platform = platform;
        }

        @EventListener(ApplicationReadyEvent.class)
        void ready() {
            platform.ensure();
        }
    }
}
