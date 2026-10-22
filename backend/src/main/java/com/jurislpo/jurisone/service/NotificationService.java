package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.Notification;
import com.jurislpo.jurisone.repository.NotificationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications (bell) and decision emails. */
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
        Notification notification = new Notification();
        notification.setEmployeeId(employeeId);
        notification.setText(text.substring(0, Math.min(300, text.length())));
        notification.setLink(link);
        repo.persist(notification);
    }

    /** A notification that stays unread until the employee accepts the policy (replaces an older one for the same policy). */
    public void notifyPolicy(UUID employeeId, String text, String link, UUID policyId) {
        clearPolicy(employeeId, policyId);
        Notification notification = new Notification();
        notification.setEmployeeId(employeeId);
        notification.setText(text.substring(0, Math.min(300, text.length())));
        notification.setLink(link);
        notification.setKind(KIND_POLICY);
        notification.setRefId(policyId);
        repo.persist(notification);
    }

    /** Marks the policy's notifications read for one employee (null = everyone): accepted, replaced or archived. */
    public void clearPolicy(UUID employeeId, UUID policyId) {
        repo.markPolicyRead(employeeId, policyId);
    }

    /** Bell + email for a leave / regularization decision (REQUIREMENTS §5.14). Call after the decision has committed. */
    public void notifyDecision(UUID employeeId, String what, boolean approved, String by, String link, String note) {
        Employee employee = repo.findEmployee(employeeId);
        if (employee == null) return;
        notify(employeeId, what + " " + (approved ? "approved" : "declined") + " by " + by, link);
        mail.send(employee.getEmail(), MailTemplates.decision(employee.getFullName(), what, approved, by, note));
    }
}
