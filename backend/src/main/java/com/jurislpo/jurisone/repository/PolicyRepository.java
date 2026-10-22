package com.jurislpo.jurisone.repository;

import static com.jurislpo.jurisone.service.PolicyService.formatIsoTimestamp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Company policies, their published versions and the acknowledgements. */
@Repository
public class PolicyRepository {

    /** Employees who must acknowledge (current, not owners). */
    static final String REQUIRED = """
        SELECT e.id, e.code, e.full_name, d.name AS department FROM employees e JOIN roles r ON r.id = e.role_id
        LEFT JOIN departments d ON d.id = e.department_id
        WHERE e.business_id = jurisone_tenant_id() AND e.status <> 'EXITED' AND r.key NOT IN ('owner', 'super_admin')""";

    private final JdbcTemplate jdbc;

    public PolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Current versions that need acceptance and the employee hasn't accepted (oldest first): id, version, title, body,
     * fileName, publishedAt.
     */
    public List<Map<String, Object>> findPending(UUID employeeId) {
        return jdbc.query("""
            SELECT p.id::text, v.version, v.title, v.body, v.file_name, v.published_at
            FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' AND v.requires_ack
              AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?)
            ORDER BY v.published_at, v.title""", (resultSet, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", resultSet.getString(1));
            row.put("version", resultSet.getInt(2));
            row.put("title", resultSet.getString(3));
            row.put("body", resultSet.getString(4));
            row.put("fileName", resultSet.getString(5));
            row.put("publishedAt", formatIsoTimestamp(resultSet.getTimestamp(6)));
            return row;
        }, employeeId);
    }

    public Boolean hasPending(UUID employeeId) {
        return jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
              WHERE p.status = 'PUBLISHED' AND v.requires_ack
                AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?))""", Boolean.class, employeeId);
    }

    public Integer countPending(UUID employeeId) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' AND v.requires_ack
              AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?)""", Integer.class, employeeId);
    }

    /**
     * Every published policy's current version by title: id, version, title, body, fileName, publishedAt,
     * requiresAcknowledgement, acknowledgedAt (this employee's acceptance).
     */
    public List<Map<String, Object>> findPublished(UUID employeeId) {
        return jdbc.query("""
            SELECT p.id::text, v.version, v.title, v.body, v.file_name, v.published_at, v.requires_ack,
                   (SELECT a.acknowledged_at FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?) AS acked_at
            FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' ORDER BY v.title""", (resultSet, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", resultSet.getString(1));
            row.put("version", resultSet.getInt(2));
            row.put("title", resultSet.getString(3));
            row.put("body", resultSet.getString(4));
            row.put("fileName", resultSet.getString(5));
            row.put("publishedAt", formatIsoTimestamp(resultSet.getTimestamp(6)));
            row.put("requiresAcknowledgement", resultSet.getBoolean(7));
            row.put("acknowledgedAt", formatIsoTimestamp(resultSet.getTimestamp(8)));
            return row;
        }, employeeId);
    }

    /** id, version, title, body_sha256, file_sha256 of the current version and the policy's status (empty when not found). */
    public List<Map<String, Object>> findCurrentVersion(UUID policyId) {
        return jdbc.queryForList("""
            SELECT v.id, v.version, v.title, v.body_sha256, v.file_sha256, p.status FROM policies p
            JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version WHERE p.id = ?""", policyId);
    }

    /** code, full_name and email of the employee. */
    public Map<String, Object> findEmployeeSnapshot(UUID employeeId) {
        return jdbc.queryForMap("SELECT code, full_name, email FROM employees WHERE id = ?", employeeId);
    }

    /** Records an acknowledgement; returns 0 when this version was already acknowledged by the employee. */
    public int insertAcknowledgement(UUID policyId, Object versionId, Integer version, Object title, Object bodySha256, Object fileSha256,
                                     UUID employeeId, Object employeeCode, Object employeeName, Object employeeEmail, String ipAddress, String userAgent) {
        return jdbc.update("""
            INSERT INTO policy_acknowledgements (id, policy_id, policy_version_id, version, policy_title, body_sha256, file_sha256,
              employee_id, employee_code, employee_name, employee_email, ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (policy_version_id, employee_id) DO NOTHING""",
                UUID.randomUUID(), policyId, versionId, version, title, bodySha256, fileSha256,
                employeeId, employeeCode, employeeName, employeeEmail, ipAddress, userAgent);
    }

    /** How many employees must acknowledge. */
    public Integer countRequired() {
        return jdbc.queryForObject("SELECT count(*) FROM (" + REQUIRED + ") x", Integer.class);
    }

    /**
     * A policy's draft with its current version ({@code versionId} and the {@code v…} fields null before the first
     * publish); timestamps as ISO strings.
     */
    public record ListRow(String id, String title, String status, int version, boolean requiresAcknowledgement, String updatedAt, String publishedAt, UUID versionId,
                          String versionTitle, String versionBodySha, String versionFileSha, boolean versionRequiresAcknowledgement, String body, String fileSha) {}

    /** Every policy with its current version, archived last, then by title. */
    public List<ListRow> findAll() {
        return jdbc.query("""
            SELECT p.id::text, p.title, p.status, p.version, p.requires_ack, p.updated_at, p.published_at,
                   v.id AS version_id, v.title AS version_title, v.body_sha256, v.file_sha256 AS version_file_sha, v.requires_ack AS version_requires_acknowledgement, p.body, p.file_sha256
            FROM policies p LEFT JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            ORDER BY p.status = 'ARCHIVED', p.title""", (resultSet, i) -> new ListRow(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getInt(4), resultSet.getBoolean(5),
                formatIsoTimestamp(resultSet.getTimestamp(6)), formatIsoTimestamp(resultSet.getTimestamp(7)), (UUID) resultSet.getObject(8), resultSet.getString(9), resultSet.getString(10), resultSet.getString(11),
                resultSet.getBoolean(12), resultSet.getString(13), resultSet.getString(14)));
    }

    /** How many of the employees who must acknowledge have acknowledged the version. */
    public Integer countAcknowledged(UUID versionId) {
        return jdbc.queryForObject("SELECT count(*) FROM (" + REQUIRED + ") x WHERE EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = ? AND a.employee_id = x.id)",
                Integer.class, versionId);
    }

    /** The policy row (empty when not found). */
    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList("SELECT * FROM policies WHERE id = ?", id);
    }

    /**
     * The policy's versions, newest first: versionId, version, title, publishedAt, publishedBy, fileName, requiresAcknowledgement,
     * bodySha256, fileSha256, acknowledgements.
     */
    public List<Map<String, Object>> findVersions(UUID policyId) {
        return jdbc.query("""
            SELECT v.id, v.version, v.title, v.published_at, e.full_name, v.file_name, v.requires_ack, v.body_sha256, v.file_sha256,
                   (SELECT count(*) FROM policy_acknowledgements a WHERE a.policy_version_id = v.id) AS acks
            FROM policy_versions v LEFT JOIN employees e ON e.id = v.published_by WHERE v.policy_id = ? ORDER BY v.version DESC""", (resultSet, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("versionId", resultSet.getString(1));
            row.put("version", resultSet.getInt(2));
            row.put("title", resultSet.getString(3));
            row.put("publishedAt", formatIsoTimestamp(resultSet.getTimestamp(4)));
            row.put("publishedBy", resultSet.getString(5));
            row.put("fileName", resultSet.getString(6));
            row.put("requiresAcknowledgement", resultSet.getBoolean(7));
            row.put("bodySha256", resultSet.getString(8));
            row.put("fileSha256", resultSet.getString(9));
            row.put("acknowledgements", resultSet.getLong(10));
            return row;
        }, policyId);
    }

    /**
     * Everyone who must acknowledge, those still to accept first, then by name: code, name, department,
     * acknowledgedAt (of the given version), lastVersion (latest they accepted of this policy).
     */
    public List<Map<String, Object>> findRequiredPeople(Object versionId, UUID policyId) {
        return jdbc.query("""
            SELECT * FROM (SELECT x.code, x.full_name, x.department,
                   (SELECT a.acknowledged_at FROM policy_acknowledgements a WHERE a.policy_version_id = ?::uuid AND a.employee_id = x.id) AS acked_at,
                   (SELECT max(a.version) FROM policy_acknowledgements a WHERE a.policy_id = ? AND a.employee_id = x.id) AS last_version
            FROM (""" + REQUIRED + ") x) y ORDER BY y.acked_at IS NOT NULL, y.full_name", (resultSet, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", resultSet.getString(1));
            row.put("name", resultSet.getString(2));
            row.put("department", resultSet.getString(3));
            row.put("acknowledgedAt", formatIsoTimestamp(resultSet.getTimestamp(4)));
            row.put("lastVersion", resultSet.getObject(5));
            return row;
        }, versionId, policyId);
    }

    public void insert(UUID id, String title, String body, boolean requiresAcknowledgement, UUID createdBy) {
        jdbc.update("INSERT INTO policies (id, title, body, requires_ack, created_by) VALUES (?, ?, ?, ?, ?)", id, title, body, requiresAcknowledgement, createdBy);
    }

    /** Saves the draft. */
    public void updateDraft(UUID id, String title, String body, boolean requiresAcknowledgement) {
        jdbc.update("UPDATE policies SET title = ?, body = ?, requires_ack = ?, updated_at = now() WHERE id = ?", title, body, requiresAcknowledgement, id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM policies WHERE id = ?", id);
    }

    /** Sets the draft's PDF. */
    public void setFile(UUID id, String filePath, String fileName, String fileSha256) {
        jdbc.update("UPDATE policies SET file_path = ?, file_name = ?, file_sha256 = ?, updated_at = now() WHERE id = ?",
                filePath, fileName, fileSha256, id);
    }

    /** Removes the draft's PDF. */
    public void clearFile(UUID id) {
        jdbc.update("UPDATE policies SET file_path = NULL, file_name = NULL, file_sha256 = NULL, updated_at = now() WHERE id = ?", id);
    }

    public void archive(UUID id) {
        jdbc.update("UPDATE policies SET status = 'ARCHIVED', archived_at = now(), updated_at = now() WHERE id = ?", id);
    }

    /** title, body_sha256, file_sha256, requires_ack of a published version. */
    public Map<String, Object> findVersionHashes(UUID policyId, int version) {
        return jdbc.queryForMap("SELECT title, body_sha256, file_sha256, requires_ack FROM policy_versions WHERE policy_id = ? AND version = ?", policyId, version);
    }

    /** Publishes an archived policy again as its current version. */
    public void republish(UUID id) {
        jdbc.update("UPDATE policies SET status = 'PUBLISHED', archived_at = NULL, updated_at = now() WHERE id = ?", id);
    }

    public void insertVersion(UUID policyId, int version, Object title, String body, String bodySha256, Object filePath, Object fileName, Object fileSha256,
                              Object requiresAcknowledgement, UUID publishedBy) {
        jdbc.update("""
            INSERT INTO policy_versions (id, policy_id, version, title, body, body_sha256, file_path, file_name, file_sha256, requires_ack, published_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), policyId, version, title, body, bodySha256, filePath, fileName, fileSha256, requiresAcknowledgement, publishedBy);
    }

    /** Marks the policy published at the given version. */
    public void markPublished(UUID id, int version) {
        jdbc.update("UPDATE policies SET status = 'PUBLISHED', version = ?, published_at = now(), archived_at = NULL, updated_at = now() WHERE id = ?", version, id);
    }

    /** Ids of the employees who must acknowledge. */
    public List<UUID> findRequiredIds() {
        return jdbc.queryForList("SELECT id FROM (" + REQUIRED + ") x", UUID.class);
    }

    public UUID findVersionId(UUID policyId, Object version) {
        return jdbc.queryForObject("SELECT id FROM policy_versions WHERE policy_id = ? AND version = ?", UUID.class, policyId, version);
    }

    /** Ids of the employees who must acknowledge and haven't acknowledged the version. */
    public List<UUID> findNotAcknowledged(UUID versionId) {
        return jdbc.queryForList("SELECT x.id FROM (" + REQUIRED + ") x WHERE NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = ? AND a.employee_id = x.id)",
                UUID.class, versionId);
    }

    /**
     * Every acknowledgement of the policy by version and time, as evidence rows: policy title, version, accepted at
     * (ISO), employee code, name, email, IP, browser, text SHA-256, PDF SHA-256, acknowledgement id.
     */
    public List<List<Object>> findAcknowledgements(UUID policyId) {
        return jdbc.query("""
            SELECT policy_title, version, acknowledged_at, employee_code, employee_name, employee_email, ip, user_agent, body_sha256, file_sha256, id::text
            FROM policy_acknowledgements WHERE policy_id = ? ORDER BY version, acknowledged_at""", (resultSet, i) -> {
            List<Object> values = new ArrayList<>();
            values.add(resultSet.getString(1));
            values.add(resultSet.getInt(2));
            values.add(formatIsoTimestamp(resultSet.getTimestamp(3)));
            for (int column = 4; column <= 11; column++) values.add(resultSet.getString(column));
            return values;
        }, policyId);
    }

    /** file_path and file_name of the draft (empty when not found). */
    public List<Map<String, Object>> findDraftFile(UUID id) {
        return jdbc.queryForList("SELECT file_path, file_name FROM policies WHERE id = ?", id);
    }

    /** file_path and file_name of a version; unless {@code anyStatus}, only while the policy is published. */
    public List<Map<String, Object>> findVersionFile(UUID id, Integer version, boolean anyStatus) {
        return jdbc.queryForList("""
            SELECT v.file_path, v.file_name FROM policy_versions v JOIN policies p ON p.id = v.policy_id
            WHERE v.policy_id = ? AND v.version = ? AND (? OR p.status = 'PUBLISHED')""", id, version, anyStatus);
    }

    /** The current business's name (for the evidence PDF). */
    public String businessName() {
        return jdbc.queryForObject("SELECT name FROM businesses WHERE id = jurisone_tenant_id()", String.class);
    }
}
