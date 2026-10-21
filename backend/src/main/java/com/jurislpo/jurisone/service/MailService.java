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
 * it is logged so sign-in codes and links can be copied. A failed send is recorded and never thrown.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final TransactionTemplate tx;
    private final MailRepository repo;
    private final String host, user, pass, from;
    private final int port;

    public MailService(TransactionTemplate tx, MailRepository repo,
                       @Value("${jurisone.mail.host:}") String host, @Value("${jurisone.mail.port:587}") int port, @Value("${jurisone.mail.user:}") String user,
                       @Value("${jurisone.mail.pass:}") String pass, @Value("${jurisone.mail.from:JurisOne <no-reply@jurislpo.com>}") String from) {
        this.tx = tx;
        this.repo = repo;
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        this.from = from;
    }

    /** A file sent with the mail (kept out of the outbox table: only the text is recorded there). */
    public record Attachment(String filename, byte[] data, String contentType) {}

    public void send(String to, MailTemplates.Content content) {
        send(to, content, null);
    }

    public void send(String to, MailTemplates.Content content, Attachment file) {
        java.util.UUID id = tx.execute(s -> {
            EmailOutbox row = new EmailOutbox();
            row.setTo(to);
            row.setSubject(content.subject());
            row.setText(content.text());
            row.setHtml(content.html());
            repo.insert(row);
            return row.getId();
        });
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (host == null || host.isBlank()) {
            log.info("\n✉  [email:dev] to={}\n   subject={}\n   {}\n", to, content.subject(), content.text().replace("\n", "\n   "));
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
            Properties properties = sender.getJavaMailProperties();
            properties.put("mail.smtp.auth", String.valueOf(!user.isBlank()));
            if (port == 465) properties.put("mail.smtp.ssl.enable", "true");
            else properties.put("mail.smtp.starttls.enable", "true");
            MimeMessage mimeMessage = sender.createMimeMessage();
            MimeMessageHelper mimeMessageHelper = new MimeMessageHelper(mimeMessage, content.html() != null || file != null, "UTF-8");
            mimeMessageHelper.setFrom(from);
            mimeMessageHelper.setTo(to);
            mimeMessageHelper.setSubject(content.subject());
            if (content.html() != null) mimeMessageHelper.setText(content.text(), content.html());
            else mimeMessageHelper.setText(content.text());
            if (file != null) mimeMessageHelper.addAttachment(file.filename(), new org.springframework.core.io.ByteArrayResource(file.data()), file.contentType());
            sender.send(mimeMessage);
            repo.markDone(id, EmailStatus.SENT, java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        } catch (Exception err) {
            String msg = String.valueOf(err);
            repo.markFailed(id, EmailStatus.FAILED, msg.substring(0, Math.min(1000, msg.length())));
            log.error("[email] send failed", err);
        }
    }
}
