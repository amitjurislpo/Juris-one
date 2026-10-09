package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Company policies. HR / the owner ({@code policies.manage}) edit a draft and publish it: each publish is an immutable
 * version. Everyone in the business except the owner must acknowledge the current version of every published policy
 * that requires it before using JurisOne; each acknowledgement is kept as a permanent legal record with a snapshot of
 * the policy (title, SHA-256 of the text and of the PDF) and of the employee (code, name, email), time, IP and browser.
 */
@Service
@Transactional
public class PolicyService {

    public static final String OWNER = "owner";
    static final int MAX_FILE_MB = 10;
    static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Path projectDir;
    private final String uploadDir;

    public PolicyService(JdbcTemplate jdbc, NotificationService notifications, AuditService audit, JurisOneProperties props,
                         @Value("${UPLOAD_DIR:./storage}") String uploadDir) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir()).toAbsolutePath().normalize();
        this.uploadDir = uploadDir;
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String iso(Object ts) {
        return ts == null ? null : Format.jsIso(((Timestamp) ts).toInstant());
    }

    /** Whether this user must acknowledge policies (everyone in a business except its owners). */
    public static boolean mustAcknowledge(CurrentUser u) {
        return u != null && u.businessId() != null && !PeopleRules.isAdminRole(u.roleKey());
    }

    // ───────────────────────── employees: pending, mine, acknowledge ─────────────────────────

    /** Current versions this user still has to acknowledge (oldest first), with their text — for the acknowledgement screen. */
    public List<Map<String, Object>> pending(CurrentUser u) {
        if (!mustAcknowledge(u)) return List.of();
        return jdbc.query("""
            SELECT p.id::text, v.version, v.title, v.body, v.file_name, v.published_at
            FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' AND v.requires_ack
              AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?)
            ORDER BY v.published_at, v.title""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("version", rs.getInt(2));
            m.put("title", rs.getString(3));
            m.put("body", rs.getString(4));
            m.put("fileName", rs.getString(5));
            m.put("publishedAt", iso(rs.getTimestamp(6)));
            return m;
        }, u.id());
    }

    /** Fast check for the request gate. */
    public boolean hasPending(UUID employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
              WHERE p.status = 'PUBLISHED' AND v.requires_ack
                AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?))""", Boolean.class, employeeId));
    }

    public int pendingCount(CurrentUser u) {
        if (!mustAcknowledge(u)) return 0;
        return jdbc.queryForObject("""
            SELECT count(*) FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' AND v.requires_ack
              AND NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?)""", Integer.class, u.id());
    }

    /** Every published policy (current version) with when this user acknowledged it. */
    public List<Map<String, Object>> mine(CurrentUser u) {
        return jdbc.query("""
            SELECT p.id::text, v.version, v.title, v.body, v.file_name, v.published_at, v.requires_ack,
                   (SELECT a.acknowledged_at FROM policy_acknowledgements a WHERE a.policy_version_id = v.id AND a.employee_id = ?) AS acked_at
            FROM policies p JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            WHERE p.status = 'PUBLISHED' ORDER BY v.title""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("version", rs.getInt(2));
            m.put("title", rs.getString(3));
            m.put("body", rs.getString(4));
            m.put("fileName", rs.getString(5));
            m.put("publishedAt", iso(rs.getTimestamp(6)));
            m.put("requiresAck", rs.getBoolean(7));
            m.put("acknowledgedAt", iso(rs.getTimestamp(8)));
            return m;
        }, u.id());
    }

    /** Records "I have read and accept" for the current version (idempotent). 409 when a newer version has been published. */
    public String acknowledge(CurrentUser u, String policyIdRaw, Integer version, String ip, String userAgent) {
        UUID policyId = Check.uuid(policyIdRaw);
        if (!mustAcknowledge(u)) throw new UserException("Owners don't acknowledge their own business's policies.");
        if (version == null) throw new UserException(HttpStatus.BAD_REQUEST, "Say which version you read.");
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT v.id, v.version, v.title, v.body_sha256, v.file_sha256, p.status FROM policies p
            JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version WHERE p.id = ?""", policyId);
        if (rows.isEmpty() || !"PUBLISHED".equals(rows.get(0).get("status"))) throw new UserException(HttpStatus.NOT_FOUND, "Policy not found.");
        Map<String, Object> v = rows.get(0);
        if (((Number) v.get("version")).intValue() != version) {
            throw new UserException(HttpStatus.CONFLICT, "A newer version of this policy has been published. Please read it before accepting.");
        }
        Map<String, Object> me = jdbc.queryForMap("SELECT code, full_name, email FROM employees WHERE id = ?", u.id());
        int n = jdbc.update("""
            INSERT INTO policy_acknowledgements (id, policy_id, policy_version_id, version, policy_title, body_sha256, file_sha256,
              employee_id, employee_code, employee_name, employee_email, ip, user_agent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (policy_version_id, employee_id) DO NOTHING""",
                UUID.randomUUID(), policyId, v.get("id"), version, v.get("title"), v.get("body_sha256"), v.get("file_sha256"),
                u.id(), me.get("code"), me.get("full_name"), me.get("email"), ip, userAgent == null ? null : userAgent.substring(0, Math.min(400, userAgent.length())));
        notifications.clearPolicy(u.id(), policyId);
                return n == 0 ? "Already accepted" : "Thank you — \"" + v.get("title") + "\" accepted";
    }

    // ───────────────────────── HR: list, detail, edit, publish ─────────────────────────

    /** Employees who must acknowledge (current, not owners). */
    static final String REQUIRED = """
        SELECT e.id, e.code, e.full_name, d.name AS department FROM employees e JOIN roles r ON r.id = e.role_id
        LEFT JOIN departments d ON d.id = e.department_id
        WHERE e.business_id = jurisone_tenant_id() AND e.status <> 'EXITED' AND r.key NOT IN ('owner', 'super_admin')""";

    public List<Map<String, Object>> list() {
        int total = jdbc.queryForObject("SELECT count(*) FROM (" + REQUIRED + ") x", Integer.class);
        return jdbc.query("""
            SELECT p.id::text, p.title, p.status, p.version, p.requires_ack, p.updated_at, p.published_at,
                   v.id AS vid, v.title AS vtitle, v.body_sha256, v.file_sha256 AS vfile, v.requires_ack AS vack, p.body, p.file_sha256
            FROM policies p LEFT JOIN policy_versions v ON v.policy_id = p.id AND v.version = p.version
            ORDER BY p.status = 'ARCHIVED', p.title""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("title", rs.getString(2));
            m.put("status", rs.getString(3));
            m.put("version", rs.getInt(4));
            m.put("requiresAck", rs.getBoolean(5));
            m.put("updatedAt", iso(rs.getTimestamp(6)));
            m.put("publishedAt", iso(rs.getTimestamp(7)));
            UUID vid = (UUID) rs.getObject(8);
            m.put("hasChanges", vid == null || changed(rs.getString(2), rs.getString(13), rs.getString(14), rs.getBoolean(5), rs.getString(9), rs.getString(10),
                    rs.getString(11), rs.getBoolean(12)));
            m.put("acknowledged", vid == null ? 0 : acknowledgedCount(vid));
            m.put("required", total);
            return m;
        });
    }

    int acknowledgedCount(UUID versionId) {
        return jdbc.queryForObject("SELECT count(*) FROM (" + REQUIRED + ") x WHERE EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = ? AND a.employee_id = x.id)",
                Integer.class, versionId);
    }

    static boolean changed(String title, String body, String fileSha, boolean ack, String vTitle, String vBodySha, String vFileSha, boolean vAck) {
        return !title.equals(vTitle) || !sha256(body.getBytes(StandardCharsets.UTF_8)).equals(vBodySha) || !Objects.equals(fileSha, vFileSha) || ack != vAck;
    }

    Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM policies WHERE id = ?", id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Policy not found.");
        return rows.get(0);
    }

    /** The draft, every published version, and who has / hasn't acknowledged the current one. */
    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("title", p.get("title"));
        out.put("body", p.get("body"));
        out.put("fileName", p.get("file_name"));
        out.put("requiresAck", p.get("requires_ack"));
        out.put("status", p.get("status"));
        out.put("version", p.get("version"));
        out.put("updatedAt", iso(p.get("updated_at")));
        List<Map<String, Object>> versions = jdbc.query("""
            SELECT v.id, v.version, v.title, v.published_at, e.full_name, v.file_name, v.requires_ack, v.body_sha256, v.file_sha256,
                   (SELECT count(*) FROM policy_acknowledgements a WHERE a.policy_version_id = v.id) AS acks
            FROM policy_versions v LEFT JOIN employees e ON e.id = v.published_by WHERE v.policy_id = ? ORDER BY v.version DESC""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("versionId", rs.getString(1));
            m.put("version", rs.getInt(2));
            m.put("title", rs.getString(3));
            m.put("publishedAt", iso(rs.getTimestamp(4)));
            m.put("publishedBy", rs.getString(5));
            m.put("fileName", rs.getString(6));
            m.put("requiresAck", rs.getBoolean(7));
            m.put("bodySha256", rs.getString(8));
            m.put("fileSha256", rs.getString(9));
            m.put("acknowledgements", rs.getLong(10));
            return m;
        }, id);
        out.put("versions", versions);
        Map<String, Object> current = versions.isEmpty() ? null : versions.get(0);
        out.put("hasChanges", current == null || changed((String) p.get("title"), (String) p.get("body"), (String) p.get("file_sha256"),
                (Boolean) p.get("requires_ack"), (String) current.get("title"), (String) current.get("bodySha256"), (String) current.get("fileSha256"),
                (Boolean) current.get("requiresAck")));
        List<Map<String, Object>> people = new ArrayList<>();
        if (current != null) {
            people = jdbc.query("""
                SELECT * FROM (SELECT x.code, x.full_name, x.department,
                       (SELECT a.acknowledged_at FROM policy_acknowledgements a WHERE a.policy_version_id = ?::uuid AND a.employee_id = x.id) AS acked_at,
                       (SELECT max(a.version) FROM policy_acknowledgements a WHERE a.policy_id = ? AND a.employee_id = x.id) AS last_version
                FROM (""" + REQUIRED + ") x) y ORDER BY y.acked_at IS NOT NULL, y.full_name", (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", rs.getString(1));
                m.put("name", rs.getString(2));
                m.put("department", rs.getString(3));
                m.put("acknowledgedAt", iso(rs.getTimestamp(4)));
                m.put("lastVersion", rs.getObject(5));
                return m;
            }, current.get("versionId"), id);
        }
        out.put("people", people);
        return out;
    }

    public record PolicyInput(String title, String body, Boolean requiresAck) {}

    record Valid(String title, String body, boolean requiresAck) {}

    static Valid validate(PolicyInput in) {
        if (in == null) throw new UserException(HttpStatus.BAD_REQUEST, "Policy details are missing.");
        String title = Check.trimmed(in.title(), 3, "Enter the policy title.", 160, "Keep the title under 160 characters.");
        String body = in.body() == null ? "" : in.body().replace("\r\n", "\n").strip();
        if (body.length() > 100_000) throw new UserException(HttpStatus.BAD_REQUEST, "The policy text is too long (100,000 characters at most) — attach a PDF instead.");
        return new Valid(title, body, in.requiresAck() == null || in.requiresAck());
    }

    public String create(CurrentUser u, PolicyInput in, String ip) {
        Valid v = validate(in);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO policies (id, title, body, requires_ack, created_by) VALUES (?, ?, ?, ?, ?)", id, v.title(), v.body(), v.requiresAck(), u.id());
        audit.log(u.id(), "policy.create", "policy", id.toString(), null, Map.of("title", v.title()), ip);
        return id.toString();
    }

    public void update(CurrentUser u, String idRaw, PolicyInput in, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        if ("ARCHIVED".equals(p.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        Valid v = validate(in);
        jdbc.update("UPDATE policies SET title = ?, body = ?, requires_ack = ?, updated_at = now() WHERE id = ?", v.title(), v.body(), v.requiresAck(), id);
        audit.log(u.id(), "policy.update", "policy", id.toString(), Map.of("title", p.get("title")), Map.of("title", v.title()), ip);
    }

    /** Deletes a policy that has never been published (published ones are archived instead). */
    public void delete(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        if (((Number) p.get("version")).intValue() > 0) throw new UserException("A published policy can't be deleted — archive it instead.");
        jdbc.update("DELETE FROM policies WHERE id = ?", id);
        audit.log(u.id(), "policy.delete", "policy", id.toString(), Map.of("title", p.get("title")), null, ip);
    }

    /** Attaches a PDF to the draft (a new file each time; files of published versions stay untouched). */
    public String attach(CurrentUser u, String idRaw, String fileName, byte[] bytes, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        if ("ARCHIVED".equals(p.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a PDF file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "PDFs up to " + MAX_FILE_MB + " MB.");
        if (bytes.length < PDF.length || !java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, PDF.length), PDF)) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Attach a PDF file.");
        }
        Path dir = projectDir.resolve(uploadDir).normalize().resolve("policies").resolve(TenantContext.current().toString());
        Path abs = dir.resolve(UUID.randomUUID() + ".pdf");
        try {
            Files.createDirectories(dir);
            Files.write(abs, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String name = (fileName == null ? "" : fileName).replaceAll("[^\\w.\\- ()]", "_");
        name = name.substring(0, Math.min(120, name.length()));
        if (name.isBlank()) name = "policy.pdf";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) name += ".pdf";
        jdbc.update("UPDATE policies SET file_path = ?, file_name = ?, file_sha256 = ?, updated_at = now() WHERE id = ?",
                projectDir.relativize(abs).toString().replace('\\', '/'), name, sha256(bytes), id);
        audit.log(u.id(), "policy.attach", "policy", id.toString(), null, Map.of("file", name), ip);
        return name;
    }

    public void detach(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        if ("ARCHIVED".equals(p.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        jdbc.update("UPDATE policies SET file_path = NULL, file_name = NULL, file_sha256 = NULL, updated_at = now() WHERE id = ?", id);
        audit.log(u.id(), "policy.detach", "policy", id.toString(), ip);
    }

    /**
     * PUBLISHED: publishes the draft as the next version (or re-publishes an archived policy unchanged) and tells everyone
     * who must acknowledge it. ARCHIVED: takes it out of everyone's list (versions and acknowledgements are kept).
     */
    public String setStatus(CurrentUser u, String idRaw, String statusRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("PUBLISHED", "ARCHIVED"));
        Map<String, Object> p = row(id);
        int version = ((Number) p.get("version")).intValue();
        if ("ARCHIVED".equals(status)) {
            if (!"PUBLISHED".equals(p.get("status"))) throw new UserException("Only a published policy can be archived.");
            jdbc.update("UPDATE policies SET status = 'ARCHIVED', archived_at = now(), updated_at = now() WHERE id = ?", id);
            notifications.clearPolicy(null, id);
            audit.log(u.id(), "policy.archive", "policy", id.toString(), null, Map.of("version", version), ip);
            return "Policy archived — it no longer appears to employees";
        }
        boolean hasChanges = true;
        if (version > 0) {
            Map<String, Object> v = jdbc.queryForMap("SELECT title, body_sha256, file_sha256, requires_ack FROM policy_versions WHERE policy_id = ? AND version = ?", id, version);
            hasChanges = changed((String) p.get("title"), (String) p.get("body"), (String) p.get("file_sha256"), (Boolean) p.get("requires_ack"),
                    (String) v.get("title"), (String) v.get("body_sha256"), (String) v.get("file_sha256"), (Boolean) v.get("requires_ack"));
        }
        if (!hasChanges) {
            if ("PUBLISHED".equals(p.get("status"))) throw new UserException("Nothing has changed since version " + version + ".");
            jdbc.update("UPDATE policies SET status = 'PUBLISHED', archived_at = NULL, updated_at = now() WHERE id = ?", id);
            audit.log(u.id(), "policy.publish", "policy", id.toString(), null, Map.of("version", version, "republished", true), ip);
            return "Policy published again (version " + version + ")";
        }
        String body = (String) p.get("body");
        if (body.isBlank() && p.get("file_path") == null) throw new UserException("Write the policy text or attach a PDF before publishing.");
        int next = version + 1;
        jdbc.update("""
            INSERT INTO policy_versions (id, policy_id, version, title, body, body_sha256, file_path, file_name, file_sha256, requires_ack, published_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), id, next, p.get("title"), body, sha256(body.getBytes(StandardCharsets.UTF_8)), p.get("file_path"), p.get("file_name"),
                p.get("file_sha256"), p.get("requires_ack"), u.id());
        jdbc.update("UPDATE policies SET status = 'PUBLISHED', version = ?, published_at = now(), archived_at = NULL, updated_at = now() WHERE id = ?", next, id);
        audit.log(u.id(), "policy.publish", "policy", id.toString(), null, Map.of("version", next, "title", p.get("title")), ip);
        boolean ack = Boolean.TRUE.equals(p.get("requires_ack"));
        String text = (next == 1 ? "New policy: " : "Updated policy: ") + p.get("title") + (ack ? " — please read and accept it" : "");
        for (UUID e : jdbc.queryForList("SELECT id FROM (" + REQUIRED + ") x", UUID.class)) {
            if (ack) notifications.notifyPolicy(e, text, "/my-policies", id); // stays until accepted
            else {
                notifications.clearPolicy(e, id);
                notifications.notify(e, text, "/my-policies");
            }
        }
        return "Version " + next + " published" + (ack ? " — everyone will be asked to accept it" : "");
    }

    /** Reminds everyone who hasn't accepted the current version yet (bell). */
    public int remind(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        if (!"PUBLISHED".equals(p.get("status")) || !Boolean.TRUE.equals(p.get("requires_ack"))) throw new UserException("Only published policies that need acceptance have reminders.");
        UUID vid = jdbc.queryForObject("SELECT id FROM policy_versions WHERE policy_id = ? AND version = ?", UUID.class, id, p.get("version"));
        List<UUID> pending = jdbc.queryForList("SELECT x.id FROM (" + REQUIRED + ") x WHERE NOT EXISTS (SELECT 1 FROM policy_acknowledgements a WHERE a.policy_version_id = ? AND a.employee_id = x.id)",
                UUID.class, vid);
        for (UUID e : pending) notifications.notify(e, "Reminder: please read and accept \"" + p.get("title") + "\"", "/my-policies");
        audit.log(u.id(), "policy.remind", "policy", id.toString(), null, Map.of("count", pending.size()), ip);
        return pending.size();
    }

    // ───────────────────────── evidence and files ─────────────────────────

    public record Export(String filename, String body) {}

    static final List<String> EVIDENCE = List.of("Policy", "Version", "Accepted at (UTC)", "Employee ID", "Name", "Email", "IP address", "Browser",
            "Policy text SHA-256", "PDF SHA-256", "Acknowledgement ID");

    /** Every acknowledgement of every version of this policy, as recorded (CSV). */
    public Export evidence(CurrentUser u, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> p = row(id);
        List<List<Object>> rows = jdbc.query("""
            SELECT policy_title, version, acknowledged_at, employee_code, employee_name, employee_email, ip, user_agent, body_sha256, file_sha256, id::text
            FROM policy_acknowledgements WHERE policy_id = ? ORDER BY version, acknowledged_at""", (rs, i) -> {
            List<Object> r = new ArrayList<>();
            r.add(rs.getString(1));
            r.add(rs.getInt(2));
            r.add(iso(rs.getTimestamp(3)));
            for (int c = 4; c <= 11; c++) r.add(rs.getString(c));
            return r;
        }, id);
        audit.log(u.id(), "policy.evidence_export", "policy", id.toString(), null, Map.of("rows", rows.size()), ip);
        String slug = ((String) p.get("title")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return new Export("policy-acknowledgements-" + (slug.isEmpty() ? "policy" : slug) + ".csv", Csv.toCsv(EVIDENCE, rows));
    }

    public record PolicyFile(String name, byte[] bytes) {}

    /**
     * The PDF of a published version (anyone in the business while the policy is published; managers always), or of the
     * draft ({@code version} null, managers only). Null = not found.
     */
    public PolicyFile file(CurrentUser u, String idRaw, Integer version) {
        UUID id = Check.uuid(idRaw);
        boolean manager = u.can("policies.manage");
        List<Map<String, Object>> rows;
        if (version == null) {
            if (!manager) return null;
            rows = jdbc.queryForList("SELECT file_path, file_name FROM policies WHERE id = ?", id);
        } else {
            rows = jdbc.queryForList("""
                SELECT v.file_path, v.file_name FROM policy_versions v JOIN policies p ON p.id = v.policy_id
                WHERE v.policy_id = ? AND v.version = ? AND (? OR p.status = 'PUBLISHED')""", id, version, manager);
        }
        if (rows.isEmpty() || rows.get(0).get("file_path") == null) return null;
        Path abs = projectDir.resolve((String) rows.get(0).get("file_path")).normalize();
        if (!abs.startsWith(projectDir)) return null;
        try {
            return new PolicyFile((String) rows.get(0).get("file_name"), Files.readAllBytes(abs));
        } catch (IOException e) {
            return null;
        }
    }
}
