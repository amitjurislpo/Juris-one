package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.PeopleService;
import com.jurislpo.jurisone.service.PhotoService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.PeopleRules.EmployeeInput;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/people (people.view / people.add / access.toggle), /api/directory (dir) and the people CSV export. */
@RestController
public class PeopleController {

    public record AccessChange(boolean enabled) {}

    public record StatusChange(String status, String exitDate) {}

    private final Auth auth;
    private final PeopleService people;
    private final PhotoService photos;

    public PeopleController(Auth auth, PeopleService people, PhotoService photos) {
        this.photos = photos;
        this.auth = auth;
        this.people = people;
    }

    @GetMapping("/api/v1/people")
    public ApiResult<PeopleService.Page> page(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(people.page(auth.requirePage("employees"), queryParams));
    }

    @GetMapping("/api/v1/directory")
    public ApiResult<PeopleService.Directory> directory(@RequestParam Map<String, String> queryParams) {
        auth.requirePage("directory");
        return ApiResult.success(people.directory(queryParams));
    }

    /** The profile drawer (masked identity numbers; no salary). */
    @GetMapping("/api/v1/people/{id}")
    public ApiResult<PeopleService.Profile> profile(@PathVariable String id) {
        return ApiResult.success(people.profile(auth.assertPerm("people.view"), id));
    }

    /** The directory photo of an employee of this business (any signed-in user): image bytes, cached for an hour; 404 when none. */
    @GetMapping("/api/v1/people/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        auth.current();
        PhotoService.Photo photo = photos.read(id);
        if (photo == null) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.NOT_FOUND, "No photo.");
        String etag = "\"" + photo.version() + "\"";
        ResponseEntity.BodyBuilder builder = etag.equals(ifNoneMatch) ? ResponseEntity.status(HttpStatus.NOT_MODIFIED) : ResponseEntity.ok();
        builder = builder.eTag(etag).header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").header("X-Content-Type-Options", "nosniff");
        return etag.equals(ifNoneMatch) ? builder.build() : builder.contentType(MediaType.parseMediaType(photo.contentType())).body(photo.data());
    }

    /** HR / the admin set someone's photo (raw image bytes as the body, as for My profile): the same rule as editing that employee. */
    @PutMapping("/api/v1/people/{id}/photo")
    public ApiResult<Map<String, Long>> setPhoto(@PathVariable String id, @RequestBody(required = false) byte[] body, @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String type, HttpServletRequest request) {
        long photoVersion = photos.setFor(auth.assertPerm("people.add"), id, type, body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Photo saved", Map.of("photo", photoVersion));
    }

    @DeleteMapping("/api/v1/people/{id}/photo")
    public ApiResult<Void> removePhoto(@PathVariable String id, HttpServletRequest request) {
        photos.removeFor(auth.assertPerm("people.add"), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Photo removed");
    }

    /** Full phone number and address (people.add); every read is audited. */
    @GetMapping("/api/v1/people/{id}/contact")
    public ApiResult<Map<String, String>> reveal(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.success(people.reveal(auth.assertPerm("people.add"), id, RequestMeta.of(request).ipAddress()));
    }

    @PutMapping("/api/v1/people/{id}/access")
    public ApiResult<Void> access(@PathVariable String id, @RequestBody AccessChange body, HttpServletRequest request) {
        return ApiResult.message(people.toggleAccess(auth.assertPerm("access.toggle"), id, body.enabled(), RequestMeta.of(request).ipAddress()));
    }

    /**
     * Sets the employment status: {@code {"status": "ACTIVE"}} makes a probation employee permanent (the admin (owner) or HR);
     * {@code {"status": "EXITED", "exitDate": "YYYY-MM-DD"}} marks them exited, turns access off and ends their sessions.
     */
    @PutMapping("/api/v1/people/{id}/status")
    public ApiResult<Void> setStatus(@PathVariable String id, @RequestBody StatusChange body, HttpServletRequest request) {
        String ipAddress = RequestMeta.of(request).ipAddress();
        String status = body == null || body.status() == null ? "" : body.status();
        return switch (status) {
            case "ACTIVE" -> ApiResult.message(people.confirmEmployee(auth.assertPerm("people.add"), id, ipAddress));
            case "EXITED" -> ApiResult.message(people.markExited(auth.assertPerm("people.add"), id, body.exitDate(), ipAddress));
            default -> throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.BAD_REQUEST, "The status can be set to ACTIVE or EXITED.");
        };
    }

    /** Add (no {@code id}) or edit form options. */
    @GetMapping("/api/v1/people/form-options")
    public ApiResult<PeopleService.FormOptions> form(@RequestParam(required = false) String id) {
        return ApiResult.success(people.formOptions(auth.assertPerm("people.add"), id));
    }

    @PostMapping("/api/v1/people")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody EmployeeInput body, HttpServletRequest request) {
        PeopleService.Saved saved = people.save(auth.assertPerm("people.add"), body, null, RequestMeta.of(request).ipAddress());
        return ApiResult.success(saved.message(), Map.of("id", saved.id(), "code", saved.code()));
    }

    @PutMapping("/api/v1/people/{id}")
    public ApiResult<Map<String, String>> update(@PathVariable String id, @RequestBody EmployeeInput body, HttpServletRequest request) {
        PeopleService.Saved saved = people.save(auth.assertPerm("people.add"), body, id, RequestMeta.of(request).ipAddress());
        return ApiResult.success(saved.message(), Map.of("id", saved.id(), "code", saved.code()));
    }

    /** CSV (people.view): same filters as the page; no salary and no identity numbers. */
    @GetMapping("/api/v1/people/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> queryParams) {
        PeopleService.Export exportFile = people.export(auth.assertPerm("people.view"), queryParams);
        return csv(exportFile.filename(), exportFile.body());
    }

    public static ResponseEntity<byte[]> csv(String filename, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
