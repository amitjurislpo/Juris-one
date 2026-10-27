package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Rows of {@code onboarding_attachments} (business_id comes from the tenant default of the table). */
@Repository
public class OnboardingAttachmentRepository {

    private final JdbcTemplate jdbc;

    public OnboardingAttachmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID onboardingId, String slotKey, String fileName, String mime, long sizeBytes, String sha256, String storagePath) {
        jdbc.update("""
            INSERT INTO onboarding_attachments (id, onboarding_id, slot_key, file_name, mime, size_bytes, sha256, storage_path) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, onboardingId, slotKey, fileName, mime, sizeBytes, sha256, storagePath);
    }

    public Map<String, Object> findInOnboarding(UUID id, UUID onboardingId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM onboarding_attachments WHERE id = ? AND onboarding_id = ?", id, onboardingId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> findAllOf(UUID onboardingId) {
        return jdbc.queryForList("SELECT * FROM onboarding_attachments WHERE onboarding_id = ? ORDER BY uploaded_at, id", onboardingId);
    }

    public List<Map<String, Object>> findBySlotKeyPrefix(UUID onboardingId, String slotKeyPrefix) {
        return jdbc.queryForList("SELECT * FROM onboarding_attachments WHERE onboarding_id = ? AND starts_with(slot_key, ?)", onboardingId, slotKeyPrefix);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM onboarding_attachments WHERE id = ?", id);
    }

}
