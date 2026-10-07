package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.entity.AuthToken;
import com.jurislpo.jurisone.entity.AuthTokenPurpose;
import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.entity.LoginHistory;
import com.jurislpo.jurisone.entity.User;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.platform.SettingsService;
import com.jurislpo.jurisone.platform.SettingsService.AuthPolicy;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign in / out, forgot password, invite + reset links — src/app/(auth)/actions.ts, same messages and rules:
 * failed attempts per IP, account lockout, no account enumeration, login history and audit.
 */
@Service
public class AuthService {

    static final String BAD_LOGIN = "That employee ID / email and password don't match.";
    static final String MSG_DISABLED = "Your portal access is disabled. Please contact HR.";
    static final String MSG_EXITED = "This account belongs to a past employee and can no longer sign in.";

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final Passwords passwords;
    private final RateLimiter limiter;
    private final SessionService sessions;
    private final AuthTokenService tokens;
    private final SettingsService settings;
    private final AuditService audit;

    public AuthService(EntityManager em, TransactionTemplate tx, Passwords passwords, RateLimiter limiter, SessionService sessions,
                       AuthTokenService tokens, SettingsService settings, AuditService audit) {
        this.em = em;
        this.tx = tx;
        this.passwords = passwords;
        this.limiter = limiter;
        this.sessions = sessions;
        this.tokens = tokens;
        this.settings = settings;
        this.audit = audit;
    }

    static long minutes(long ms) {
        return Math.max(1, (long) Math.ceil(ms / 60_000.0));
    }

    /** Signed in: where to go, and the session cookie. */
    public record LoginResult(String redirect, ResponseCookie cookie) {}

    private static UserException fail(String msg) {
        return new UserException(HttpStatus.UNAUTHORIZED, msg);
    }

    private Employee findByIdentifier(String identifier) {
        String id = identifier.toLowerCase();
        List<Employee> found = id.contains("@")
                ? em.createQuery("SELECT e FROM Employee e WHERE e.email = :v", Employee.class).setParameter("v", id).getResultList()
                : em.createQuery("SELECT e FROM Employee e WHERE e.code = :v", Employee.class).setParameter("v", id.toUpperCase()).getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    private void history(Employee emp, String identifier, boolean success, String reason, RequestMeta meta) {
        tx.executeWithoutResult(s -> {
            LoginHistory h = new LoginHistory();
            h.setEmployeeId(emp != null ? emp.getId() : null);
            h.setIdentifier(identifier.substring(0, Math.min(200, identifier.length())));
            h.setSuccess(success);
            h.setReason(reason);
            h.setIp(meta.ip());
            h.setUserAgent(meta.userAgent());
            em.persist(h);
        });
    }

    public LoginResult login(String identifierRaw, String password, boolean remember, String next, RequestMeta meta) {
        String identifier = identifierRaw == null ? "" : identifierRaw.trim();
        if (identifier.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your employee ID or email.");
        if (identifier.length() > 200) throw new UserException(HttpStatus.BAD_REQUEST, "Some details are invalid.");
        if (password == null || password.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your password.");
        if (password.length() > 200) throw new UserException(HttpStatus.BAD_REQUEST, "Some details are invalid.");
        AuthPolicy policy = settings.auth();

        // Only failed attempts count against the IP, so a shared office IP isn't throttled by normal sign-ins.
        String ipKey = "login:" + (meta.ip() != null ? meta.ip() : "unknown");
        RateLimiter.Result rl = limiter.limited(ipKey, policy.ipMaxAttempts());
        if (rl.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-in attempts. Try again in " + minutes(rl.retryInMs()) + " minute(s).");
        }
        Runnable failedFromIp = () -> limiter.hit(ipKey, policy.ipWindowMinutes() * 60_000L);

        Employee emp = findByIdentifier(identifier);
        User user = emp == null ? null : em.find(User.class, emp.getId());
        if (emp == null || user == null) {
            passwords.verify(passwords.dummyHash(), password);
            failedFromIp.run();
            history(emp, identifier, false, "unknown_identifier", meta);
            throw fail(BAD_LOGIN);
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            history(emp, identifier, false, "locked", meta);
            throw fail("This account is locked after repeated failed sign-ins. Try again in "
                    + minutes(user.getLockedUntil().toEpochMilli() - System.currentTimeMillis()) + " minute(s), or reset your password.");
        }

        if (!passwords.verify(user.getPasswordHash(), password)) {
            failedFromIp.run();
            int failed = user.getFailedAttempts() + 1;
            boolean lock = failed >= policy.maxFailedAttempts();
            boolean activated = user.getPasswordHash() != null;
            tx.executeWithoutResult(s -> {
                User u = em.find(User.class, emp.getId());
                if (lock) {
                    u.setFailedAttempts(0);
                    u.setLockedUntil(Instant.now().plusSeconds(policy.lockMinutes() * 60L).truncatedTo(ChronoUnit.MILLIS));
                } else {
                    u.setFailedAttempts(failed);
                }
            });
            history(emp, identifier, false, lock ? "bad_password_locked" : activated ? "bad_password" : "not_activated", meta);
            if (lock) {
                audit.log(emp.getId(), "auth.locked", "user", emp.getId().toString(), null, Map.of("lockMinutes", policy.lockMinutes()), meta.ip());
                throw fail("Too many failed attempts — this account is locked for " + policy.lockMinutes() + " minutes.");
            }
            throw fail(activated ? BAD_LOGIN : "This account isn't activated yet. Use the invite link in your email, or choose “Forgot?” to get a new one.");
        }

        // Blocked accounts get their specific message only after the password is right (no enumeration).
        if (emp.getStatus() == EmployeeStatus.EXITED) {
            history(emp, identifier, false, "exited", meta);
            throw fail(MSG_EXITED);
        }
        if (!Boolean.TRUE.equals(emp.getPortalAccess())) {
            history(emp, identifier, false, "access_disabled", meta);
            throw fail(MSG_DISABLED);
        }

        ResponseCookie cookie = tx.execute(s -> {
            User u = em.find(User.class, emp.getId());
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            u.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            return sessions.create(emp.getId(), remember, meta);
        });
        history(emp, identifier, true, "ok", meta);
        Map<String, Object> after = new java.util.HashMap<>();
        after.put("userAgent", meta.userAgent());
        audit.log(emp.getId(), "auth.login", "session", emp.getId().toString(), null, after, meta.ip());

        Set<String> perms = new HashSet<>(em.createQuery("SELECT p.permissionKey FROM RolePermission p WHERE p.roleId = :r", String.class)
                .setParameter("r", emp.getRoleId()).getResultList());
        String target = next != null && next.startsWith("/") && !next.startsWith("//") ? next : Nav.landingFor(perms);
        return new LoginResult(target, cookie);
    }

    public void logout(String token, CurrentUser u, RequestMeta meta) {
        sessions.destroy(token);
        if (u != null) audit.log(u.id(), "auth.logout", "session", u.id().toString(), meta.ip());
    }

    public String forgot(String identifierRaw, RequestMeta meta) {
        String identifier = identifierRaw == null ? "" : identifierRaw.trim().toLowerCase();
        if (identifier.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your employee ID or official email.");
        RateLimiter.Result rl = limiter.rateLimit("forgot:" + (meta.ip() != null ? meta.ip() : "unknown"), 5, 15 * 60_000L);
        if (rl.limited()) throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in " + minutes(rl.retryInMs()) + " minute(s).");
        Employee emp = findByIdentifier(identifier);
        if (emp != null && em.find(User.class, emp.getId()) != null && emp.getStatus() != EmployeeStatus.EXITED && Boolean.TRUE.equals(emp.getPortalAccess())) {
            tokens.sendPasswordReset(emp.getId());
            audit.log(emp.getId(), "auth.reset_requested", "user", emp.getId().toString(), meta.ip());
        }
        return "If that account exists and is active, a reset link is on its way to the official email.";
    }

    /** Name and code for the set-password page, or null when the link is unusable. */
    public Map<String, String> tokenInfo(String token, String purpose) {
        AuthTokenPurpose p;
        try {
            p = AuthTokenPurpose.valueOf(purpose);
        } catch (IllegalArgumentException e) {
            return null;
        }
        AuthToken t = tokens.findUsable(token, p);
        return t == null ? null : Map.of("name", t.getEmployee().getFullName(), "code", t.getEmployee().getCode());
    }

    /** Sets the password from an invite / reset link; returns where to go next. */
    public String setPassword(String token, String purpose, String password, String confirm, RequestMeta meta) {
        boolean validPurpose = "INVITE".equals(purpose) || "RESET".equals(purpose);
        if (token == null || token.length() < 10 || token.length() > 200 || !validPurpose || password == null || confirm == null) {
            throw new UserException(HttpStatus.BAD_REQUEST, "This link is invalid.");
        }
        String problem = Passwords.problem(password);
        if (problem != null) throw new UserException(problem);
        if (!password.equals(confirm)) throw new UserException("The two passwords don't match.");

        AuthTokenPurpose p = AuthTokenPurpose.valueOf(purpose);
        AuthToken t = tokens.findUsable(token, p);
        if (t == null) {
            throw new UserException(HttpStatus.GONE, "This link has expired or was already used. Ask HR for a new invite, or use “Forgot?” on the sign-in page.");
        }
        String hash = passwords.hash(password);
        UUID employeeId = t.getEmployeeId();
        boolean reset = p == AuthTokenPurpose.RESET;
        tx.executeWithoutResult(s -> {
            AuthToken row = em.find(AuthToken.class, t.getId());
            row.setUsedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            User u = em.find(User.class, employeeId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (u == null) {
                u = new User();
                u.setEmployeeId(employeeId);
                u.setPasswordHash(hash);
                u.setPasswordSetAt(now);
                em.persist(u);
            } else {
                u.setPasswordHash(hash);
                u.setPasswordSetAt(now);
                u.setFailedAttempts(0);
                u.setLockedUntil(null);
            }
            sessions.revokeAll(employeeId, reset ? "password_reset" : "invite_accepted");
            audit.log(employeeId, reset ? "auth.password_reset" : "auth.invite_accepted", "user", employeeId.toString(), meta.ip());
        });
        return "/login?" + (reset ? "reset" : "activated") + "=1";
    }
}
