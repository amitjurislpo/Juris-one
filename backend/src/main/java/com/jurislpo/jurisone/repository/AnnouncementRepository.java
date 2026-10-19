package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Announcements (tenant-scoped by row-level security). */
@Repository
public class AnnouncementRepository {

    private static final String ISO = "YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"";
    private static final String COLS = """
        SELECT n.id::text AS "id", n.title AS "title", n.body AS "body", n.audience AS "audience", n.pinned AS "pinned",
               n.expires_on::text AS "expiresOn", n.status AS "status", c.full_name AS "author",
               to_char(n.created_at AT TIME ZONE 'UTC', '%s') AS "createdAt",
               (n.status = 'PUBLISHED' AND (n.expires_on IS NULL OR n.expires_on >= current_date)) AS "active"
        FROM announcements n LEFT JOIN employees c ON c.id = n.created_by""".formatted(ISO);

    private final JdbcTemplate jdbc;

    public AnnouncementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What is shown to someone in {@code region} now: published, not expired, for everyone or their region; pinned first. */
    public List<Map<String, Object>> findActive(String region, int limit) {
        return jdbc.queryForList(COLS + " " + """
             WHERE n.status = 'PUBLISHED' AND (n.expires_on IS NULL OR n.expires_on >= current_date)
               AND (n.audience = 'ALL' OR n.audience = ?)
             ORDER BY n.pinned DESC, n.created_at DESC LIMIT ?""", region, limit);
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList(COLS + " ORDER BY n.created_at DESC");
    }

    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList(COLS + " WHERE n.id = ?", id);
    }

    public void insert(UUID id, String title, String body, String audience, boolean pinned, LocalDate expiresOn, UUID by) {
        jdbc.update("INSERT INTO announcements (id, title, body, audience, pinned, expires_on, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, title, body, audience, pinned, expiresOn == null ? null : Date.valueOf(expiresOn), by);
    }

    public void update(UUID id, String title, String body, String audience, boolean pinned, LocalDate expiresOn) {
        jdbc.update("UPDATE announcements SET title = ?, body = ?, audience = ?, pinned = ?, expires_on = ?, updated_at = now() WHERE id = ?",
                title, body, audience, pinned, expiresOn == null ? null : Date.valueOf(expiresOn), id);
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("UPDATE announcements SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    /** Employees who are told about an announcement for {@code audience} (everyone who hasn't left, or one region). */
    public List<UUID> findRecipients(String audience) {
        return jdbc.queryForList("SELECT id FROM employees WHERE status <> 'EXITED' AND (? = 'ALL' OR region::text = ?) ORDER BY code", UUID.class, audience, audience);
    }
}
