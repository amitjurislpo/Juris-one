package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbc;
    private final BusinessSetupService setup;
    private final String name;

    public PlatformCompanyService(JdbcTemplate jdbc, BusinessSetupService setup, @Value("${PLATFORM_COMPANY_NAME:${APP_NAME:JurisOne}}") String name) {
        this.jdbc = jdbc;
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
            List<UUID> found = jdbc.queryForList("SELECT id FROM businesses WHERE is_platform", UUID.class);
            UUID business;
            if (found.isEmpty()) {
                String code = jdbc.queryForList("SELECT 1 FROM businesses WHERE code = 'jurisone-platform'").isEmpty() ? "jurisone-platform"
                        : "jurisone-platform-" + UUID.randomUUID().toString().substring(0, 6);
                business = setup.create(new BusinessSetupService.NewBusiness(name, null, code, "JO", "Asia/Kolkata", null, null, null, null), null);
                jdbc.update("UPDATE businesses SET is_platform = true WHERE id = ?", business);
                log.info("Platform company \"{}\" created", name);
            } else {
                business = found.get(0);
            }
            // the super admin role of the platform company (locked; can assign every role there except owner)
            List<UUID> roles = jdbc.queryForList("SELECT id FROM roles WHERE business_id = ? AND key = ?", UUID.class, business, SUPER_ADMIN);
            UUID role = roles.isEmpty() ? UUID.randomUUID() : roles.get(0);
            if (roles.isEmpty()) {
                jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked, business_id) VALUES (?, ?, 'Super admin', true, true, ?)", role, SUPER_ADMIN, business);
            }
            for (String p : superAdminPerms()) {
                jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?) ON CONFLICT DO NOTHING", role, p);
            }
            jdbc.update("""
                INSERT INTO role_assignable_roles (role_id, assignable_role_id)
                SELECT ?, r.id FROM roles r WHERE r.business_id = ? AND r.key NOT IN ('owner', ?) ON CONFLICT DO NOTHING""", role, business, SUPER_ADMIN);
            int moved = jdbc.update("""
                UPDATE employees e SET business_id = ?, role_id = ?, updated_at = now() FROM users u
                WHERE u.employee_id = e.id AND u.is_super_admin AND (e.business_id IS DISTINCT FROM ? OR e.role_id <> ?)""", business, role, business, role);
            if (moved > 0) log.info("{} super admin(s) placed in the platform company", moved);
            return business;
        });
    }

    /** Whether this is the platform company. */
    public boolean isPlatform(UUID businessId) {
        return businessId != null && Boolean.TRUE.equals(TenantContext.call(null,
                () -> jdbc.queryForObject("SELECT is_platform FROM businesses WHERE id = ?", Boolean.class, businessId)));
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
