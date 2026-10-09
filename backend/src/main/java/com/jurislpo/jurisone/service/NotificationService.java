package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.Notification;
import com.jurislpo.jurisone.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications (bell) and decision emails — src/lib/notify.ts. */
@Service
@Transactional
public class NotificationService {

    public static final String KIND_POLICY = "POLICY";

    private final NotificationRepository repo;
    private final MailService mail;

    public NotificationService(NotificationRepository repo, MailService mail) {
        this.repo = repo;
        this.mail = mail;
    }

    /** In-app notification (bell); text is cut to 300 characters. Joins the caller's transaction. */
    public void notify(UUID employeeId, String text, String link) {
        Notification n = new Notification();
        n.setEmployeeId(employeeId);
        n.setText(text.substring(0, Math.min(300, text.length())));
        n.setLink(link);
        repo.persist(n);
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
        repo.persist(n);
    }

    /** Marks the policy's notifications read for one employee (null = everyone): accepted, replaced or archived. */
    public void clearPolicy(UUID employeeId, UUID policyId) {
        repo.markPolicyRead(employeeId, policyId);
    }

    /** Bell + email for a leave / regularization decision (REQUIREMENTS §5.14). Call after the decision has committed. */
    public void notifyDecision(UUID employeeId, String what, boolean approved, String by, String link, String note) {
        Employee e = repo.findEmployee(employeeId);
        if (e == null) return;
        notify(employeeId, what + " " + (approved ? "approved" : "declined") + " by " + by, link);
        mail.send(e.getEmail(), MailTemplates.decision(e.getFullName(), what, approved, by, note));
    }
}
