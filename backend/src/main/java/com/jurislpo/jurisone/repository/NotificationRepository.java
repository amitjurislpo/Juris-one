package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.Notification;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** In-app notifications (bell). Joins the caller's transaction. */
@Repository
public class NotificationRepository {

    private final EntityManager entityManager;

    public NotificationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void persist(Notification notification) {
        entityManager.persist(notification);
    }

    /** Flushes pending inserts, then marks the policy's unread notifications read for one employee (null = everyone). */
    public void markPolicyRead(UUID employeeId, UUID policyId) {
        entityManager.flush();
        String sql = "UPDATE notifications SET read_at = now() WHERE kind = 'POLICY' AND ref_id = ?1 AND read_at IS NULL";
        if (employeeId == null) entityManager.createNativeQuery(sql).setParameter(1, policyId).executeUpdate();
        else entityManager.createNativeQuery(sql + " AND employee_id = ?2").setParameter(1, policyId).setParameter(2, employeeId).executeUpdate();
    }

    /** The employee, or null. */
    public Employee findEmployee(UUID employeeId) {
        return entityManager.find(Employee.class, employeeId);
    }
}
