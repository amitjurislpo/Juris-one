package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.auth.SessionService;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final String USER_SQL = """
        SELECT s.id AS sid, s.employee_id AS session_employee, s.expires_at, s.revoked_at, s.last_seen_at,
               e.id, e.business_id, e.code, e.full_name, e.email, e.designation, e.role_id, e.portal_access, e.status::text AS status,
               e.region::text AS region, e.time_zone, u.is_super_admin, u.employee_id AS user_id, r.key AS role_key, r.name AS role_name,
               EXISTS (SELECT 1 FROM employees d WHERE d.reporting_manager_id = e.id AND d.status <> 'EXITED') AS has_reports,
               (SELECT b.status FROM businesses b WHERE b.id = e.business_id) AS business_status
        FROM sessions s
        JOIN employees e ON e.id = s.employee_id
        JOIN roles r ON r.id = e.role_id
        LEFT JOIN users u ON u.employee_id = e.id
        WHERE s.id = ?""";

    private final JdbcTemplate jdbc;
    private final JwtService jwt;

    public JwtAuthFilter(JdbcTemplate jdbc, JwtService jwt) {
        this.jdbc = jdbc;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        TenantContext.clear(); // the lookup below is a system query
        try {
            String token = cookie(req, SessionService.ACCESS_COOKIE);
            if (token != null && !token.isEmpty()) {
                try {
                    JwtService.AccessClaims claims = jwt.verify(token);
                    CurrentUser u = load(claims);
                    if (u != null) {
                        List<SimpleGrantedAuthority> auth = u.perms().stream().map(SimpleGrantedAuthority::new).toList();
                        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auth));
                        TenantContext.set(u.businessId()); // null for the super admin: platform requests run in system mode
                    }
                } catch (JwtService.ExpiredException e) {
                    req.setAttribute(EXPIRED_ATTR, Boolean.TRUE);
                } catch (JwtException | IllegalArgumentException e) {
                    log.debug("rejected access token: {}", e.getMessage()); // forged / malformed: treated as signed out
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }

    /** A cookie's value, or null. */
    public static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }

    CurrentUser load(JwtService.AccessClaims claims) {
        List<Map<String, Object>> rows = jdbc.queryForList(USER_SQL, claims.sessionId());
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Instant now = Instant.now();
        if (!claims.employeeId().equals(r.get("session_employee"))) return null;
        if (r.get("revoked_at") != null || !((Timestamp) r.get("expires_at")).toInstant().isAfter(now)) return null;
        if (!Boolean.TRUE.equals(r.get("portal_access")) || "EXITED".equals(r.get("status")) || r.get("user_id") == null) return null;
        if ("SUSPENDED".equals(r.get("business_status"))) return null; // a suspended business is signed out everywhere

        Instant lastSeen = ((Timestamp) r.get("last_seen_at")).toInstant();
        if (Duration.between(lastSeen, now).compareTo(TOUCH_EVERY) >= 0) {
            try {
                jdbc.update("UPDATE sessions SET last_seen_at = now() WHERE id = ?", claims.sessionId());
            } catch (RuntimeException e) {
                log.debug("session touch failed", e);
            }
        }
        UUID roleId = (UUID) r.get("role_id");
        List<String> perms = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId);
        return new CurrentUser(
                (UUID) r.get("id"), (String) r.get("code"), (String) r.get("full_name"), (String) r.get("email"), (String) r.get("designation"),
                roleId, (String) r.get("role_key"), (String) r.get("role_name"), java.util.Set.copyOf(new HashSet<>(perms)),
                Boolean.TRUE.equals(r.get("is_super_admin")), (String) r.get("region"), (String) r.get("time_zone"),
                Boolean.TRUE.equals(r.get("has_reports")), claims.sessionId(), (UUID) r.get("business_id"));
    }
}
