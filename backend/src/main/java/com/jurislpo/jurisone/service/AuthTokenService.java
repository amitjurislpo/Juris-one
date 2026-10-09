package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.entity.AuthToken;
import com.jurislpo.jurisone.entity.AuthTokenPurpose;
import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.repository.AuthTokenRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One-time invite / reset links (src/lib/auth-tokens.ts). Only the newest link of each kind works. */
@Service
public class AuthTokenService {

    private final AuthTokenRepository repo;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final MailService mail;
    private final String appUrl;

    public AuthTokenService(AuthTokenRepository repo, TransactionTemplate tx, SettingsService settings, MailService mail,
                            @Value("${APP_URL:http://localhost:3005}") String appUrl) {
        this.repo = repo;
        this.tx = tx;
        this.settings = settings;
        this.mail = mail;
        this.appUrl = appUrl.replaceAll("/$", "");
    }

    String issue(UUID employeeId, AuthTokenPurpose purpose, Duration ttl) {
        String token = Tokens.randomToken(32);
        tx.executeWithoutResult(s -> {
            repo.markUnusedAsUsed(employeeId, purpose, Instant.now());
            AuthToken t = new AuthToken();
            t.setTokenHash(Tokens.sha256(token));
            t.setEmployeeId(employeeId);
            t.setPurpose(purpose);
            t.setExpiresAt(Instant.now().plus(ttl).truncatedTo(ChronoUnit.MILLIS));
            repo.persist(t);
        });
        return token;
    }

    /** One-time "set your password" link for a new employee (REQUIREMENTS §4). */
    public void sendInvite(UUID employeeId) {
        Employee e = repo.findEmployee(employeeId);
        int hours = settings.auth().inviteTtlHours();
        String token = issue(employeeId, AuthTokenPurpose.INVITE, Duration.ofHours(hours));
        mail.send(e.getEmail(), MailTemplates.invite(e.getFullName(), e.getCode(), appUrl + "/invite/" + token, hours));
    }

    public void sendPasswordReset(UUID employeeId) {
        Employee e = repo.findEmployee(employeeId);
        int minutes = settings.auth().resetTtlMinutes();
        String token = issue(employeeId, AuthTokenPurpose.RESET, Duration.ofMinutes(minutes));
        mail.send(e.getEmail(), MailTemplates.reset(e.getFullName(), appUrl + "/reset/" + token, minutes));
    }

    /** The token if it exists, has this purpose, is unused, unexpired, and its employee hasn't exited. */
    public AuthToken findUsable(String token, AuthTokenPurpose purpose) {
        List<AuthToken> ts = repo.findByHash(Tokens.sha256(token));
        if (ts.isEmpty()) return null;
        AuthToken t = ts.get(0);
        if (t.getPurpose() != purpose || t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now())) return null;
        if (t.getEmployee().getStatus() == EmployeeStatus.EXITED) return null;
        return t;
    }
}
