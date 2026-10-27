package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots;
import com.jurislpo.jurisone.engine.OnboardingAttachmentSlots.Slot;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.OnboardingAttachmentRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.OwnOnboardingSource.OwnOnboarding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The files of the onboarding wizard (step 6). An employee uploads, views and removes only their own files, only into a slot that
 * exists for their current data, and not after submitting. Files live at
 * {@code <storage-dir>/onboarding-forms/<YYYY-MM>/<employeeCode>/<slotFolder>/<uuid>-<safeName>}; the database keeps the path relative to the storage dir.
 */
@Service
@Transactional
public class OnboardingAttachmentService {

    public static final int MAX_FILE_MB = 10;

    record FileType(String extension, String mime, byte[] magic) {}

    private static final byte[] OLE_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final List<FileType> FILE_TYPES = List.of(
            new FileType("pdf", "application/pdf", new byte[] {0x25, 0x50, 0x44, 0x46}),
            new FileType("png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}),
            new FileType("jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
            new FileType("jpeg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
            new FileType("doc", "application/msword", OLE_MAGIC),
            new FileType("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[] {0x50, 0x4b, 0x03, 0x04}));

    public record FileOut(String name, String mime, byte[] bytes) {}

    private final OnboardingAttachmentRepository repo;
    private final OwnOnboardingSource onboardings;
    private final PiiCrypto piiCrypto;
    private final AuditService audit;
    private final Path storageDir;

    public OnboardingAttachmentService(OnboardingAttachmentRepository repo, OwnOnboardingSource onboardings, PiiCrypto piiCrypto, AuditService audit, JurisOneProperties properties) {
        this.repo = repo;
        this.onboardings = onboardings;
        this.piiCrypto = piiCrypto;
        this.audit = audit;
        this.storageDir = Path.of(properties.storageDir()).toAbsolutePath().normalize();
    }

    private OwnOnboarding own(CurrentUser user) {
        return onboardings.findOwn(user.id()).orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Onboarding is not required for you."));
    }

    private OwnOnboarding ownEditable(CurrentUser user) {
        OwnOnboarding onboarding = own(user);
        if (onboarding.isSubmitted()) throw new UserException(HttpStatus.CONFLICT, "Onboarding is already submitted.");
        return onboarding;
    }

    /** Stores one file in a slot and returns it as {@code {id, slotKey, fileName, mime, sizeBytes, uploadedAt}}. */
    public Map<String, Object> upload(CurrentUser user, String slotKey, String originalFileName, byte[] bytes, String ipAddress) {
        return store(user.id(), ownEditable(user), slotKey, originalFileName, bytes, ipAddress);
    }

    /** HR stores a file for the employee (the caller has checked that HR may edit this status). */
    public Map<String, Object> uploadForEmployee(CurrentUser actor, UUID employeeId, String slotKey, String originalFileName, byte[] bytes, String ipAddress) {
        return store(actor.id(), ownOf(employeeId), slotKey, originalFileName, bytes, ipAddress);
    }

    private OwnOnboarding ownOf(UUID employeeId) {
        return onboardings.findOwn(employeeId).orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "Onboarding is not required for this employee."));
    }

    private Map<String, Object> store(UUID actorId, OwnOnboarding onboarding, String slotKey, String originalFileName, byte[] bytes, String ipAddress) {
        List<Slot> slots = OnboardingAttachmentSlots.slotsFor(onboarding.data());
        if (slotKey == null || slots.stream().noneMatch(slot -> slot.slotKey().equals(slotKey))) throw new UserException("This attachment is not asked for.");
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.PAYLOAD_TOO_LARGE, "Files up to " + MAX_FILE_MB + " MB.");
        String safeName = safeFileName(originalFileName);
        FileType fileType = checkedType(safeName, bytes);

        String month = YearMonth.now(ZoneOffset.UTC).toString();
        UUID id = UUID.randomUUID();
        Path directory = storageDir.resolve("onboarding-forms").resolve(month).resolve(safePathPart(onboarding.employeeCode()))
                .resolve(OnboardingAttachmentSlots.folderName(slotKey)).normalize();
        Path absolutePath = directory.resolve(id + "-" + safeName).normalize();
        if (!absolutePath.startsWith(storageDir)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        try {
            Files.createDirectories(directory);
            Files.write(absolutePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the attachment", e);
        }
        repo.insert(id, onboarding.onboardingId(), slotKey, safeName, fileType.mime(), bytes.length, PolicyService.sha256(bytes), storageDir.relativize(absolutePath).toString().replace('\\', '/'));
        audit.log(actorId, "onboarding.attachment_add", "onboarding_attachment", id.toString(), null, Map.of("slotKey", slotKey, "fileName", safeName, "bytes", bytes.length), ipAddress);
        return asJson(repo.findInOnboarding(id, onboarding.onboardingId()));
    }

    public void delete(CurrentUser user, String idRaw, String ipAddress) {
        remove(user.id(), ownEditable(user), idRaw, ipAddress);
    }

    /** HR removes a file of the employee (the caller has checked that HR may edit this status). */
    public void deleteForEmployee(CurrentUser actor, UUID employeeId, String idRaw, String ipAddress) {
        remove(actor.id(), ownOf(employeeId), idRaw, ipAddress);
    }

    private void remove(UUID actorId, OwnOnboarding onboarding, String idRaw, String ipAddress) {
        Map<String, Object> row = repo.findInOnboarding(Check.uuid(idRaw), onboarding.onboardingId());
        if (row == null) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        repo.delete((UUID) row.get("id"));
        removeFile((String) row.get("storage_path"));
        audit.log(actorId, "onboarding.attachment_remove", "onboarding_attachment", row.get("id").toString(), Map.of("slotKey", row.get("slot_key"), "fileName", row.get("file_name")), null, ipAddress);
    }

    /** The bytes of the signed-in employee's own file; also readable after submitting. */
    @Transactional(readOnly = true)
    public FileOut file(CurrentUser user, String idRaw) {
        OwnOnboarding onboarding = own(user);
        Map<String, Object> row = repo.findInOnboarding(Check.uuid(idRaw), onboarding.onboardingId());
        if (row == null) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        Path absolutePath = storageDir.resolve((String) row.get("storage_path")).normalize();
        if (!absolutePath.startsWith(storageDir) || !Files.isRegularFile(absolutePath)) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        try {
            return new FileOut((String) row.get("file_name"), (String) row.get("mime"), Files.readAllBytes(absolutePath));
        } catch (IOException e) {
            throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        }
    }

    /** Bank account number, PAN and Aadhaar in full, for the owner only (Summary eye toggle and the edit form). Audited. */
    public Map<String, String> secrets(CurrentUser user, String ipAddress) {
        return secretsOf(user.id(), own(user), ipAddress);
    }

    /** The same values for HR filling the form on the employee's behalf. Audited with the HR actor. */
    public Map<String, String> secretsForEmployee(CurrentUser actor, UUID employeeId, String ipAddress) {
        return secretsOf(actor.id(), ownOf(employeeId), ipAddress);
    }

    private Map<String, String> secretsOf(UUID actorId, OwnOnboarding onboarding, String ipAddress) {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("bankAccountNumber", piiCrypto.decrypt(onboarding.encryptedBankAccountNumber()));
        secrets.put("pan", piiCrypto.decrypt(onboarding.encryptedPan()));
        secrets.put("aadhaar", piiCrypto.decrypt(onboarding.encryptedAadhaar()));
        audit.log(actorId, "onboarding.secrets_read", "employee_onboarding", onboarding.onboardingId().toString(), ipAddress);
        return secrets;
    }

    /** Every file of an onboarding, as JSON rows {@code {id, slotKey, fileName, mime, sizeBytes, uploadedAt}}, for the wizard payload. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> filesOf(UUID onboardingId) {
        return repo.findAllOf(onboardingId).stream().map(OnboardingAttachmentService::asJson).toList();
    }

    /** The slot keys that have at least one file, for {@link OnboardingAttachmentSlots#missingMandatorySlots}. */
    @Transactional(readOnly = true)
    public Set<String> uploadedSlotKeys(UUID onboardingId) {
        return repo.findAllOf(onboardingId).stream().map(row -> (String) row.get("slot_key")).collect(Collectors.toSet());
    }

    /** Removes the files (rows and bytes on disk) of every slot whose key starts with {@code slotKeyPrefix}, e.g. {@code "qualification:<educationId>"}. */
    public void deleteAllFor(UUID onboardingId, String slotKeyPrefix) {
        for (Map<String, Object> row : repo.findBySlotKeyPrefix(onboardingId, slotKeyPrefix)) {
            repo.delete((UUID) row.get("id"));
            removeFile((String) row.get("storage_path"));
        }
    }

    private void removeFile(String storagePath) {
        Path absolutePath = storageDir.resolve(storagePath).normalize();
        if (!absolutePath.startsWith(storageDir)) return;
        try {
            Files.deleteIfExists(absolutePath);
        } catch (IOException e) {
            // the row is gone; a file left behind is harmless and can be cleaned up later
        }
    }

    static FileType checkedType(String fileName, byte[] bytes) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        FileType fileType = FILE_TYPES.stream().filter(type -> type.extension().equals(extension)).findFirst()
                .orElseThrow(() -> new UserException(HttpStatus.BAD_REQUEST, "Only pdf, doc, docx, jpg & png."));
        if (bytes.length < fileType.magic().length || !Arrays.equals(Arrays.copyOf(bytes, fileType.magic().length), fileType.magic())) {
            throw new UserException(HttpStatus.BAD_REQUEST, "That file is not a valid " + extension.toUpperCase(Locale.ROOT) + " file.");
        }
        return fileType;
    }

    /** Never trust the client's name: keep letters, digits, dot, dash, underscore, space and brackets; no path parts; at most 120 characters. */
    static String safeFileName(String originalFileName) {
        String name = originalFileName == null ? "" : originalFileName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).replaceAll("[^\\w.\\- ()]", "_").replaceAll("\\.{2,}", ".").trim();
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot >= 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
            name = name.substring(0, 120 - extension.length()) + extension;
        }
        return name.isBlank() || name.startsWith(".") ? "document" + name : name;
    }

    private static String safePathPart(String text) {
        String safe = text == null ? "" : text.replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static Map<String, Object> asJson(Map<String, Object> row) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", row.get("id").toString());
        json.put("slotKey", row.get("slot_key"));
        json.put("fileName", row.get("file_name"));
        json.put("mime", row.get("mime"));
        json.put("sizeBytes", ((Number) row.get("size_bytes")).longValue());
        json.put("uploadedAt", ((Timestamp) row.get("uploaded_at")).toInstant().toString());
        return json;
    }
}
