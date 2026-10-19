package com.jurislpo.jurisone.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** The {@code login_otps} table: one row per emailed sign-in code (system mode; sign-in has no business yet). */
@Repository
public class OtpRepository {

    /** The newest code for an email that is still usable. */
    public record Open(UUID id, String codeHash, int attempts) {}

    private final EntityManager em;

    public OtpRepository(EntityManager em) {
        this.em = em;
    }

    /** Older unused codes stop working once a new one is sent. */
    public void invalidateOpen(String email, Instant now) {
        em.createNativeQuery("UPDATE login_otps SET used_at = :now WHERE email = :e AND used_at IS NULL")
                .setParameter("now", now).setParameter("e", email).executeUpdate();
    }

    public void insert(String email, String codeHash, Instant expiresAt, String ip) {
        em.createNativeQuery("INSERT INTO login_otps (email, code_hash, expires_at, ip) VALUES (:e, :h, :x, :ip)")
                .setParameter("e", email).setParameter("h", codeHash).setParameter("x", expiresAt).setParameter("ip", ip).executeUpdate();
    }

    public Open findOpen(String email, Instant now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT id, code_hash, attempts FROM login_otps
                WHERE email = :e AND used_at IS NULL AND expires_at > :now
                ORDER BY created_at DESC LIMIT 1""").setParameter("e", email).setParameter("now", now).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);
        return new Open((UUID) r[0], (String) r[1], ((Number) r[2]).intValue());
    }

    public void addAttempt(UUID id) {
        em.createNativeQuery("UPDATE login_otps SET attempts = attempts + 1 WHERE id = :id").setParameter("id", id).executeUpdate();
    }

    public void markUsed(UUID id, Instant now) {
        em.createNativeQuery("UPDATE login_otps SET used_at = :now WHERE id = :id").setParameter("now", now).setParameter("id", id).executeUpdate();
    }
}
