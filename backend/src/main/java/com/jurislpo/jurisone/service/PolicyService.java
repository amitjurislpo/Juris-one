package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.repository.PolicyRepository;
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
import org.springframework.http.HttpStatus;
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

    private final PolicyRepository repo;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Path storageDir;

    public PolicyService(PolicyRepository repo, NotificationService notifications, AuditService audit, JurisOneProperties props) {
        this.repo = repo;
        this.notifications = notifications;
        this.audit = audit;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String iso(Object timestamp) {
        return timestamp == null ? null : Format.isoTimestamp(((Timestamp) timestamp).toInstant());
    }

    /** Whether this user must acknowledge policies (everyone in a business except its owners). */
    public static boolean mustAcknowledge(CurrentUser user) {
        return user != null && user.businessId() != null && !PeopleRules.isAdminRole(user.roleKey());
    }

    // ───────────────────────── employees: pending, mine, acknowledge ─────────────────────────

    /** Current versions this user still has to acknowledge (oldest first), with their text — for the acknowledgement screen. */
    public List<Map<String, Object>> pending(CurrentUser user) {
        if (!mustAcknowledge(user)) return List.of();
        return repo.findPending(user.id());
    }

    /** Fast check for the request gate. */
    public boolean hasPending(UUID employeeId) {
        return Boolean.TRUE.equals(repo.hasPending(employeeId));
    }

    public int pendingCount(CurrentUser user) {
        if (!mustAcknowledge(user)) return 0;
        return repo.countPending(user.id());
    }

    /** Every published policy (current version) with when this user acknowledged it. */
    public List<Map<String, Object>> mine(CurrentUser user) {
        return repo.findPublished(user.id());
    }

    /** Records "I have read and accept" for the current version (idempotent). 409 when a newer version has been published. */
    public String acknowledge(CurrentUser user, String policyIdRaw, Integer version, String ip, String userAgent) {
        UUID policyId = Check.uuid(policyIdRaw);
        if (!mustAcknowledge(user)) throw new UserException("Owners don't acknowledge their own business's policies.");
        if (version == null) throw new UserException(HttpStatus.BAD_REQUEST, "Say which version you read.");
        List<Map<String, Object>> rows = repo.findCurrentVersion(policyId);
        if (rows.isEmpty() || !"PUBLISHED".equals(rows.get(0).get("status"))) throw new UserException(HttpStatus.NOT_FOUND, "Policy not found.");
        Map<String, Object> v = rows.get(0);
        if (((Number) v.get("version")).intValue() != version) {
            throw new UserException(HttpStatus.CONFLICT, "A newer version of this policy has been published. Please read it before accepting.");
        }
        Map<String, Object> row = repo.findEmployeeSnapshot(user.id());
        int count = repo.insertAcknowledgement(policyId, v.get("id"), version, v.get("title"), v.get("body_sha256"), v.get("file_sha256"),
                user.id(), row.get("code"), row.get("full_name"), row.get("email"), ip, userAgent == null ? null : userAgent.substring(0, Math.min(400, userAgent.length())));
        notifications.clearPolicy(user.id(), policyId);
        if (count > 0) audit.log(user.id(), "policy.acknowledge", "policy", policyId.toString(), null, Map.of("version", version, "title", String.valueOf(v.get("title"))), ip);
                return count == 0 ? "Already accepted" : "Thank you — \"" + v.get("title") + "\" accepted";
    }

    // ───────────────────────── HR: list, detail, edit, publish ─────────────────────────

    public List<Map<String, Object>> list() {
        int total = repo.countRequired();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyRepository.ListRow listRow : repo.findAll()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", listRow.id());
            view.put("title", listRow.title());
            view.put("status", listRow.status());
            view.put("version", listRow.version());
            view.put("requiresAck", listRow.requiresAck());
            view.put("updatedAt", listRow.updatedAt());
            view.put("publishedAt", listRow.publishedAt());
            UUID vid = listRow.versionId();
            view.put("hasChanges", vid == null || changed(listRow.title(), listRow.body(), listRow.fileSha(), listRow.requiresAck(), listRow.vTitle(), listRow.vBodySha(), listRow.vFileSha(), listRow.vAck()));
            view.put("acknowledged", vid == null ? 0 : acknowledgedCount(vid));
            view.put("required", total);
            out.add(view);
        }
        return out;
    }

    int acknowledgedCount(UUID versionId) {
        return repo.countAcknowledged(versionId);
    }

    static boolean changed(String title, String body, String fileSha, boolean ack, String vTitle, String vBodySha, String vFileSha, boolean vAck) {
        return !title.equals(vTitle) || !sha256(body.getBytes(StandardCharsets.UTF_8)).equals(vBodySha) || !Objects.equals(fileSha, vFileSha) || ack != vAck;
    }

    Map<String, Object> row(UUID id) {
        List<Map<String, Object>> rows = repo.findById(id);
        if (rows.isEmpty()) throw new UserException(HttpStatus.NOT_FOUND, "Policy not found.");
        return rows.get(0);
    }

    /** The draft, every published version, and who has / hasn't acknowledged the current one. */
    public Map<String, Object> detail(String idRaw) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("title", policy.get("title"));
        out.put("body", policy.get("body"));
        out.put("fileName", policy.get("file_name"));
        out.put("requiresAck", policy.get("requires_ack"));
        out.put("status", policy.get("status"));
        out.put("version", policy.get("version"));
        out.put("updatedAt", iso(policy.get("updated_at")));
        List<Map<String, Object>> versions = repo.findVersions(id);
        out.put("versions", versions);
        Map<String, Object> current = versions.isEmpty() ? null : versions.get(0);
        out.put("hasChanges", current == null || changed((String) policy.get("title"), (String) policy.get("body"), (String) policy.get("file_sha256"),
                (Boolean) policy.get("requires_ack"), (String) current.get("title"), (String) current.get("bodySha256"), (String) current.get("fileSha256"),
                (Boolean) current.get("requiresAck")));
        List<Map<String, Object>> people = new ArrayList<>();
        if (current != null) {
            people = repo.findRequiredPeople(current.get("versionId"), id);
        }
        out.put("people", people);
        return out;
    }

    public record PolicyInput(String title, String body, Boolean requiresAck) {}

    record ValidatedPolicy(String title, String body, boolean requiresAck) {}

    static ValidatedPolicy validate(PolicyInput input) {
        if (input == null) throw new UserException(HttpStatus.BAD_REQUEST, "Policy details are missing.");
        String title = Check.trimmed(input.title(), 3, "Enter the policy title.", 160, "Keep the title under 160 characters.");
        String body = input.body() == null ? "" : input.body().replace("\r\n", "\n").strip();
        if (body.length() > 100_000) throw new UserException(HttpStatus.BAD_REQUEST, "The policy text is too long (100,000 characters at most) — attach a PDF instead.");
        return new ValidatedPolicy(title, body, input.requiresAck() == null || input.requiresAck());
    }

    public String create(CurrentUser user, PolicyInput input, String ip) {
        ValidatedPolicy validated = validate(input);
        UUID id = UUID.randomUUID();
        repo.insert(id, validated.title(), validated.body(), validated.requiresAck(), user.id());
        audit.log(user.id(), "policy.create", "policy", id.toString(), null, Map.of("title", validated.title()), ip);
        return id.toString();
    }

    public void update(CurrentUser user, String idRaw, PolicyInput input, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        if ("ARCHIVED".equals(policy.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        ValidatedPolicy validated = validate(input);
        repo.updateDraft(id, validated.title(), validated.body(), validated.requiresAck());
        audit.log(user.id(), "policy.update", "policy", id.toString(), Map.of("title", policy.get("title")), Map.of("title", validated.title()), ip);
    }

    /** Deletes a policy that has never been published (published ones are archived instead). */
    public void delete(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        if (((Number) policy.get("version")).intValue() > 0) throw new UserException("A published policy can't be deleted — archive it instead.");
        repo.delete(id);
        audit.log(user.id(), "policy.delete", "policy", id.toString(), Map.of("title", policy.get("title")), null, ip);
    }

    /** Attaches a PDF to the draft (a new file each time; files of published versions stay untouched). */
    public String attach(CurrentUser user, String idRaw, String fileName, byte[] bytes, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        if ("ARCHIVED".equals(policy.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a PDF file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "PDFs up to " + MAX_FILE_MB + " MB.");
        if (bytes.length < PDF.length || !java.util.Arrays.equals(java.util.Arrays.copyOf(bytes, PDF.length), PDF)) {
            throw new UserException(HttpStatus.BAD_REQUEST, "Attach a PDF file.");
        }
        Path directory = storageDir.resolve("policies").resolve(TenantContext.current().toString());
        Path absolutePath = directory.resolve(UUID.randomUUID() + ".pdf");
        try {
            Files.createDirectories(directory);
            Files.write(absolutePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String name = (fileName == null ? "" : fileName).replaceAll("[^\\w.\\- ()]", "_");
        name = name.substring(0, Math.min(120, name.length()));
        if (name.isBlank()) name = "policy.pdf";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) name += ".pdf";
        repo.setFile(id, storageDir.relativize(absolutePath).toString().replace('\\', '/'), name, sha256(bytes));
        audit.log(user.id(), "policy.attach", "policy", id.toString(), null, Map.of("file", name), ip);
        return name;
    }

    public void detach(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        if ("ARCHIVED".equals(policy.get("status"))) throw new UserException("Archived policies can't be edited — publish it again first.");
        repo.clearFile(id);
        audit.log(user.id(), "policy.detach", "policy", id.toString(), ip);
    }

    /**
     * PUBLISHED: publishes the draft as the next version (or re-publishes an archived policy unchanged) and tells everyone
     * who must acknowledge it. ARCHIVED: takes it out of everyone's list (versions and acknowledgements are kept).
     */
    public String setStatus(CurrentUser user, String idRaw, String statusRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        String status = Check.oneOf(statusRaw, List.of("PUBLISHED", "ARCHIVED"));
        Map<String, Object> policy = row(id);
        int version = ((Number) policy.get("version")).intValue();
        if ("ARCHIVED".equals(status)) {
            if (!"PUBLISHED".equals(policy.get("status"))) throw new UserException("Only a published policy can be archived.");
            repo.archive(id);
            notifications.clearPolicy(null, id);
            audit.log(user.id(), "policy.archive", "policy", id.toString(), null, Map.of("version", version), ip);
            return "Policy archived — it no longer appears to employees";
        }
        boolean hasChanges = true;
        if (version > 0) {
            Map<String, Object> row = repo.findVersionHashes(id, version);
            hasChanges = changed((String) policy.get("title"), (String) policy.get("body"), (String) policy.get("file_sha256"), (Boolean) policy.get("requires_ack"),
                    (String) row.get("title"), (String) row.get("body_sha256"), (String) row.get("file_sha256"), (Boolean) row.get("requires_ack"));
        }
        if (!hasChanges) {
            if ("PUBLISHED".equals(policy.get("status"))) throw new UserException("Nothing has changed since version " + version + ".");
            repo.republish(id);
            audit.log(user.id(), "policy.publish", "policy", id.toString(), null, Map.of("version", version, "republished", true), ip);
            return "Policy published again (version " + version + ")";
        }
        String body = (String) policy.get("body");
        if (body.isBlank() && policy.get("file_path") == null) throw new UserException("Write the policy text or attach a PDF before publishing.");
        int next = version + 1;
        repo.insertVersion(id, next, policy.get("title"), body, sha256(body.getBytes(StandardCharsets.UTF_8)), policy.get("file_path"), policy.get("file_name"),
                policy.get("file_sha256"), policy.get("requires_ack"), user.id());
        repo.markPublished(id, next);
        audit.log(user.id(), "policy.publish", "policy", id.toString(), null, Map.of("version", next, "title", policy.get("title")), ip);
        boolean ack = Boolean.TRUE.equals(policy.get("requires_ack"));
        String text = (next == 1 ? "New policy: " : "Updated policy: ") + policy.get("title") + (ack ? " — please read and accept it" : "");
        for (UUID e : repo.findRequiredIds()) {
            if (ack) notifications.notifyPolicy(e, text, "/my-policies", id); // stays until accepted
            else {
                notifications.clearPolicy(e, id);
                notifications.notify(e, text, "/my-policies");
            }
        }
        return "Version " + next + " published" + (ack ? " — everyone will be asked to accept it" : "");
    }

    /** Reminds everyone who hasn't accepted the current version yet (bell). */
    public int remind(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        if (!"PUBLISHED".equals(policy.get("status")) || !Boolean.TRUE.equals(policy.get("requires_ack"))) throw new UserException("Only published policies that need acceptance have reminders.");
        UUID vid = repo.findVersionId(id, policy.get("version"));
        List<UUID> pending = repo.findNotAcknowledged(vid);
        for (UUID e : pending) notifications.notify(e, "Reminder: please read and accept \"" + policy.get("title") + "\"", "/my-policies");
        audit.log(user.id(), "policy.remind", "policy", id.toString(), null, Map.of("count", pending.size()), ip);
        return pending.size();
    }

    // ───────────────────────── evidence and files ─────────────────────────

    public record Export(String filename, String body) {}

    static final List<String> EVIDENCE = List.of("Policy", "Version", "Accepted at (UTC)", "Employee ID", "Name", "Email", "IP address", "Browser",
            "Policy text SHA-256", "PDF SHA-256", "Acknowledgement ID");

    /** Every acknowledgement of every version of this policy, as recorded (CSV). */
    public Export evidence(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        List<List<Object>> rows = repo.findAcknowledgements(id);
        audit.log(user.id(), "policy.evidence_export", "policy", id.toString(), null, Map.of("rows", rows.size()), ip);
        String slug = ((String) policy.get("title")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return new Export("policy-acknowledgements-" + (slug.isEmpty() ? "policy" : slug) + ".csv", Csv.toCsv(EVIDENCE, rows));
    }

    /** The same evidence as a printable PDF (audited like the CSV). */
    public PolicyFile evidencePdf(CurrentUser user, String idRaw, String ip) {
        UUID id = Check.uuid(idRaw);
        Map<String, Object> policy = row(id);
        List<List<Object>> rows = repo.findAcknowledgements(id);
        audit.log(user.id(), "policy.evidence_export", "policy", id.toString(), null, Map.of("rows", rows.size(), "format", "pdf"), ip);
        String slug = ((String) policy.get("title")).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return new PolicyFile("policy-acknowledgements-" + (slug.isEmpty() ? "policy" : slug) + ".pdf",
                PolicyEvidencePdf.render(repo.businessName(), (String) policy.get("title"), user.name() + " (" + user.code() + ")", rows));
    }

    public record PolicyFile(String name, byte[] bytes) {}

    /**
     * The PDF of a published version (anyone in the business while the policy is published; managers always), or of the
     * draft ({@code version} null, managers only). Null = not found.
     */
    public PolicyFile file(CurrentUser user, String idRaw, Integer version) {
        UUID id = Check.uuid(idRaw);
        boolean manager = user.can("policies.manage");
        List<Map<String, Object>> rows;
        if (version == null) {
            if (!manager) return null;
            rows = repo.findDraftFile(id);
        } else {
            rows = repo.findVersionFile(id, version, manager);
        }
        if (rows.isEmpty() || rows.get(0).get("file_path") == null) return null;
        Path absolutePath = storageDir.resolve((String) rows.get(0).get("file_path")).normalize();
        if (!absolutePath.startsWith(storageDir)) return null;
        try {
            return new PolicyFile((String) rows.get(0).get("file_name"), Files.readAllBytes(absolutePath));
        } catch (IOException e) {
            return null;
        }
    }
}
