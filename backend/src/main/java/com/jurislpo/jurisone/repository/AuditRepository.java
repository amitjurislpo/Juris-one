package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.AuditLog;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** The audit log. Joins the caller's transaction. */
@Repository
public class AuditRepository {

    private final EntityManager entityManager;

    public AuditRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void persist(AuditLog auditLog) {
        entityManager.persist(auditLog);
    }

    /**
     * Flushes the pending insert, then tags the row (still without a business) with the actor's business — for events
     * written outside a business request.
     */
    public void assignActorBusiness(UUID auditId, UUID actorId) {
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE audit_log SET business_id = (SELECT business_id FROM employees WHERE id = ?1) WHERE id = ?2 AND business_id IS NULL")
                .setParameter(1, actorId).setParameter(2, auditId).executeUpdate();
    }
}
