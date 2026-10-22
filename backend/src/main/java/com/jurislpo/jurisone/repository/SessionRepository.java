package com.jurislpo.jurisone.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Signed-in devices ({@code sessions}) and their rotating refresh tokens ({@code refresh_tokens}). */
@Repository
public class SessionRepository {

    private static final String USER_SQL = """
        SELECT s.id AS session_id, s.employee_id AS session_employee, s.expires_at, s.revoked_at, s.last_seen_at,
               e.id, e.business_id, e.code, e.full_name, e.email, e.designation, e.role_id, e.portal_access, e.status::text AS status,
               e.region::text AS region, e.time_zone, e.photo_updated_at, u.is_super_admin, u.employee_id AS user_id, r.key AS role_key, r.name AS role_name,
               EXISTS (SELECT 1 FROM employees d WHERE d.reporting_manager_id = e.id AND d.status <> 'EXITED') AS has_reports,
               (SELECT b.status FROM businesses b WHERE b.id = e.business_id) AS business_status
        FROM sessions s
        JOIN employees e ON e.id = s.employee_id
        JOIN roles r ON r.id = e.role_id
        LEFT JOIN users u ON u.employee_id = e.id
        WHERE s.id = ?""";

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSession(String sessionId, UUID employeeId, Instant expiresAt, String ipAddress, String userAgent) {
        jdbc.update("INSERT INTO sessions (id, employee_id, expires_at, ip, user_agent) VALUES (?, ?, ?, ?, ?)",
                sessionId, employeeId, Timestamp.from(expiresAt), ipAddress, userAgent);
    }

    /**
     * The refresh token with this hash joined to its session, employee and business status, the token row locked
     * ({@code FOR UPDATE}); empty when unknown.
     */
    public List<Map<String, Object>> findRefreshTokenForUpdate(String tokenHash) {
        return jdbc.queryForList("""
            SELECT t.used_at, t.expires_at, s.id AS session_id, s.created_at, s.expires_at AS session_expires, s.revoked_at, s.employee_id,
                   e.portal_access, e.status::text AS status, (SELECT u.employee_id FROM users u WHERE u.employee_id = e.id) AS user_id,
                   (SELECT b.status FROM businesses b WHERE b.id = e.business_id) AS business_status
            FROM refresh_tokens t JOIN sessions s ON s.id = t.session_id JOIN employees e ON e.id = s.employee_id
            WHERE t.token_hash = ? FOR UPDATE OF t""", tokenHash);
    }

    public void markRefreshTokenUsed(String tokenHash, Instant usedAt) {
        jdbc.update("UPDATE refresh_tokens SET used_at = ? WHERE token_hash = ?", Timestamp.from(usedAt), tokenHash);
    }

    public void updateLastSeen(String sessionId, Instant instant) {
        jdbc.update("UPDATE sessions SET last_seen_at = ? WHERE id = ?", Timestamp.from(instant), sessionId);
    }

    public void insertRefreshToken(String tokenHash, String sessionId, Instant expiresAt, String ipAddress, String userAgent) {
        jdbc.update("INSERT INTO refresh_tokens (token_hash, session_id, expires_at, ip, user_agent) VALUES (?, ?, ?, ?, ?)",
                tokenHash, sessionId, Timestamp.from(expiresAt), ipAddress, userAgent);
    }

    /** Revokes one session unless already revoked. */
    public void revoke(String sessionId, String reason, Instant instant) {
        jdbc.update("UPDATE sessions SET revoked_at = ?, revoked_reason = ? WHERE id = ? AND revoked_at IS NULL", Timestamp.from(instant), reason, sessionId);
    }

    /** The session id (zero or one) of a refresh token. */
    public List<String> findSessionIdByRefreshToken(String tokenHash) {
        return jdbc.queryForList("SELECT session_id FROM refresh_tokens WHERE token_hash = ?", String.class, tokenHash);
    }

    /** Revokes every active session of an employee. */
    public void revokeAll(UUID employeeId, String reason, Instant instant) {
        jdbc.update("UPDATE sessions SET revoked_at = ?, revoked_reason = ? WHERE employee_id = ? AND revoked_at IS NULL",
                Timestamp.from(instant), reason, employeeId);
    }

    // ── access-token authentication (JwtAuthFilter) ──

    /** The session with its employee, user, role and business status (zero or one row). */
    public List<Map<String, Object>> findSessionUser(String sessionId) {
        return jdbc.queryForList(USER_SQL, sessionId);
    }

    /** Sets {@code last_seen_at} to the database's now(). */
    public void touch(String sessionId) {
        jdbc.update("UPDATE sessions SET last_seen_at = now() WHERE id = ?", sessionId);
    }

    public List<String> findPermissionKeys(UUID roleId) {
        return jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId);
    }
}
