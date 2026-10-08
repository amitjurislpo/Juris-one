package com.jurislpo.jurisone.platform;

import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.Notification;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications (bell) and decision emails — src/lib/notify.ts. */
@Service
@Transactional
public class NotificationService {

    public static final String KIND_POLICY = "POLICY";

    private final EntityManager em;
    private final MailService mail;

    public NotificationService(EntityManager em, MailService mail) {
        this.em = em;
        this.mail = mail;
    }

    /** In-app notification (bell); text is cut to 300 characters. Joins the caller's transaction. */
    public void notify(UUID employeeId, String text, String link) {
        Notification n = new Notification();
        n.setEmployeeId(employeeId);
        n.setText(text.substring(0, Math.min(300, text.length())));
        n.setLink(link);
        em.persist(n);
    }

    /** A notification that stays unread until the employee accepts the policy (replaces an older one for the same policy). */
    public void notifyPolicy(UUID employeeId, String text, String link, UUID policyId) {
        clearPolicy(employeeId, policyId);
        Notification n = new Notification();
        n.setEmployeeId(employeeId);
        n.setText(text.substring(0, Math.min(300, text.length())));
        n.setLink(link);
        n.setKind(KIND_POLICY);
        n.setRefId(policyId);
        em.persist(n);
    }

    /** Marks the policy's notifications read for one employee (null = everyone): accepted, replaced or archived. */
    public void clearPolicy(UUID employeeId, UUID policyId) {
        em.flush();
        String sql = "UPDATE notifications SET read_at = now() WHERE kind = 'POLICY' AND ref_id = ?1 AND read_at IS NULL";
        if (employeeId == null) em.createNativeQuery(sql).setParameter(1, policyId).executeUpdate();
        else em.createNativeQuery(sql + " AND employee_id = ?2").setParameter(1, policyId).setParameter(2, employeeId).executeUpdate();
    }

    /** Bell + email for a leave / regularization decision (REQUIREMENTS §5.14). Call after the decision has committed. */
    public void notifyDecision(UUID employeeId, String what, boolean approved, String by, String link, String note) {
        Employee e = em.find(Employee.class, employeeId);
        if (e == null) return;
        notify(employeeId, what + " " + (approved ? "approved" : "declined") + " by " + by, link);
        mail.send(e.getEmail(), MailTemplates.decision(e.getFullName(), what, approved, by, note));
    }
}
