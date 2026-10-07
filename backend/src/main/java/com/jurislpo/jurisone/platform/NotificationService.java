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

    /** Bell + email for a leave / regularization decision (REQUIREMENTS §5.14). Call after the decision has committed. */
    public void notifyDecision(UUID employeeId, String what, boolean approved, String by, String link, String note) {
        Employee e = em.find(Employee.class, employeeId);
        if (e == null) return;
        notify(employeeId, what + " " + (approved ? "approved" : "declined") + " by " + by, link);
        mail.send(e.getEmail(), MailTemplates.decision(e.getFullName(), what, approved, by, note));
    }
}
