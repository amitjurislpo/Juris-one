package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.PolicyService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Company policies: everyone reads and accepts them ({@code /mine}, {@code /pending}, acknowledgements); HR / the owner
 * ({@code policies.manage}) write, publish and archive them, follow who has accepted and export the evidence.
 */
@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    public record StatusBody(String status) {}

    public record AckBody(Integer version) {}

    private final Auth auth;
    private final PolicyService policies;

    public PolicyController(Auth auth, PolicyService policies) {
        this.auth = auth;
        this.policies = policies;
    }

    private CurrentUser manager() {
        return auth.assertPerm(Permissions.POLICIES_MANAGE);
    }

    // everyone
    @GetMapping("/mine")
    public ApiResult<List<Map<String, Object>>> mine() {
        return ApiResult.success(policies.mine(auth.assertPerm()));
    }

    /** Current versions the signed-in user still has to accept (the acknowledgement screen). */
    @GetMapping("/pending")
    public ApiResult<List<Map<String, Object>>> pending() {
        return ApiResult.success(policies.pending(auth.assertPerm()));
    }

    /** {@code {"version": n}} — "I have read and accept" version n (must be the current one). */
    @PostMapping("/{id}/acknowledgements")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> acknowledge(@PathVariable String id, @RequestBody AckBody body, HttpServletRequest request) {
        RequestMeta requestMeta = RequestMeta.of(request);
        return ApiResult.message(policies.acknowledge(auth.assertPerm(), id, body.version(), requestMeta.ipAddress(), requestMeta.userAgent()));
    }

    /** The PDF of a published version. */
    @GetMapping("/{id}/versions/{version}/file")
    public ResponseEntity<byte[]> versionFile(@PathVariable String id, @PathVariable int version) {
        return pdf(policies.file(auth.assertPerm(), id, version));
    }

    // HR / owner
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        manager();
        return ApiResult.success(policies.list());
    }

    /** {@code {title, body, requiresAcknowledgement?}} — a new draft. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody PolicyService.PolicyInput body, HttpServletRequest request) {
        String id = policies.create(manager(), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Draft saved", Map.of("id", id));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        manager();
        return ApiResult.success(policies.detail(id));
    }

    /** Edits the draft; publishing it makes a new version. */
    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody PolicyService.PolicyInput body, HttpServletRequest request) {
        policies.update(manager(), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Draft saved");
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        policies.delete(manager(), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Draft deleted");
    }

    /** {@code {"status": "PUBLISHED" | "ARCHIVED"}} */
    @PutMapping("/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody StatusBody body, HttpServletRequest request) {
        return ApiResult.message(policies.setStatus(manager(), id, body.status(), RequestMeta.of(request).ipAddress()));
    }

    /** Multipart {@code file}: the PDF for the draft. */
    @PutMapping(value = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<Map<String, String>> attach(@PathVariable String id, @RequestPart(name = "file", required = false) MultipartFile file, HttpServletRequest request) {
        CurrentUser user = manager();
        try {
            String name = policies.attach(user, id, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes(), RequestMeta.of(request).ipAddress());
            return ApiResult.success("Attached " + name, Map.of("name", name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DeleteMapping("/{id}/file")
    public ApiResult<Void> detach(@PathVariable String id, HttpServletRequest request) {
        policies.detach(manager(), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("PDF removed from the draft");
    }

    /** The draft's PDF. */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> draftFile(@PathVariable String id) {
        return pdf(policies.file(manager(), id, null));
    }

    /** Bell reminder to everyone who hasn't accepted the current version. */
    @PostMapping("/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Integer>> remind(@PathVariable String id, HttpServletRequest request) {
        int remindedCount = policies.remind(manager(), id, RequestMeta.of(request).ipAddress());
        return ApiResult.success(remindedCount == 0 ? "Everyone has already accepted it" : "Reminder sent to " + remindedCount + " " + (remindedCount == 1 ? "person" : "people"), Map.of("count", remindedCount));
    }

    /** The legal evidence: every acknowledgement of every version, as recorded (CSV, or {@code ?format=pdf}; audited). */
    @GetMapping("/{id}/acknowledgements/export")
    public ResponseEntity<byte[]> evidence(@PathVariable String id, @org.springframework.web.bind.annotation.RequestParam(name = "format", required = false) String format,
                                           HttpServletRequest request) {
        if ("pdf".equals(format)) {
            PolicyService.PolicyFile file = policies.evidencePdf(manager(), id, RequestMeta.of(request).ipAddress());
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(file.bytes());
        }
        PolicyService.Export exportFile = policies.evidence(manager(), id, RequestMeta.of(request).ipAddress());
        return PeopleController.csv(exportFile.filename(), exportFile.body());
    }

    static ResponseEntity<byte[]> pdf(PolicyService.PolicyFile file) {
        if (file == null) throw new UserException(HttpStatus.NOT_FOUND, "File not found.");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
}
