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

    public record AccessChange(boolean on) {}

    public record ExitRequest(String exitDate) {}

    private final Auth auth;
    private final PeopleService people;
    private final PhotoService photos;

    public PeopleController(Auth auth, PeopleService people, PhotoService photos) {
        this.photos = photos;
        this.auth = auth;
        this.people = people;
    }

    @GetMapping("/api/people")
    public ApiResult<PeopleService.Page> page(@RequestParam Map<String, String> sp) {
        return ApiResult.success(people.page(auth.requirePage("employees"), sp));
    }

    @GetMapping("/api/directory")
    public ApiResult<PeopleService.Directory> directory(@RequestParam Map<String, String> sp) {
        auth.requirePage("directory");
        return ApiResult.success(people.directory(sp));
    }

    /** The profile drawer (masked identity numbers; no salary). */
    @GetMapping("/api/people/{id}")
    public ApiResult<PeopleService.Profile> profile(@PathVariable String id) {
        return ApiResult.success(people.profile(auth.assertPerm("people.view"), id));
    }

    /** The directory photo of an employee of this business (any signed-in user): image bytes, cached for an hour; 404 when none. */
    @GetMapping("/api/people/{id}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable String id, @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        auth.current();
        PhotoService.Photo p = photos.read(id);
        if (p == null) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.NOT_FOUND, "No photo.");
        String etag = "\"" + p.version() + "\"";
        ResponseEntity.BodyBuilder ok = etag.equals(ifNoneMatch) ? ResponseEntity.status(HttpStatus.NOT_MODIFIED) : ResponseEntity.ok();
        ok = ok.eTag(etag).header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600").header("X-Content-Type-Options", "nosniff");
        return etag.equals(ifNoneMatch) ? ok.build() : ok.contentType(MediaType.parseMediaType(p.contentType())).body(p.data());
    }

    /** HR / the admin set someone's photo (raw image bytes as the body, as for My profile): the same rule as editing that employee. */
    @PutMapping("/api/people/{id}/photo")
    public ApiResult<Map<String, Long>> setPhoto(@PathVariable String id, @RequestBody(required = false) byte[] body, @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String type, HttpServletRequest req) {
        long v = photos.setFor(auth.assertPerm("people.add"), id, type, body, RequestMeta.of(req).ip());
        return ApiResult.success("Photo saved", Map.of("photo", v));
    }

    @DeleteMapping("/api/people/{id}/photo")
    public ApiResult<Void> removePhoto(@PathVariable String id, HttpServletRequest req) {
        photos.removeFor(auth.assertPerm("people.add"), id, RequestMeta.of(req).ip());
        return ApiResult.message("Photo removed");
    }

    /** Full phone number and address (people.add); every read is audited. */
    @GetMapping("/api/people/{id}/contact")
    public ApiResult<Map<String, String>> reveal(@PathVariable String id, HttpServletRequest req) {
        return ApiResult.success(people.reveal(auth.assertPerm("people.add"), id, RequestMeta.of(req).ip()));
    }

    @PutMapping("/api/people/{id}/access")
    public ApiResult<Void> access(@PathVariable String id, @RequestBody AccessChange body, HttpServletRequest req) {
        return ApiResult.message(people.toggleAccess(auth.assertPerm("access.toggle"), id, body.on(), RequestMeta.of(req).ip()));
    }

    /** Probation → permanent: the admin (owner) or HR. */
    @PutMapping("/api/people/{id}/confirm")
    public ApiResult<Void> confirm(@PathVariable String id, HttpServletRequest req) {
        return ApiResult.message(people.confirmEmployee(auth.assertPerm("people.add"), id, RequestMeta.of(req).ip()));
    }

    /** Mark exited ({@code {"exitDate": "YYYY-MM-DD"}}); access is turned off and sessions end. */
    @PutMapping("/api/people/{id}/exit")
    public ApiResult<Void> exit(@PathVariable String id, @RequestBody ExitRequest body, HttpServletRequest req) {
        return ApiResult.message(people.markExited(auth.assertPerm("people.add"), id, body.exitDate(), RequestMeta.of(req).ip()));
    }

    /** Add (no {@code id}) or edit form options. */
    @GetMapping("/api/people/form-options")
    public ApiResult<PeopleService.FormOptions> form(@RequestParam(required = false) String id) {
        return ApiResult.success(people.formOptions(auth.assertPerm("people.add"), id));
    }

    @PostMapping("/api/people")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody EmployeeInput body, HttpServletRequest req) {
        PeopleService.Saved s = people.save(auth.assertPerm("people.add"), body, null, RequestMeta.of(req).ip());
        return ApiResult.success(s.msg(), Map.of("id", s.id(), "code", s.code()));
    }

    @PutMapping("/api/people/{id}")
    public ApiResult<Map<String, String>> update(@PathVariable String id, @RequestBody EmployeeInput body, HttpServletRequest req) {
        PeopleService.Saved s = people.save(auth.assertPerm("people.add"), body, id, RequestMeta.of(req).ip());
        return ApiResult.success(s.msg(), Map.of("id", s.id(), "code", s.code()));
    }

    /** CSV (people.view): same filters as the page; no salary and no identity numbers. */
    @GetMapping("/api/people/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> sp) {
        PeopleService.Export e = people.export(auth.assertPerm("people.view"), sp);
        return csv(e.filename(), e.body());
    }

    public static ResponseEntity<byte[]> csv(String filename, String body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
