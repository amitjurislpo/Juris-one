package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.LettersService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR letters. HR / the owner ({@code letters.manage}) keep the templates and issue letters; a template that states a salary
 * can only be previewed, issued and opened with {@code struct.manage} as well. Employees read and download the letters issued
 * to them ({@code /mine}, {@code /{id}/pdf}).
 */
@RestController
@RequestMapping("/api/letters")
public class LettersController {

    private final Auth auth;
    private final LettersService letters;

    public LettersController(Auth auth, LettersService letters) {
        this.auth = auth;
        this.letters = letters;
    }

    // employee
    @GetMapping("/mine")
    public ApiResult<List<Map<String, Object>>> mine() {
        return ApiResult.success(letters.mine(auth.requirePage("my-letters")));
    }

    // HR / owner
    /** The merge fields a template may use. */
    @GetMapping("/fields")
    public ApiResult<Map<String, Object>> fields() {
        auth.requirePage("letters");
        return ApiResult.success(letters.fields());
    }

    @GetMapping("/templates")
    public ApiResult<List<Map<String, Object>>> templates() {
        auth.requirePage("letters");
        return ApiResult.success(letters.templates());
    }

    @GetMapping("/templates/{id}")
    public ApiResult<Map<String, Object>> template(@PathVariable String id) {
        auth.requirePage("letters");
        return ApiResult.success(letters.template(id));
    }

    /** {@code {type, name, subject, body}} — a new template (unknown merge fields are rejected). */
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> createTemplate(@RequestBody LettersService.TemplateInput body, HttpServletRequest request) {
        String id = letters.createTemplate(auth.requirePage("letters"), body, RequestMeta.of(request).ip());
        return ApiResult.success("Template saved", Map.of("id", id));
    }

    /** {@code {name, subject, body, active?}} */
    @PutMapping("/templates/{id}")
    public ApiResult<Void> updateTemplate(@PathVariable String id, @RequestBody LettersService.TemplateInput body, HttpServletRequest request) {
        letters.updateTemplate(auth.requirePage("letters"), id, body, RequestMeta.of(request).ip());
        return ApiResult.message("Template saved");
    }

    /** Employees a letter can be issued to. */
    @GetMapping("/employees")
    public ApiResult<List<Map<String, Object>>> employees() {
        auth.requirePage("letters");
        return ApiResult.success(letters.employees());
    }

    /** What a letter would say (nothing is saved). */
    @GetMapping("/previews")
    public ApiResult<Map<String, Object>> preview(@RequestParam(name = "templateId", required = false) String templateId,
                                                  @RequestParam(name = "employeeId", required = false) String employeeId,
                                                  @RequestParam(name = "lastWorkingDate", required = false) String lastWorkingDate) {
        return ApiResult.success(letters.preview(auth.requirePage("letters"), templateId, employeeId, lastWorkingDate));
    }

    /** {@code {templateId, employeeId, lastWorkingDate?}} — issues the letter. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, Object>> issue(@RequestBody LettersService.IssueInput body, HttpServletRequest request) {
        Map<String, Object> out = letters.issue(auth.requirePage("letters"), body, RequestMeta.of(request).ip());
        return ApiResult.success("Letter " + out.get("reference") + " issued · emailed to the employee and added to their My letters", out);
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(name = "employeeId", required = false) String employeeId) {
        return ApiResult.success(letters.list(auth.requirePage("letters"), employeeId));
    }

    // the letter's employee, or HR
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.success(letters.detail(auth.assertPerm("self", "letters.manage"), id));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        LettersService.PdfFile file = letters.pdf(auth.assertPerm("self", "letters.manage"), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }
}
