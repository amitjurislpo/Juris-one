package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.TaskAttachmentService;
import com.jurislpo.jurisone.service.TaskCommentService;
import com.jurislpo.jurisone.service.TaskQueryService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Comments, history and files of a task (page {@code my-tasks}); each needs the person to see the task. */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}")
public class TaskCollaborationController {

    private final Auth auth;
    private final TaskQueryService taskQueries;
    private final TaskCommentService comments;
    private final TaskAttachmentService attachments;

    public TaskCollaborationController(Auth auth, TaskQueryService taskQueries, TaskCommentService comments, TaskAttachmentService attachments) {
        this.auth = auth;
        this.taskQueries = taskQueries;
        this.comments = comments;
        this.attachments = attachments;
    }

    private CurrentUser user() {
        return auth.requirePage("my-tasks");
    }

    private static String ipAddress(HttpServletRequest request) {
        return RequestMeta.of(request).ipAddress();
    }

    // ───────────── comments ─────────────

    /** {@code ?page=}: 50 per page, oldest first. */
    @GetMapping("/comments")
    public ApiResult<Map<String, Object>> comments(@PathVariable String taskId, @RequestParam(name = "page", required = false) String page) {
        return ApiResult.success(comments.page(user(), Check.uuid(taskId), page));
    }

    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> addComment(@PathVariable String taskId, @RequestBody TaskCommentService.CommentInput body, HttpServletRequest request) {
        return ApiResult.success("Comment added", Map.of("id", comments.add(user(), Check.uuid(taskId), body, ipAddress(request))));
    }

    @PatchMapping("/comments/{commentId}")
    public ApiResult<Void> editComment(@PathVariable String taskId, @PathVariable String commentId, @RequestBody TaskCommentService.CommentInput body,
                                       HttpServletRequest request) {
        comments.edit(user(), Check.uuid(taskId), Check.uuid(commentId), body, ipAddress(request));
        return ApiResult.message("Comment saved");
    }

    @DeleteMapping("/comments/{commentId}")
    public ApiResult<Void> deleteComment(@PathVariable String taskId, @PathVariable String commentId, HttpServletRequest request) {
        comments.delete(user(), Check.uuid(taskId), Check.uuid(commentId), ipAddress(request));
        return ApiResult.message("Comment deleted");
    }

    // ───────────── history ─────────────

    /** {@code ?page=}: 50 per page, newest first. */
    @GetMapping("/activity")
    public ApiResult<Map<String, Object>> activity(@PathVariable String taskId, @RequestParam(name = "page", required = false) String page) {
        return ApiResult.success(taskQueries.activity(user(), Check.uuid(taskId), page));
    }

    // ───────────── files ─────────────

    @PostMapping("/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> upload(@PathVariable String taskId, @RequestPart(name = "file", required = false) MultipartFile file,
                                                 HttpServletRequest request) throws IOException {
        Map<String, String> uploaded = attachments.upload(user(), Check.uuid(taskId), file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getBytes(), ipAddress(request));
        return ApiResult.success("File attached", uploaded);
    }

    @GetMapping("/attachments/{attachmentId}/file")
    public ResponseEntity<byte[]> file(@PathVariable String taskId, @PathVariable String attachmentId) {
        TaskAttachmentService.FileOut file = attachments.file(user(), Check.uuid(taskId), Check.uuid(attachmentId));
        ContentDisposition disposition = (file.isInline() ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(file.name(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    @DeleteMapping("/attachments/{attachmentId}")
    public ApiResult<Void> deleteAttachment(@PathVariable String taskId, @PathVariable String attachmentId, HttpServletRequest request) {
        attachments.delete(user(), Check.uuid(taskId), Check.uuid(attachmentId), ipAddress(request));
        return ApiResult.message("File removed");
    }
}
