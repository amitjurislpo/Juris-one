package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.engine.Nav;
import com.jurislpo.jurisone.entity.AuthToken;
import com.jurislpo.jurisone.entity.AuthTokenPurpose;
import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.entity.LoginHistory;
import com.jurislpo.jurisone.entity.User;
import com.jurislpo.jurisone.repository.AuthRepository;
import com.jurislpo.jurisone.service.SettingsService.AuthPolicy;
import com.jurislpo.jurisone.security.CurrentUser;
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

    public static final String BAD_LOGIN = "That email and password don't match.";
    public static final String BAD_OTP = "That code is wrong or has expired. Request a new one.";
    public static final String MSG_DISABLED = "Your portal access is disabled. Please contact HR.";
    public static final String MSG_EXITED = "This account belongs to a past employee and can no longer sign in.";
    static final String MSG_SUSPENDED = "Your organisation's JurisOne account is suspended. Please contact JurisOne.";

    private final AuthRepository repo;
    private final TransactionTemplate tx;
    private final Passwords passwords;
    private final RateLimiter limiter;
    private final SessionService sessions;
    private final AuthTokenService tokens;
    private final SettingsService settings;
    private final AuditService audit;
    private final OtpService otps;

    public AuthService(AuthRepository repo, TransactionTemplate tx, Passwords passwords, RateLimiter limiter, SessionService sessions,
                       AuthTokenService tokens, SettingsService settings, AuditService audit, OtpService otps) {
        this.otps = otps;
        this.repo = repo;
        this.tx = tx;
        this.passwords = passwords;
        this.limiter = limiter;
        this.sessions = sessions;
        this.tokens = tokens;
        this.settings = settings;
        this.audit = audit;
    }

    private String jdbcStatus(java.util.UUID businessId) {
        return repo.findBusinessStatus(businessId);
    }

    static long minutes(long ms) {
        return Math.max(1, (long) Math.ceil(ms / 60_000.0));
    }

    /** Signed in: where to go, and the cookies (JWT access token + refresh token). */
    public record LoginResult(String redirect, java.util.List<ResponseCookie> cookies) {}

    private static UserException fail(String msg) {
        return new UserException(HttpStatus.UNAUTHORIZED, msg);
    }

    /**
     * The account for a sign-in email. Email is the only sign-in name: it is unique across every business, while
     * employee IDs repeat from one business to the next. (Runs in system mode — no business is known yet.)
     */
    private Employee findByIdentifier(String identifier) {
        String email = identifier.toLowerCase();
        if (!email.contains("@")) return null;
        List<Employee> found = repo.findEmployeesByEmail(email);
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
            repo.persistLoginHistory(h);
        });
    }

    public LoginResult login(String identifierRaw, String password, boolean remember, String next, RequestMeta meta) {
        String identifier = identifierRaw == null ? "" : identifierRaw.trim();
        if (identifier.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your email.");
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
        User user = emp == null ? null : repo.findUser(emp.getId());
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
                User u = repo.findUser(emp.getId());
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

        return completeSignIn(emp, identifier, remember, next, meta);
    }

    /**
     * Last step of every sign-in, once the person has proved who they are (password or emailed code): blocked
     * accounts get their specific message, then the session, history and audit rows are written.
     */
    private LoginResult completeSignIn(Employee emp, String identifier, boolean remember, String next, RequestMeta meta) {
        // Blocked accounts get their specific message only after the person is proved (no enumeration).
        if (emp.getStatus() == EmployeeStatus.EXITED) {
            history(emp, identifier, false, "exited", meta);
            throw fail(MSG_EXITED);
        }
        if (!Boolean.TRUE.equals(emp.getPortalAccess())) {
            history(emp, identifier, false, "access_disabled", meta);
            throw fail(MSG_DISABLED);
        }
        if (emp.getBusinessId() != null && "SUSPENDED".equals(jdbcStatus(emp.getBusinessId()))) {
            history(emp, identifier, false, "business_suspended", meta);
            throw fail(MSG_SUSPENDED);
        }

        SessionService.Issued issued = tx.execute(s -> {
            User u = repo.findUser(emp.getId());
            if (u == null) { // first sign-in by emailed code, before any password was ever set
                u = new User();
                u.setEmployeeId(emp.getId());
                repo.persistUser(u);
            }
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            u.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            return sessions.create(emp.getId(), remember, meta);
        });
        history(emp, identifier, true, "ok", meta);
        Map<String, Object> after = new java.util.HashMap<>();
        after.put("userAgent", meta.userAgent());
        audit.log(emp.getId(), "auth.login", "session", emp.getId().toString(), null, after, meta.ip());

        Set<String> perms = new HashSet<>(repo.findPermissionKeys(emp.getRoleId()));
        String target = next != null && next.startsWith("/") && !next.startsWith("//") ? next : Nav.landingFor(perms);
        return new LoginResult(target, issued.cookies());
    }

    /** Whether this account may sign in at all right now (exists, active, portal access on, business not suspended). */
    private boolean canReceiveOtp(Employee emp) {
        return emp != null && emp.getStatus() != EmployeeStatus.EXITED && Boolean.TRUE.equals(emp.getPortalAccess())
                && !(emp.getBusinessId() != null && "SUSPENDED".equals(jdbcStatus(emp.getBusinessId())));
    }

    /** Emails a sign-in code. Always the same answer, so it doesn't reveal who has an account. */
    public String requestOtp(String emailRaw, RequestMeta meta) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (email.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your email.");
        if (email.length() > 200 || !email.contains("@")) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid email.");
        String ip = meta.ip() != null ? meta.ip() : "unknown";
        RateLimiter.Result byIp = limiter.rateLimit("otp-ip:" + ip, 10, 15 * 60_000L);
        RateLimiter.Result byMail = limiter.rateLimit("otp-mail:" + email, 5, 15 * 60_000L);
        if (byIp.limited() || byMail.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many code requests. Try again in " + minutes(Math.max(byIp.retryInMs(), byMail.retryInMs())) + " minute(s).");
        }
        Employee emp = findByIdentifier(email);
        if (canReceiveOtp(emp)) otps.issue(email, emp.getFullName(), meta.ip());
        return "If that account exists and is active, a 6-digit code is on its way to the email.";
    }

    /** Sign in with the emailed code (same blocks, lockout, history and audit as a password sign-in). */
    public LoginResult loginWithOtp(String emailRaw, String code, boolean remember, String next, RequestMeta meta) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (email.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your email.");
        if (code == null || !code.trim().matches("[0-9]{6}")) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the 6-digit code.");
        AuthPolicy policy = settings.auth();
        String ipKey = "login:" + (meta.ip() != null ? meta.ip() : "unknown");
        RateLimiter.Result rl = limiter.limited(ipKey, policy.ipMaxAttempts());
        if (rl.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-in attempts. Try again in " + minutes(rl.retryInMs()) + " minute(s).");
        }
        Employee emp = findByIdentifier(email);
        User user = emp == null ? null : repo.findUser(emp.getId());
        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            history(emp, email, false, "locked", meta);
            throw fail("This account is locked after repeated failed sign-ins. Try again in "
                    + minutes(user.getLockedUntil().toEpochMilli() - System.currentTimeMillis()) + " minute(s).");
        }
        if (emp == null || !otps.verify(email, code.trim())) {
            limiter.hit(ipKey, policy.ipWindowMinutes() * 60_000L);
            history(emp, email, false, "bad_otp", meta);
            throw fail(BAD_OTP);
        }
        return completeSignIn(emp, email, remember, next, meta);
    }

    /** Ends this device's session: the one in the access token, else the refresh cookie's. */
    public void logout(CurrentUser u, String refreshToken, RequestMeta meta) {
        if (u != null) sessions.revoke(u.sessionId(), "logout");
        else sessions.revokeByRefreshToken(refreshToken, "logout");
        if (u != null) audit.log(u.id(), "auth.logout", "session", u.id().toString(), meta.ip());
    }

    public String forgot(String identifierRaw, RequestMeta meta) {
        String identifier = identifierRaw == null ? "" : identifierRaw.trim().toLowerCase();
        if (identifier.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your official email.");
        RateLimiter.Result rl = limiter.rateLimit("forgot:" + (meta.ip() != null ? meta.ip() : "unknown"), 5, 15 * 60_000L);
        if (rl.limited()) throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again in " + minutes(rl.retryInMs()) + " minute(s).");
        Employee emp = findByIdentifier(identifier);
        if (emp != null && repo.findUser(emp.getId()) != null && emp.getStatus() != EmployeeStatus.EXITED && Boolean.TRUE.equals(emp.getPortalAccess())) {
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
            AuthToken row = repo.findAuthToken(t.getId());
            row.setUsedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            User u = repo.findUser(employeeId);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if (u == null) {
                u = new User();
                u.setEmployeeId(employeeId);
                u.setPasswordHash(hash);
                u.setPasswordSetAt(now);
                repo.persistUser(u);
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
