package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.AuthToken;
import com.jurislpo.jurisone.entity.AuthTokenPurpose;
import com.jurislpo.jurisone.entity.Employee;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One-time invite / reset link rows ({@code auth_tokens}). */
@Repository
public class AuthTokenRepository {

    private final EntityManager em;
    private final JdbcTemplate jdbc;

    public AuthTokenRepository(EntityManager em, JdbcTemplate jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    /** Marks every unused link of this kind of the employee as used at {@code usedAt}. */
    public void markUnusedAsUsed(UUID employeeId, AuthTokenPurpose purpose, Instant usedAt) {
        jdbc.update("UPDATE auth_tokens SET used_at = ? WHERE employee_id = ? AND purpose = ?::\"AuthTokenPurpose\" AND used_at IS NULL",
                Timestamp.from(usedAt), employeeId, purpose.name());
    }

    public void persist(AuthToken t) {
        em.persist(t);
    }

    public Employee findEmployee(UUID employeeId) {
        return em.find(Employee.class, employeeId);
    }

    /** The token rows (zero or one) with this hash, their employee fetched. */
    public List<AuthToken> findByHash(String tokenHash) {
        return em.createQuery("SELECT t FROM AuthToken t JOIN FETCH t.employee WHERE t.tokenHash = :h", AuthToken.class)
                .setParameter("h", tokenHash).getResultList();
    }
}
