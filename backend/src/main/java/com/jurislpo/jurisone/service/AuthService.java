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
    private final TransactionTemplate transactionTemplate;
    private final RateLimiter limiter;
    private final SessionService sessions;
    private final SettingsService settings;
    private final AuditService audit;
    private final OtpService otps;
    private final int otpPerIp, otpPerEmail;
    private final MailService mail;
    private final SubscriptionService subscriptions;

    public AuthService(AuthRepository repo, TransactionTemplate transactionTemplate, RateLimiter limiter, SessionService sessions,
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
        this.transactionTemplate = transactionTemplate;
        this.limiter = limiter;
        this.sessions = sessions;
        this.settings = settings;
        this.audit = audit;
    }

    private String jdbcStatus(java.util.UUID businessId) {
        return repo.findBusinessStatus(businessId);
    }

    static long minutes(long millis) {
        return Math.max(1, (long) Math.ceil(millis / 60_000.0));
    }

    /** Signed in: where to go, and the cookies (JWT access token + refresh token). */
    public record LoginResult(String redirect, java.util.List<ResponseCookie> cookies) {}

    private static UserException fail(String message) {
        return new UserException(HttpStatus.UNAUTHORIZED, message);
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

    private void history(Employee employee, String identifier, boolean success, String reason, RequestMeta meta) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            LoginHistory loginHistory = new LoginHistory();
            loginHistory.setEmployeeId(employee != null ? employee.getId() : null);
            loginHistory.setIdentifier(identifier.substring(0, Math.min(200, identifier.length())));
            loginHistory.setSuccess(success);
            loginHistory.setReason(reason);
            loginHistory.setIpAddress(meta.ipAddress());
            loginHistory.setUserAgent(meta.userAgent());
            repo.persistLoginHistory(loginHistory);
            if (!success) { // every refused or failed sign-in step is in the audit trail too (identifier masked)
                audit.log(employee != null ? employee.getId() : null, "auth.login_failed", "session", employee != null ? employee.getId().toString() : null, null,
                        java.util.Map.of("reason", reason, "identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(identifier)), meta.ipAddress());
            }
        });
    }

    /**
     * Last step of every sign-in, once the person has proved who they are (emailed code): blocked
     * accounts get their specific message, then the session, history and audit rows are written.
     */
    private LoginResult completeSignIn(Employee employee, String identifier, boolean remember, String next, RequestMeta meta) {
        // Blocked accounts get their specific message only after the person is proved (no enumeration).
        if (employee.getStatus() == EmployeeStatus.EXITED) {
            history(employee, identifier, false, "exited", meta);
            throw fail(MSG_EXITED);
        }
        if (!Boolean.TRUE.equals(employee.getPortalAccess())) {
            history(employee, identifier, false, "access_disabled", meta);
            throw fail(MSG_DISABLED);
        }
        if (employee.getBusinessId() != null && "SUSPENDED".equals(jdbcStatus(employee.getBusinessId()))) {
            history(employee, identifier, false, "business_suspended", meta);
            throw fail(MSG_SUSPENDED);
        }
        if (employee.getBusinessId() != null) { // no subscription yet, or none at all: no sign-in (an ended one signs in read-only)
            SubscriptionService.Current subscription = TenantContext.call(null, () -> subscriptions.current(employee.getBusinessId()));
            if ("UPCOMING".equals(subscription.state()) || "NONE".equals(subscription.state())) {
                history(employee, identifier, false, "subscription_" + subscription.state().toLowerCase(), meta);
                String when = "UPCOMING".equals(subscription.state())
                        ? java.time.LocalDate.parse(subscription.startsOn()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) : null;
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_INACTIVE", when != null
                        ? "Your organisation's subscription starts on " + when + ". You can sign in from then."
                        : "Your organisation has no active subscription. Please contact your administrator.");
            }
        }

        SessionService.Issued issued = transactionTemplate.execute(transactionStatus -> {
            User user = repo.findUser(employee.getId());
            if (user == null) { // first sign-in
                user = new User();
                user.setEmployeeId(employee.getId());
                repo.persistUser(user);
            }
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            return sessions.create(employee.getId(), remember, meta);
        });
        history(employee, identifier, true, "ok", meta);
        Map<String, Object> after = new java.util.HashMap<>();
        after.put("userAgent", meta.userAgent());
        audit.log(employee.getId(), "auth.login", "session", employee.getId().toString(), null, after, meta.ipAddress());

        Set<String> perms = new HashSet<>(repo.findPermissionKeys(employee.getRoleId()));
        String target = next != null && next.startsWith("/") && !next.startsWith("//") ? next : Nav.landingFor(perms);
        return new LoginResult(target, issued.cookies());
    }

    /** Whether this account may sign in at all right now (exists, active, portal access on, business not suspended). */
    private boolean canReceiveOtp(Employee employee) {
        return employee != null && employee.getStatus() != EmployeeStatus.EXITED && Boolean.TRUE.equals(employee.getPortalAccess())
                && !(employee.getBusinessId() != null && "SUSPENDED".equals(jdbcStatus(employee.getBusinessId())));
    }

    /** Emails a sign-in code. Always the same answer, so it doesn't reveal who has an account. */
    public String requestOtp(String emailRaw, RequestMeta meta) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (email.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your email.");
        if (email.length() > 200 || !email.contains("@")) throw new UserException(HttpStatus.BAD_REQUEST, "Enter a valid email.");
        String ipAddress = meta.ipAddress() != null ? meta.ipAddress() : "unknown";
        RateLimiter.Result byIp = limiter.rateLimit("otp-ip:" + ipAddress, otpPerIp, 15 * 60_000L);
        RateLimiter.Result byMail = limiter.rateLimit("otp-mail:" + email, otpPerEmail, 15 * 60_000L);
        if (byIp.limited() || byMail.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many code requests. Try again in " + minutes(Math.max(byIp.retryInMs(), byMail.retryInMs())) + " minute(s).");
        }
        Employee employee = findByIdentifier(email);
        if (canReceiveOtp(employee)) {
            otps.issue(email, employee.getFullName(), meta.ipAddress());
            audit.log(employee.getId(), "auth.otp_requested", "session", employee.getId().toString(), null,
                    java.util.Map.of("identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(email)), meta.ipAddress());
        }
        else if (employee != null) { // the person is told by email why no code comes (the page still answers the same for everyone)
            history(employee, email, false, employee.getStatus() == EmployeeStatus.EXITED ? "exited" : !Boolean.TRUE.equals(employee.getPortalAccess()) ? "access_disabled" : "business_suspended", meta);
            mail.send(email, MailTemplates.noCode(employee.getFullName(), employee.getStatus() == EmployeeStatus.EXITED ? MSG_EXITED
                    : !Boolean.TRUE.equals(employee.getPortalAccess()) ? MSG_DISABLED : MSG_SUSPENDED));
        }
        return "If that account exists and is active, a 6-digit code is on its way to the email.";
    }

    /** Sign in with the emailed code (blocks, lockout, history and audit). */
    public LoginResult loginWithOtp(String emailRaw, String code, boolean remember, String next, RequestMeta meta) {
        String email = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (email.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Enter your email.");
        if (code == null || !code.trim().matches("[0-9]{6}")) throw new UserException(HttpStatus.BAD_REQUEST, "Enter the 6-digit code.");
        AuthPolicy policy = settings.auth();
        String ipKey = "login:" + (meta.ipAddress() != null ? meta.ipAddress() : "unknown");
        RateLimiter.Result result = limiter.limited(ipKey, policy.ipMaxAttempts());
        if (result.limited()) {
            throw new UserException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-in attempts. Try again in " + minutes(result.retryInMs()) + " minute(s).");
        }
        Employee employee = findByIdentifier(email);
        User user = employee == null ? null : repo.findUser(employee.getId());
        if (user != null && user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            history(employee, email, false, "locked", meta);
            throw fail("This account is locked after repeated failed sign-ins. Try again in "
                    + minutes(user.getLockedUntil().toEpochMilli() - System.currentTimeMillis()) + " minute(s).");
        }
        if (employee == null || !otps.verify(email, code.trim())) {
            limiter.hit(ipKey, policy.ipWindowMinutes() * 60_000L);
            history(employee, email, false, "bad_otp", meta);
            if (employee != null) lockAfterTooManyFailures(employee, policy, meta);
            throw fail(BAD_OTP);
        }
        return completeSignIn(employee, email, remember, next, meta);
    }

    /**
     * Counts a wrong code against the account; after {@code maxFailedAttempts} in a row the account is locked for
     * {@code lockMinutes} (the count restarts and a success clears it). The answer to the caller stays the same.
     */
    private void lockAfterTooManyFailures(Employee employee, AuthPolicy policy, RequestMeta meta) {
        Instant lockedUntil = transactionTemplate.execute(transactionStatus -> {
            User user = repo.findUser(employee.getId());
            if (user == null) {
                user = new User();
                user.setEmployeeId(employee.getId());
                repo.persistUser(user);
            }
            user.setFailedAttempts(user.getFailedAttempts() + 1);
            if (user.getFailedAttempts() < policy.maxFailedAttempts()) return null;
            user.setFailedAttempts(0);
            user.setLockedUntil(Instant.now().plus(policy.lockMinutes(), ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS));
            return user.getLockedUntil();
        });
        if (lockedUntil != null) {
            audit.log(employee.getId(), "auth.account_locked", "session", employee.getId().toString(), null,
                    Map.of("lockedUntil", lockedUntil.toString(), "identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(employee.getEmail())), meta.ipAddress());
        }
    }

    /** Ends this device's session: the one in the access token, else the refresh cookie's. */
    public void logout(CurrentUser user, String refreshToken, RequestMeta meta) {
        if (user != null) sessions.revoke(user.sessionId(), "logout");
        else sessions.revokeByRefreshToken(refreshToken, "logout");
        if (user != null) audit.log(user.id(), "auth.logout", "session", user.id().toString(), meta.ipAddress());
    }
}
