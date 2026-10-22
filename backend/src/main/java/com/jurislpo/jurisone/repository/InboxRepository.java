package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.InboxService;
import com.jurislpo.jurisone.service.InboxService.Item;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The signed-in user's notifications. */
@Repository
public class InboxRepository {

    private final JdbcTemplate jdbc;

    public InboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Integer countUnread(UUID employeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND read_at IS NULL", Integer.class, employeeId);
    }

    private static String where(boolean unreadOnly) {
        return "employee_id = ?" + (unreadOnly ? " AND read_at IS NULL" : "");
    }

    /** The employee's notifications (or only the unread ones). */
    public Integer count(UUID employeeId, boolean unreadOnly) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE " + where(unreadOnly), Integer.class, employeeId);
    }

    /** One page: unread first, then newest. */
    public List<Item> page(UUID employeeId, boolean unreadOnly, int size, int offset) {
        return jdbc.query("SELECT id::text, text, link, created_at, read_at, kind FROM notifications WHERE " + where(unreadOnly)
                + " ORDER BY read_at IS NOT NULL, created_at DESC LIMIT ? OFFSET ?", (resultSet, i) -> InboxService.item(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
                resultSet.getTimestamp(4), resultSet.getTimestamp(5), resultSet.getString(6)), employeeId, size, offset);
    }

    /** kind and read_at of one of the employee's notifications (empty when not theirs / not found). */
    public List<Map<String, Object>> findKindAndReadAt(UUID id, UUID employeeId) {
        return jdbc.queryForList("SELECT kind, read_at FROM notifications WHERE id = ? AND employee_id = ?", id, employeeId);
    }

    /** Read keeps an earlier read time; unread clears it. */
    public void setRead(UUID id, boolean read) {
        jdbc.update("UPDATE notifications SET read_at = " + (read ? "COALESCE(read_at, now())" : "NULL") + " WHERE id = ?", id);
    }

    /** Marks all unread read, except policy notifications. Returns how many. */
    public int markAllRead(UUID employeeId) {
        return jdbc.update("UPDATE notifications SET read_at = now() WHERE employee_id = ? AND read_at IS NULL AND kind IS DISTINCT FROM 'POLICY'", employeeId);
    }

    /** The newest n: unread first. */
    public List<Item> recent(UUID employeeId, int count) {
        return jdbc.query("SELECT id::text, text, link, created_at, read_at, kind FROM notifications WHERE employee_id = ? ORDER BY read_at IS NOT NULL, created_at DESC LIMIT ?",
                (resultSet, i) -> InboxService.item(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getTimestamp(4), resultSet.getTimestamp(5), resultSet.getString(6)), employeeId, count);
    }
}
