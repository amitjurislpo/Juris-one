package com.jurislpo.jurisone.platform;

import com.jurislpo.jurisone.entity.EmailOutbox;
import com.jurislpo.jurisone.entity.EmailStatus;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every email is recorded in {@code email_outbox} (src/lib/email.ts). With SMTP configured it is sent; without it (dev)
 * it is logged so invite / reset links can be copied. A failed send is recorded and never thrown.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final jakarta.persistence.EntityManager em;
    private final String host, user, pass, from;
    private final int port;

    public MailService(TransactionTemplate tx, JdbcTemplate jdbc, jakarta.persistence.EntityManager em,
                       @Value("${SMTP_HOST:}") String host, @Value("${SMTP_PORT:587}") int port, @Value("${SMTP_USER:}") String user,
                       @Value("${SMTP_PASS:}") String pass, @Value("${MAIL_FROM:JurisOne <no-reply@jurislpo.com>}") String from) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.em = em;
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        this.from = from;
    }

    public void send(String to, MailTemplates.Content c) {
        java.util.UUID id = tx.execute(s -> {
            EmailOutbox row = new EmailOutbox();
            row.setTo(to);
            row.setSubject(c.subject());
            row.setText(c.text());
            row.setHtml(c.html());
            em.persist(row);
            return row.getId();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (host == null || host.isBlank()) {
            log.info("\n✉  [email:dev] to={}\n   subject={}\n   {}\n", to, c.subject(), c.text().replace("\n", "\n   "));
            jdbc.update("UPDATE email_outbox SET status = ?::\"EmailStatus\", sent_at = ? WHERE id = ?", EmailStatus.LOGGED.name(), java.sql.Timestamp.from(now), id);
            return;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            if (!user.isBlank()) {
                sender.setUsername(user);
                sender.setPassword(pass);
            }
            Properties p = sender.getJavaMailProperties();
            p.put("mail.smtp.auth", String.valueOf(!user.isBlank()));
            if (port == 465) p.put("mail.smtp.ssl.enable", "true");
            else p.put("mail.smtp.starttls.enable", "true");
            MimeMessage m = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, c.html() != null, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(c.subject());
            if (c.html() != null) h.setText(c.text(), c.html());
            else h.setText(c.text());
            sender.send(m);
            jdbc.update("UPDATE email_outbox SET status = ?::\"EmailStatus\", sent_at = ? WHERE id = ?", EmailStatus.SENT.name(),
                    java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)), id);
        } catch (Exception err) {
            String msg = String.valueOf(err);
            jdbc.update("UPDATE email_outbox SET status = ?::\"EmailStatus\", error = ? WHERE id = ?", EmailStatus.FAILED.name(),
                    msg.substring(0, Math.min(1000, msg.length())), id);
            log.error("[email] send failed", err);
        }
    }
}
