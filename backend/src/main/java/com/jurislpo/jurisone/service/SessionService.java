package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.repository.SessionRepository;
import com.jurislpo.jurisone.security.JwtService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signed-in devices. A {@code sessions} row is one sign-in (12 h, or {@code jurisone.security.session-ttl-days} with "keep me signed in");
 * it is the refresh-token family. The browser holds two HTTP-only cookies:
 * {@code jo_at} — the 5-minute JWT access token — and {@code jo_rt} — an opaque refresh token that rotates on every use.
 */
@Service
public class SessionService {

    public static final String ACCESS_COOKIE = "jo_at";
    public static final String REFRESH_COOKIE = "jo_rt";
    /** The cookie of the pre-JWT sign-in; cleared on sign-out so old browsers don't keep it. */
    static final String LEGACY_COOKIE = "jo_session";
    static final int SHORT_SESSION_HOURS = 12; // "Keep me signed in" unchecked

    /** Cookies to set after sign-in or refresh. */
    public record Issued(String sessionId, List<ResponseCookie> cookies) {}

    /** Why a refresh was refused. */
    public static class RefreshRefused extends RuntimeException {
        RefreshRefused(String reason) {
            super(reason);
        }
    }

    private final SessionRepository repo;
    private final JwtService jwtService;
    private final int ttlDays;
    private final boolean secureCookie;
    private final Duration reuseGrace;

    public SessionService(SessionRepository repo, JwtService jwtService, @Value("${jurisone.security.session-ttl-days:30}") int ttlDays,
                          @Value("${jurisone.security.cookie-secure:${jurisone.app-url:http://localhost:3005}}") String secure,
                          @Value("${jurisone.refresh-reuse-grace-seconds:30}") long reuseGraceSeconds) {
        this.repo = repo;
        this.jwtService = jwtService;
        this.ttlDays = ttlDays;
        this.reuseGrace = Duration.ofSeconds(reuseGraceSeconds);
        // Secure cookies whenever the app is served over https, or COOKIE_SECURE=true
        this.secureCookie = "true".equalsIgnoreCase(secure) || secure.startsWith("https://");
    }

    /** Starts a session for a successful sign-in and returns its cookies. */
    @Transactional
    public Issued create(UUID employeeId, boolean remember, RequestMeta meta) {
        String sessionId = Tokens.randomToken(24);
        Duration sessionLifetime = remember ? Duration.ofDays(ttlDays) : Duration.ofHours(SHORT_SESSION_HOURS);
        Instant expiresAt = Instant.now().plus(sessionLifetime).truncatedTo(ChronoUnit.MILLIS);
        repo.insertSession(sessionId, employeeId, expiresAt, meta.ipAddress(), meta.userAgent());
        String refresh = newRefreshToken(sessionId, expiresAt, meta);
        return new Issued(sessionId, cookies(employeeId, sessionId, refresh, remember ? sessionLifetime : null));
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token. A token that was already used means
     * it was copied: the whole session is revoked — unless it was used only moments ago ({@code reuseGrace}), which
     * is the browser sending several requests at once when the access token ran out.
     */
    @Transactional(noRollbackFor = RefreshRefused.class) // a reused token must stay revoked even though the call fails
    public Issued refresh(String refreshToken, RequestMeta meta) {
        if (refreshToken == null || refreshToken.isEmpty()) throw new RefreshRefused("missing");
        List<Map<String, Object>> rows = repo.findRefreshTokenForUpdate(Tokens.sha256(refreshToken));
        if (rows.isEmpty()) throw new RefreshRefused("unknown");
        Map<String, Object> row = rows.get(0);
        String sessionId = (String) row.get("session_id");
        Instant now = Instant.now();
        if (row.get("revoked_at") != null) throw new RefreshRefused("revoked");
        if (row.get("used_at") != null) {
            Instant usedAt = ((Timestamp) row.get("used_at")).toInstant();
            if (Duration.between(usedAt, now).compareTo(reuseGrace) >= 0) {
                revoke(sessionId, "refresh_reuse");
                throw new RefreshRefused("reused");
            }
        }
        Instant sessionExpires = ((Timestamp) row.get("session_expires")).toInstant();
        if (!sessionExpires.isAfter(now)) throw new RefreshRefused("expired");
        if (!Boolean.TRUE.equals(row.get("portal_access")) || "EXITED".equals(row.get("status")) || row.get("user_id") == null
                || "SUSPENDED".equals(row.get("business_status"))) {
            revoke(sessionId, "access_ended");
            throw new RefreshRefused("blocked");
        }
        if (row.get("used_at") == null) repo.markRefreshTokenUsed(Tokens.sha256(refreshToken), now);
        repo.updateLastSeen(sessionId, now);
        String next = newRefreshToken(sessionId, sessionExpires, meta);
        Instant created = ((Timestamp) row.get("created_at")).toInstant();
        boolean remember = Duration.between(created, sessionExpires).compareTo(Duration.ofHours(SHORT_SESSION_HOURS + 1)) > 0;
        return new Issued(sessionId, cookies((UUID) row.get("employee_id"), sessionId, next, remember ? Duration.between(now, sessionExpires) : null));
    }

    private String newRefreshToken(String sessionId, Instant expiresAt, RequestMeta meta) {
        String token = Tokens.randomToken(32);
        repo.insertRefreshToken(Tokens.sha256(token), sessionId, expiresAt, meta.ipAddress(), meta.userAgent());
        return token;
    }

    /** {@code maxAge} null = browser-session cookies (no "keep me signed in"). */
    private List<ResponseCookie> cookies(UUID employeeId, String sessionId, String refresh, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder accessCookie = base(ACCESS_COOKIE, jwtService.issue(employeeId, sessionId));
        ResponseCookie.ResponseCookieBuilder responseCookieBuilder = base(REFRESH_COOKIE, refresh);
        if (maxAge != null) {
            accessCookie.maxAge(maxAge);
            responseCookieBuilder.maxAge(maxAge);
        }
        return List.of(accessCookie.build(), responseCookieBuilder.build(), expired(LEGACY_COOKIE));
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value) {
        return ResponseCookie.from(name, value).httpOnly(true).sameSite("Lax").secure(secureCookie).path("/");
    }

    private ResponseCookie expired(String name) {
        return base(name, "").maxAge(0).build();
    }

    /** Clears all sign-in cookies in the browser. */
    public List<ResponseCookie> clearCookies() {
        return List.of(expired(ACCESS_COOKIE), expired(REFRESH_COOKIE), expired(LEGACY_COOKIE));
    }

    /** Ends one session (sign-out). */
    @Transactional
    public void revoke(String sessionId, String reason) {
        if (sessionId == null) return;
        repo.revoke(sessionId, reason, Instant.now());
    }

    /** Sign-out with only the refresh cookie (access token already expired). */
    @Transactional
    public void revokeByRefreshToken(String refreshToken, String reason) {
        if (refreshToken == null || refreshToken.isEmpty()) return;
        List<String> sessionId = repo.findSessionIdByRefreshToken(Tokens.sha256(refreshToken));
        if (!sessionId.isEmpty()) revoke(sessionId.get(0), reason);
    }

    /** Ends every active session of an employee immediately (access disabled, exit, password reset). */
    @Transactional
    public void revokeAll(UUID employeeId, String reason) {
        repo.revokeAll(employeeId, reason, Instant.now());
    }
}
