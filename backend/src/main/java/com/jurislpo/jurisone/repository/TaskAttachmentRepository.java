package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Files attached to tasks (soft-deleted: the row and the stored file stay for the audit trail). */
@Repository
public class TaskAttachmentRepository {

    public record AttachmentRow(UUID id, String filePath, String fileName, String contentType, UUID uploadedBy) {}

    private final JdbcTemplate jdbc;

    public TaskAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The task's attachments (not deleted), newest first, with {@code uploadedById} for the delete right. */
    public List<Map<String, Object>> findForTask(UUID taskId) {
        return jdbc.queryForList("""
            SELECT a.id::text AS "id", a.file_name AS "fileName", a.content_type AS "contentType", a.size_bytes AS "sizeBytes", u.full_name AS "uploadedByName",
                   a.uploaded_by::text AS "uploadedById", %s AS "uploadedAt"
            FROM task_attachments a LEFT JOIN employees u ON u.id = a.uploaded_by WHERE a.task_id = ? AND a.deleted_at IS NULL ORDER BY a.uploaded_at DESC"""
                .formatted(TaskQuery.iso("a.uploaded_at")), taskId);
    }

    public int count(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_attachments WHERE task_id = ? AND deleted_at IS NULL", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public List<AttachmentRow> find(UUID attachmentId, UUID taskId) {
        return jdbc.query("SELECT id, file_path, file_name, content_type, uploaded_by FROM task_attachments WHERE id = ? AND task_id = ? AND deleted_at IS NULL",
                (resultSet, rowNumber) -> new AttachmentRow((UUID) resultSet.getObject("id"), resultSet.getString("file_path"), resultSet.getString("file_name"),
                        resultSet.getString("content_type"), (UUID) resultSet.getObject("uploaded_by")), attachmentId, taskId);
    }

    public void insert(UUID id, UUID taskId, String filePath, String fileName, String contentType, long sizeBytes, String sha256, UUID uploadedBy) {
        jdbc.update("INSERT INTO task_attachments (id, task_id, file_path, file_name, content_type, size_bytes, sha256, uploaded_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, taskId, filePath, fileName, contentType, sizeBytes, sha256, uploadedBy);
    }

    public void softDelete(UUID id, UUID actorId) {
        jdbc.update("UPDATE task_attachments SET deleted_at = now(), deleted_by = ? WHERE id = ? AND deleted_at IS NULL", actorId, id);
    }
}
