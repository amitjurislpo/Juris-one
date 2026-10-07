package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.entity.AuthToken;
import com.jurislpo.jurisone.entity.AuthTokenPurpose;
import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.platform.MailService;
import com.jurislpo.jurisone.platform.MailTemplates;
import com.jurislpo.jurisone.platform.SettingsService;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** One-time invite / reset links (src/lib/auth-tokens.ts). Only the newest link of each kind works. */
@Service
public class AuthTokenService {

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final MailService mail;
    private final String appUrl;

    public AuthTokenService(EntityManager em, JdbcTemplate jdbc, TransactionTemplate tx, SettingsService settings, MailService mail,
                            @Value("${APP_URL:http://localhost:3005}") String appUrl) {
        this.em = em;
        this.jdbc = jdbc;
        this.tx = tx;
        this.settings = settings;
        this.mail = mail;
        this.appUrl = appUrl.replaceAll("/$", "");
    }

    String issue(UUID employeeId, AuthTokenPurpose purpose, Duration ttl) {
        String token = Tokens.randomToken(32);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE auth_tokens SET used_at = ? WHERE employee_id = ? AND purpose = ?::\"AuthTokenPurpose\" AND used_at IS NULL",
                    Timestamp.from(Instant.now()), employeeId, purpose.name());
            AuthToken t = new AuthToken();
            t.setTokenHash(Tokens.sha256(token));
            t.setEmployeeId(employeeId);
            t.setPurpose(purpose);
            t.setExpiresAt(Instant.now().plus(ttl).truncatedTo(ChronoUnit.MILLIS));
            em.persist(t);
        });
        return token;
    }

    /** One-time "set your password" link for a new employee (REQUIREMENTS §4). */
    public void sendInvite(UUID employeeId) {
        Employee e = em.find(Employee.class, employeeId);
        int hours = settings.auth().inviteTtlHours();
        String token = issue(employeeId, AuthTokenPurpose.INVITE, Duration.ofHours(hours));
        mail.send(e.getEmail(), MailTemplates.invite(e.getFullName(), e.getCode(), appUrl + "/invite/" + token, hours));
    }

    public void sendPasswordReset(UUID employeeId) {
        Employee e = em.find(Employee.class, employeeId);
        int minutes = settings.auth().resetTtlMinutes();
        String token = issue(employeeId, AuthTokenPurpose.RESET, Duration.ofMinutes(minutes));
        mail.send(e.getEmail(), MailTemplates.reset(e.getFullName(), appUrl + "/reset/" + token, minutes));
    }

    /** The token if it exists, has this purpose, is unused, unexpired, and its employee hasn't exited. */
    public AuthToken findUsable(String token, AuthTokenPurpose purpose) {
        List<AuthToken> ts = em.createQuery("SELECT t FROM AuthToken t JOIN FETCH t.employee WHERE t.tokenHash = :h", AuthToken.class)
                .setParameter("h", Tokens.sha256(token)).getResultList();
        if (ts.isEmpty()) return null;
        AuthToken t = ts.get(0);
        if (t.getPurpose() != purpose || t.getUsedAt() != null || !t.getExpiresAt().isAfter(Instant.now())) return null;
        if (t.getEmployee().getStatus() == EmployeeStatus.EXITED) return null;
        return t;
    }
}
