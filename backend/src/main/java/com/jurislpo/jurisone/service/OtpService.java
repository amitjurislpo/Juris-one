package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.crypto.Tokens;
import com.jurislpo.jurisone.repository.OtpRepository;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email one-time codes for sign-in. Only a salted hash is stored; a code works once, for {@link #TTL_MINUTES} minutes
 * and {@link #MAX_ATTEMPTS} tries. While {@code jurisone.otp.fixed-code} is set (default {@code 000000}, until the
 * identity provider takes over) every code is that value; set it to empty to get random 6-digit codes.
 */
@Service
public class OtpService {

    public static final int TTL_MINUTES = 10;
    public static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OtpRepository repo;
    private final TransactionTemplate tx;
    private final MailService mail;
    private final String fixedCode;

    public OtpService(OtpRepository repo, TransactionTemplate tx, MailService mail, @Value("${jurisone.otp.fixed-code:}") String fixedCode) {
        this.repo = repo;
        this.tx = tx;
        this.mail = mail;
        this.fixedCode = fixedCode == null ? "" : fixedCode.trim();
    }

    static String hash(String email, String code) {
        return Tokens.sha256(email + ":" + code);
    }

    private String newCode() {
        return fixedCode.isEmpty() ? String.format("%06d", RANDOM.nextInt(1_000_000)) : fixedCode;
    }

    /** Creates a code for this (already verified-eligible) email and emails it. */
    public void issue(String email, String name, String ip) {
        String code = newCode();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        tx.executeWithoutResult(s -> {
            repo.invalidateOpen(email, now);
            repo.insert(email, hash(email, code), now.plus(Duration.ofMinutes(TTL_MINUTES)), ip);
        });
        mail.send(email, MailTemplates.otp(name, code, TTL_MINUTES));
    }

    /** True when {@code code} matches the newest open code for the email (which is then used up). Wrong tries are counted. */
    public boolean verify(String email, String code) {
        if (code == null || !code.matches("[0-9]{6}")) return false;
        return Boolean.TRUE.equals(tx.execute(s -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            OtpRepository.Open open = repo.findOpen(email, now);
            if (open == null || open.attempts() >= MAX_ATTEMPTS) return false;
            boolean ok = MessageDigest.isEqual(open.codeHash().getBytes(), hash(email, code).getBytes());
            if (ok) repo.markUsed(open.id(), now);
            else repo.addAttempt(open.id());
            return ok;
        }));
    }
}
