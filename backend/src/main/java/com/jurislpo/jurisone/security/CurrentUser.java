package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.engine.Nav;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/** The signed-in user, re-validated against the database on every request. */
public record CurrentUser(
        UUID id, String code, String name, String email, String designation, UUID roleId, String roleKey, String roleName,
        Set<String> perms, boolean isSuperAdmin, String region, String timeZone, boolean hasReports, String sessionId, UUID businessId) {

    /** Any one of {@code perms}. */
    public boolean can(String... perms) {
        return Arrays.stream(perms).anyMatch(this.perms::contains);
    }

    public boolean navAllowed(String navId) {
        return Nav.allowed(navId, perms, hasReports);
    }

    /** Platform console for the super admin, Home for anyone with self, Overview for the business admin, else the first allowed page. */
    public String landingPath() {
        if (perms.contains("platform.manage")) return "/platform";
        if (perms.contains("self")) return "/home";
        if (perms.contains("roles.manage")) return "/overview";
        return Nav.visible(perms, hasReports).stream().findFirst().map(i -> "/" + i.id()).orElse("/no-access");
    }
}
