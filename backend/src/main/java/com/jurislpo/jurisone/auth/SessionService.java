package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.entity.Session;
import com.jurislpo.jurisone.security.SessionAuthFilter;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sessions (src/lib/session.ts): a random token in the {@code jo_session} cookie, its sha256 in {@code sessions}. */
@Service
public class SessionService {

    static final int SHORT_SESSION_HOURS = 12; // "Keep me signed in" unchecked

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final int ttlDays;
    private final boolean secureCookie;

    public SessionService(EntityManager em, JdbcTemplate jdbc, @Value("${SESSION_TTL_DAYS:30}") int ttlDays,
                          @Value("${COOKIE_SECURE:${APP_URL:http://localhost:3005}}") String secure) {
        this.em = em;
        this.jdbc = jdbc;
        this.ttlDays = ttlDays;
        // Secure cookies whenever the app is served over https (the Node app: NODE_ENV=production), or COOKIE_SECURE=true
        this.secureCookie = "true".equalsIgnoreCase(secure) || secure.startsWith("https://");
    }

    /** Creates the session row and returns the Set-Cookie for it. */
    @Transactional
    public ResponseCookie create(UUID employeeId, boolean remember, RequestMeta meta) {
        String token = Tokens.randomToken(32);
        Duration ttl = remember ? Duration.ofDays(ttlDays) : Duration.ofHours(SHORT_SESSION_HOURS);
        Instant expiresAt = Instant.now().plus(ttl).truncatedTo(ChronoUnit.MILLIS);
        Session s = new Session();
        s.setId(Tokens.sha256(token));
        s.setEmployeeId(employeeId);
        s.setExpiresAt(expiresAt);
        s.setIp(meta.ip());
        s.setUserAgent(meta.userAgent());
        em.persist(s);
        ResponseCookie.ResponseCookieBuilder c = ResponseCookie.from(SessionAuthFilter.COOKIE, token)
                .httpOnly(true).sameSite("Lax").secure(secureCookie).path("/");
        if (remember) c.maxAge(ttl);
        return c.build();
    }

    /** Clears the cookie in the browser. */
    public ResponseCookie clearCookie() {
        return ResponseCookie.from(SessionAuthFilter.COOKIE, "").httpOnly(true).sameSite("Lax").secure(secureCookie).path("/").maxAge(0).build();
    }

    /** Ends every active session of an employee immediately (access disabled, exit, password reset). */
    @Transactional
    public void revokeAll(UUID employeeId, String reason) {
        jdbc.update("UPDATE sessions SET revoked_at = ?, revoked_reason = ? WHERE employee_id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), reason, employeeId);
    }

    /** Logout: revokes the session behind this token, if any. */
    @Transactional
    public void destroy(String token) {
        if (token == null || token.isEmpty()) return;
        jdbc.update("UPDATE sessions SET revoked_at = ?, revoked_reason = 'logout' WHERE id = ? AND revoked_at IS NULL",
                Timestamp.from(Instant.now()), Tokens.sha256(token));
    }
}
