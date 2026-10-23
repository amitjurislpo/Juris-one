package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.TaskAttachmentRepository;
import com.jurislpo.jurisone.repository.TaskAttachmentRepository.AttachmentRow;
import com.jurislpo.jurisone.repository.TaskRepository;
import com.jurislpo.jurisone.repository.TaskRepository.TaskFacts;
import com.jurislpo.jurisone.security.CurrentUser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Files on tasks (plan §11): checked by their first bytes, at most 10 MB each and 20 per task, stored under
 * {@code <storage-dir>/tasks/<businessId>/<taskId>/}. Deleting hides the file; the stored file stays for the audit trail.
 */
@Service
@Transactional
public class TaskAttachmentService {

    static final int MAX_FILE_MB = 10, MAX_PER_TASK = 20;
    static final String TYPES_MESSAGE = "Upload a PDF, image, Word, Excel, PowerPoint, text or CSV file.";

    /** An accepted file type: its stored extension and content type. */
    record FileType(String extension, String contentType) {}

    static final byte[] PDF = {0x25, 0x50, 0x44, 0x46}, PNG = {(byte) 0x89, 0x50, 0x4e, 0x47}, JPG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, ZIP = {0x50, 0x4b, 0x03, 0x04};
    static final Map<String, String> OFFICE = Map.of("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    static final Map<String, String> TEXT = Map.of("txt", "text/plain", "csv", "text/csv");

    public record FileOut(String name, String contentType, byte[] bytes) {
        /** PDFs and images open in the browser; everything else downloads. */
        public boolean isInline() {
            return "application/pdf".equals(contentType) || contentType.startsWith("image/");
        }
    }

    private final TaskAttachmentRepository repo;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final TaskActivityService activity;
    private final AuditService audit;
    private final Path storageDir;

    public TaskAttachmentService(TaskAttachmentRepository repo, TaskRepository tasks, TaskService taskService, TaskActivityService activity, AuditService audit,
                                 JurisOneProperties props) {
        this.repo = repo;
        this.tasks = tasks;
        this.taskService = taskService;
        this.activity = activity;
        this.audit = audit;
        this.storageDir = Path.of(props.storageDir()).toAbsolutePath().normalize();
    }

    static boolean startsWith(byte[] bytes, byte[] magic) {
        return bytes.length >= magic.length && Arrays.equals(Arrays.copyOf(bytes, magic.length), magic);
    }

    static boolean isUtf8Text(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** The type of an upload by its first bytes (and, for Office and text files, its extension); null when it is not accepted. */
    static FileType detect(String fileName, byte[] bytes) {
        if (startsWith(bytes, PDF)) return new FileType("pdf", "application/pdf");
        if (startsWith(bytes, PNG)) return new FileType("png", "image/png");
        if (startsWith(bytes, JPG)) return new FileType("jpg", "image/jpeg");
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (startsWith(bytes, ZIP) && OFFICE.containsKey(extension)) return new FileType(extension, OFFICE.get(extension));
        if (TEXT.containsKey(extension) && isUtf8Text(bytes)) return new FileType(extension, TEXT.get(extension));
        return null;
    }

    /** Uploader, project owners and the Owner may remove an attachment. */
    static boolean canDelete(CurrentUser user, TaskService.AccessibleTask access, UUID uploadedBy) {
        return user.id().equals(uploadedBy) || access.permissions().canManageProject() || ProjectService.isTaskAdmin(user);
    }

    public Map<String, String> upload(CurrentUser user, UUID taskId, String fileName, byte[] bytes, String ipAddress) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        if (!access.permissions().canEdit() && !access.isDirectAssignee()) throw new ForbiddenException("You can't attach files to this task.");
        TaskFacts task = access.task();
        if (task.projectId() != null && "ARCHIVED".equals(task.projectStatus())) throw new UserException(ProjectService.ARCHIVED);
        if (bytes == null || bytes.length == 0) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a file.");
        if (bytes.length > MAX_FILE_MB * 1024 * 1024) throw new UserException(HttpStatus.BAD_REQUEST, "Files up to " + MAX_FILE_MB + " MB.");
        FileType type = detect(fileName, bytes);
        if (type == null) throw new UserException(HttpStatus.BAD_REQUEST, TYPES_MESSAGE);
        if (repo.count(taskId) >= MAX_PER_TASK) throw new UserException("A task can have at most " + MAX_PER_TASK + " attachments. Remove one first.");
        String businessFolder = user.businessId() == null ? "platform" : user.businessId().toString();
        Path directory = storageDir.resolve("tasks").resolve(businessFolder).resolve(taskId.toString());
        Path absolutePath = directory.resolve(UUID.randomUUID() + "." + type.extension());
        try {
            Files.createDirectories(directory);
            Files.write(absolutePath, bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String name = (fileName == null ? "" : fileName).replaceAll("[^\\w.\\- ()]", "_");
        name = name.substring(0, Math.min(120, name.length()));
        if (name.isBlank()) name = "attachment." + type.extension();
        UUID id = UUID.randomUUID();
        repo.insert(id, taskId, storageDir.relativize(absolutePath).toString().replace('\\', '/'), name, type.contentType(), bytes.length, PolicyService.sha256(bytes),
                user.id());
        tasks.touch(taskId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("attachmentId", id.toString());
        after.put("fileName", name);
        after.put("sizeBytes", bytes.length);
        activity.record(user.id(), taskId, task.projectId(), "ATTACHMENT_ADDED", null, after);
        audit.log(user.id(), "task.attachment_add", "task", taskId.toString(), null, after, ipAddress);
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("name", name);
        return out;
    }

    private AttachmentRow attachment(UUID attachmentId, UUID taskId) {
        return repo.find(attachmentId, taskId).stream().findFirst().orElseThrow(() -> new UserException(HttpStatus.NOT_FOUND, "File not found."));
    }

    /** The stored file, for anyone who can see the task; 404 when it is gone or its path leaves the storage folder. */
    public FileOut file(CurrentUser user, UUID taskId, UUID attachmentId) {
        taskService.access(user, taskId);
        AttachmentRow row = attachment(attachmentId, taskId);
        Path absolutePath = storageDir.resolve(row.filePath()).normalize();
        if (!absolutePath.startsWith(storageDir)) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        try {
            return new FileOut(row.fileName(), row.contentType(), Files.readAllBytes(absolutePath));
        } catch (IOException e) {
            throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        }
    }

    public void delete(CurrentUser user, UUID taskId, UUID attachmentId, String ipAddress) {
        TaskService.AccessibleTask access = taskService.access(user, taskId);
        AttachmentRow row = attachment(attachmentId, taskId);
        if (!canDelete(user, access, row.uploadedBy())) throw new ForbiddenException("You can't remove this file.");
        TaskFacts task = access.task();
        if (task.projectId() != null && "ARCHIVED".equals(task.projectStatus())) throw new UserException(ProjectService.ARCHIVED);
        repo.softDelete(attachmentId, user.id());
        tasks.touch(taskId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("attachmentId", attachmentId.toString());
        before.put("fileName", row.fileName());
        activity.record(user.id(), taskId, task.projectId(), "ATTACHMENT_REMOVED", before, null);
        audit.log(user.id(), "task.attachment_delete", "task", taskId.toString(), before, null, ipAddress);
    }
}
