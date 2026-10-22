package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.entity.EmailOutbox;
import com.jurislpo.jurisone.entity.EmailStatus;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The email outbox. */
@Repository
public class MailRepository {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;

    public MailRepository(JdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    /** Queues the row (needs a transaction); its id is set once persisted. */
    public void insert(EmailOutbox row) {
        entityManager.persist(row);
    }

    /** Sets the status and when it was sent (or logged). */
    public void markDone(UUID id, EmailStatus status, Timestamp sentAt) {
        jdbc.update("UPDATE email_outbox SET status = ?::\"EmailStatus\", sent_at = ? WHERE id = ?", status.name(), sentAt, id);
    }

    public void markFailed(UUID id, EmailStatus status, String error) {
        jdbc.update("UPDATE email_outbox SET status = ?::\"EmailStatus\", error = ? WHERE id = ?", status.name(), error, id);
    }
}
