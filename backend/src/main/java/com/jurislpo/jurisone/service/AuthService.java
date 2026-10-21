package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Nav;
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
import com.jurislpo.jurisone.exception.ApiException;
import com.jurislpo.jurisone.tenancy.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign in (email + emailed code) and out: failed attempts per IP, account lockout, no account enumeration,
 * login history and audit.
 */
@Service
public class AuthService {

    public static final String BAD_OTP = "That code is wrong or has expired. Request a new one. If it keeps failing, your account may be disabled or your organisation suspended: contact your administrator.";
    public static final String MSG_DISABLED = "Your portal access is disabled. Please contact HR.";
    public static final String MSG_EXITED = "This account belongs to a past employee and can no longer sign in.";
    static final String MSG_SUSPENDED = "Your organisation's JurisOne account is suspended. Please contact JurisOne.";

    private final AuthRepository repo;
    private final TransactionTemplate tx;
    private final RateLimiter limiter;
    private final SessionService sessions;
    private final SettingsService settings;
    private final AuditService audit;
    private final OtpService otps;
    private final int otpPerIp, otpPerEmail;
    private final MailService mail;
    private final SubscriptionService subscriptions;

    public AuthService(AuthRepository repo, TransactionTemplate tx, RateLimiter limiter, SessionService sessions,
                       SettingsService settings, AuditService audit, OtpService otps,
                       @org.springframework.beans.factory.annotation.Value("${jurisone.otp.requests-per-ip:10}") int otpPerIp,
                       @org.springframework.beans.factory.annotation.Value("${jurisone.otp.requests-per-email:5}") int otpPerEmail, MailService mail,
                       SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
        this.otpPerIp = otpPerIp;
        this.otpPerEmail = otpPerEmail;
        this.mail = mail;
        this.otps = otps;
        this.repo = repo;
        this.tx = tx;
        this.limiter = limiter;
        this.sessions = sessions;
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
            if (!success) { // every refused or failed sign-in step is in the audit trail too (identifier masked)
                audit.log(emp != null ? emp.getId() : null, "auth.login_failed", "session", emp != null ? emp.getId().toString() : null, null,
                        java.util.Map.of("reason", reason, "identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(identifier)), meta.ip());
            }
        });
    }

    /**
     * Last step of every sign-in, once the person has proved who they are (emailed code): blocked
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
        if (emp.getBusinessId() != null) { // no subscription yet, or none at all: no sign-in (an ended one signs in read-only)
            SubscriptionService.Current sub = TenantContext.call(null, () -> subscriptions.current(emp.getBusinessId()));
            if ("UPCOMING".equals(sub.state()) || "NONE".equals(sub.state())) {
                history(emp, identifier, false, "subscription_" + sub.state().toLowerCase(), meta);
                String when = "UPCOMING".equals(sub.state())
                        ? java.time.LocalDate.parse(sub.startsOn()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) : null;
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_INACTIVE", when != null
                        ? "Your organisation's subscription starts on " + when + ". You can sign in from then."
                        : "Your organisation has no active subscription. Please contact your administrator.");
            }
        }

        SessionService.Issued issued = tx.execute(s -> {
            User u = repo.findUser(emp.getId());
            if (u == null) { // first sign-in
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
        RateLimiter.Result byIp = limiter.rateLimit("otp-ip:" + ip, otpPerIp, 15 * 60_000L);
        RateLimiter.Result byMail = limiter.rateLimit("otp-mail:" + email, otpPerEmail, 15 * 60_000L);
        if (byIp.limited() || byMail.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many code requests. Try again in " + minutes(Math.max(byIp.retryInMs(), byMail.retryInMs())) + " minute(s).");
        }
        Employee emp = findByIdentifier(email);
        if (canReceiveOtp(emp)) {
            otps.issue(email, emp.getFullName(), meta.ip());
            audit.log(emp.getId(), "auth.otp_requested", "session", emp.getId().toString(), null,
                    java.util.Map.of("identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(email)), meta.ip());
        }
        else if (emp != null) { // the person is told by email why no code comes (the page still answers the same for everyone)
            history(emp, email, false, emp.getStatus() == EmployeeStatus.EXITED ? "exited" : !Boolean.TRUE.equals(emp.getPortalAccess()) ? "access_disabled" : "business_suspended", meta);
            mail.send(email, MailTemplates.noCode(emp.getFullName(), emp.getStatus() == EmployeeStatus.EXITED ? MSG_EXITED
                    : !Boolean.TRUE.equals(emp.getPortalAccess()) ? MSG_DISABLED : MSG_SUSPENDED));
        }
        return "If that account exists and is active, a 6-digit code is on its way to the email.";
    }

    /** Sign in with the emailed code (blocks, lockout, history and audit). */
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
}
