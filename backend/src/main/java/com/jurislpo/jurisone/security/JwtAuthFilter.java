package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.repository.SessionRepository;
import com.jurislpo.jurisone.service.SessionService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from the {@code jo_at} JWT access token. The signature and expiry are checked first; then
 * one lookup confirms the session ({@code sid}) is not revoked or expired and the employee still has portal access,
 * is not exited and has a user row — so disabling access or signing out takes effect on the very next request.
 * Permissions are read on every request so role edits apply immediately. {@code last_seen_at} is touched at most
 * every 5 minutes. The user's business becomes the request's {@link TenantContext} (row-level security), and is
 * always cleared when the request ends.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Request attribute set when the access token had expired (the entry point answers code TOKEN_EXPIRED). */
    public static final String EXPIRED_ATTR = "jurisone.tokenExpired";
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final SessionRepository sessions;
    private final JwtService jwt;

    public JwtAuthFilter(SessionRepository sessions, JwtService jwt) {
        this.sessions = sessions;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        TenantContext.clear(); // the lookup below is a system query
        try {
            String token = cookie(request, SessionService.ACCESS_COOKIE);
            if (token != null && !token.isEmpty()) {
                try {
                    JwtService.AccessClaims claims = jwt.verify(token);
                    CurrentUser user = load(claims);
                    if (user != null) {
                        List<SimpleGrantedAuthority> auth = user.perms().stream().map(SimpleGrantedAuthority::new).toList();
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, auth));
                        request.setAttribute(AuditTrail.USER_ATTR, user); // the audit trail reads the actor after this chain has ended
                        TenantContext.set(user.businessId()); // null for the super admin: platform requests run in system mode
                    }
                } catch (JwtService.ExpiredException e) {
                    request.setAttribute(EXPIRED_ATTR, Boolean.TRUE);
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("rejected access token: {}", e.getMessage()); // forged / malformed: treated as signed out
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** A cookie's value, or null. */
    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    CurrentUser load(JwtService.AccessClaims claims) {
        List<Map<String, Object>> rows = sessions.findSessionUser(claims.sessionId());
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Instant now = Instant.now();
        if (!claims.employeeId().equals(row.get("session_employee"))) return null;
        if (row.get("revoked_at") != null || !((Timestamp) row.get("expires_at")).toInstant().isAfter(now)) return null;
        if (!Boolean.TRUE.equals(row.get("portal_access")) || "EXITED".equals(row.get("status")) || row.get("user_id") == null) return null;
        if ("SUSPENDED".equals(row.get("business_status"))) return null; // a suspended business is signed out everywhere

        Instant lastSeen = ((Timestamp) row.get("last_seen_at")).toInstant();
        if (Duration.between(lastSeen, now).compareTo(TOUCH_EVERY) >= 0) {
            try {
                sessions.touch(claims.sessionId());
            } catch (RuntimeException e) {
                log.debug("session touch failed", e);
            }
        }
        UUID roleId = (UUID) row.get("role_id");
        List<String> perms = sessions.findPermissionKeys(roleId);
        return new CurrentUser(
                (UUID) row.get("id"), (String) row.get("code"), (String) row.get("full_name"), (String) row.get("email"), (String) row.get("designation"),
                roleId, (String) row.get("role_key"), (String) row.get("role_name"), java.util.Set.copyOf(new HashSet<>(perms)),
                Boolean.TRUE.equals(row.get("is_super_admin")), (String) row.get("region"), (String) row.get("time_zone"),
                Boolean.TRUE.equals(row.get("has_reports")), claims.sessionId(), (UUID) row.get("business_id"),
                com.jurislpo.jurisone.service.PhotoService.version(row.get("photo_updated_at")));
    }
}
