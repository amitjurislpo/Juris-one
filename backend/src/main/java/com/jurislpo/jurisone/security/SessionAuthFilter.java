package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.crypto.Tokens;
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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from the existing {@code jo_session} cookie (src/lib/auth.ts getCurrentUser): the token's
 * sha256 must be an unrevoked, unexpired session of an employee with portal access, not exited, with a user row.
 * Permissions are read on every request so role edits apply immediately. {@code last_seen_at} is touched at most
 * every 5 minutes.
 */
@Component
public class SessionAuthFilter extends OncePerRequestFilter {

    public static final String COOKIE = "jo_session";
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(SessionAuthFilter.class);

    private static final String USER_SQL = """
        SELECT s.id AS sid, s.expires_at, s.revoked_at, s.last_seen_at,
               e.id, e.code, e.full_name, e.email, e.designation, e.role_id, e.portal_access, e.status::text AS status,
               e.region::text AS region, e.time_zone, u.is_super_admin, u.employee_id AS user_id, r.key AS role_key, r.name AS role_name,
               EXISTS (SELECT 1 FROM employees d WHERE d.reporting_manager_id = e.id AND d.status <> 'EXITED') AS has_reports
        FROM sessions s
        JOIN employees e ON e.id = s.employee_id
        JOIN roles r ON r.id = e.role_id
        LEFT JOIN users u ON u.employee_id = e.id
        WHERE s.id = ?""";

    private final JdbcTemplate jdbc;

    public SessionAuthFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String token = cookieValue(req);
        if (token != null && !token.isEmpty()) {
            CurrentUser u = load(Tokens.sha256(token));
            if (u != null) {
                List<SimpleGrantedAuthority> auth = u.perms().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, auth));
            }
        }
        chain.doFilter(req, res);
    }

    /** The raw jo_session token, or null. */
    public static String cookieValue(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    CurrentUser load(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(USER_SQL, sessionId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        Instant now = Instant.now();
        if (r.get("revoked_at") != null || !((Timestamp) r.get("expires_at")).toInstant().isAfter(now)) return null;
        if (!Boolean.TRUE.equals(r.get("portal_access")) || "EXITED".equals(r.get("status")) || r.get("user_id") == null) return null;

        Instant lastSeen = ((Timestamp) r.get("last_seen_at")).toInstant();
        if (Duration.between(lastSeen, now).compareTo(TOUCH_EVERY) >= 0) {
            try {
                jdbc.update("UPDATE sessions SET last_seen_at = now() WHERE id = ?", sessionId);
            } catch (RuntimeException e) {
                log.debug("session touch failed", e); // ignored, like the Node app
            }
        }
        UUID roleId = (UUID) r.get("role_id");
        List<String> perms = jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId);
        return new CurrentUser(
                (UUID) r.get("id"), (String) r.get("code"), (String) r.get("full_name"), (String) r.get("email"), (String) r.get("designation"),
                roleId, (String) r.get("role_key"), (String) r.get("role_name"), java.util.Set.copyOf(new HashSet<>(perms)),
                Boolean.TRUE.equals(r.get("is_super_admin")), (String) r.get("region"), (String) r.get("time_zone"),
                Boolean.TRUE.equals(r.get("has_reports")), sessionId);
    }
}
