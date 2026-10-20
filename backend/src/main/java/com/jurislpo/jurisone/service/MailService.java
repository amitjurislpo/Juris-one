package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.entity.EmailOutbox;
import com.jurislpo.jurisone.entity.EmailStatus;
import com.jurislpo.jurisone.repository.MailRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every email is recorded in {@code email_outbox}. With SMTP configured it is sent; without it (dev)
 * it is logged so invite / reset links can be copied. A failed send is recorded and never thrown.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final TransactionTemplate tx;
    private final MailRepository repo;
    private final String host, user, pass, from;
    private final int port;

    public MailService(TransactionTemplate tx, MailRepository repo,
                       @Value("${SMTP_HOST:}") String host, @Value("${SMTP_PORT:587}") int port, @Value("${SMTP_USER:}") String user,
                       @Value("${SMTP_PASS:}") String pass, @Value("${MAIL_FROM:JurisOne <no-reply@jurislpo.com>}") String from) {
        this.tx = tx;
        this.repo = repo;
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
            repo.insert(row);
            return row.getId();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (host == null || host.isBlank()) {
            log.info("\n✉  [email:dev] to={}\n   subject={}\n   {}\n", to, c.subject(), c.text().replace("\n", "\n   "));
            repo.markDone(id, EmailStatus.LOGGED, java.sql.Timestamp.from(now));
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
            repo.markDone(id, EmailStatus.SENT, java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        } catch (Exception err) {
            String msg = String.valueOf(err);
            repo.markFailed(id, EmailStatus.FAILED, msg.substring(0, Math.min(1000, msg.length())));
            log.error("[email] send failed", err);
        }
    }
}
