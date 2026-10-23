package com.jurislpo.jurisone.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Comments on tasks (soft-deleted: the row stays, its body is hidden). */
@Repository
public class TaskCommentRepository {

    public record CommentRow(UUID id, UUID authorId, String body, List<Object> mentionedEmployeeIds, boolean isDeleted) {}

    private final JdbcTemplate jdbc;

    public TaskCommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A UUID[] literal, e.g. {@code {a,b}} (the values are UUIDs, so nothing needs quoting). */
    static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }

    /** One page, oldest first; a deleted comment has no body and no mentions. */
    public List<Map<String, Object>> findPage(UUID taskId, int limit, int offset) {
        return JdbcRows.normalize(jdbc.queryForList("""
            SELECT c.id::text AS "id", c.author_id::text AS "authorId", a.full_name AS "authorName", (extract(epoch FROM a.photo_updated_at) * 1000)::bigint AS "authorPhoto",
                   CASE WHEN c.deleted_at IS NULL THEN c.body END AS "body",
                   CASE WHEN c.deleted_at IS NULL THEN c.mentioned_employee_ids ELSE '{}'::uuid[] END AS "mentionedEmployeeIds",
                   %s AS "createdAt", %s AS "editedAt", c.deleted_at IS NOT NULL AS "isDeleted"
            FROM task_comments c JOIN employees a ON a.id = c.author_id WHERE c.task_id = ? ORDER BY c.created_at, c.id LIMIT ? OFFSET ?"""
                .formatted(TaskQuery.iso("c.created_at"), TaskQuery.iso("c.edited_at")), taskId, limit, offset));
    }

    public int count(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_comments WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public List<CommentRow> find(UUID commentId, UUID taskId) {
        return jdbc.query("SELECT id, author_id, body, mentioned_employee_ids, deleted_at IS NOT NULL AS is_deleted FROM task_comments WHERE id = ? AND task_id = ?",
                (resultSet, rowNumber) -> new CommentRow((UUID) resultSet.getObject("id"), (UUID) resultSet.getObject("author_id"), resultSet.getString("body"),
                        JdbcRows.list(resultSet.getArray("mentioned_employee_ids")), resultSet.getBoolean("is_deleted")), commentId, taskId);
    }

    public void insert(UUID id, UUID taskId, UUID authorId, String body, Collection<UUID> mentionedEmployeeIds) {
        jdbc.update("INSERT INTO task_comments (id, task_id, author_id, body, mentioned_employee_ids) VALUES (?, ?, ?, ?, ?::uuid[])",
                id, taskId, authorId, body, uuidArray(mentionedEmployeeIds));
    }

    public void updateBody(UUID id, String body, Collection<UUID> mentionedEmployeeIds) {
        jdbc.update("UPDATE task_comments SET body = ?, mentioned_employee_ids = ?::uuid[], edited_at = now() WHERE id = ?", body, uuidArray(mentionedEmployeeIds), id);
    }

    public void softDelete(UUID id, UUID actorId) {
        jdbc.update("UPDATE task_comments SET deleted_at = now(), deleted_by = ? WHERE id = ? AND deleted_at IS NULL", actorId, id);
    }
}
