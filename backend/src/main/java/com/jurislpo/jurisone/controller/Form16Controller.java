package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.Form16Service;
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
 * Form 16. Employees read their own ({@code /mine}, {@code /forms/{id}}); Finance (tax.view to read, tax.view + pay.close to
 * publish, export the 24Q working file and set the deductor's TAN / PAN) sees everyone's. HR roles never hold those
 * permissions, so they see nothing but their own.
 */
@RestController
@RequestMapping("/api/form16")
public class Form16Controller {

    private final Auth auth;
    private final Form16Service form16;

    public Form16Controller(Auth auth, Form16Service form16) {
        this.auth = auth;
        this.form16 = form16;
    }

    /** Finance: months closed, deductor details and who has a Form 16 for the year ({@code ?fy=2026-27}). */
    @GetMapping
    public ApiResult<Map<String, Object>> overview(@RequestParam(name = "fy", required = false) String fy) {
        return ApiResult.success(form16.overview(auth.requirePage("form16-admin"), fy));
    }

    /** The signed-in employee's own published Form 16s. */
    @GetMapping("/mine")
    public ApiResult<List<Map<String, Object>>> mine() {
        return ApiResult.success(form16.mine(auth.requirePage("form16")));
    }

    /** {@code {tan, pan}} — the deductor's numbers, both optional (blank clears). */
    @PutMapping("/deductor")
    public ApiResult<Void> deductor(@RequestBody Form16Service.DeductorInput body, HttpServletRequest req) {
        form16.saveDeductor(auth.assertPerm("pay.close"), body, RequestMeta.of(req).ip());
        return ApiResult.message("Deductor details saved");
    }

    /** Generates and publishes Form 16 for the year (all 12 months must be closed). Published forms are never replaced. */
    @PostMapping("/{fy}/forms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Form16Service.Publication> publish(@PathVariable String fy, HttpServletRequest req) {
        Form16Service.Publication p = form16.publish(auth.assertPerm("pay.close"), fy, RequestMeta.of(req).ip());
        String msg = p.published() == 0 ? "Every Form 16 for FY " + fy + " was already published"
                : p.published() + " Form 16" + (p.published() == 1 ? "" : "s") + " published for FY " + fy + (p.alreadyPublished() > 0 ? " (" + p.alreadyPublished() + " already published)" : "");
        return ApiResult.success(msg, p);
    }

    /** One published Form 16 as data (its owner, or Finance). */
    @GetMapping("/forms/{id}")
    public ApiResult<Map<String, Object>> form(@PathVariable String id) {
        return ApiResult.success(form16.view(auth.assertPerm("self", "tax.view"), id));
    }

    @GetMapping("/forms/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id) {
        Form16Service.PdfFile f = form16.pdf(auth.assertPerm("self", "tax.view"), id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.filename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(f.bytes());
    }

    /** The data a quarter's Form 24Q needs, as a CSV working file (Finance; audited because it carries PANs). */
    @GetMapping("/{fy}/24q")
    public ResponseEntity<byte[]> form24q(@PathVariable String fy, @RequestParam(name = "quarter", required = false) String quarter, HttpServletRequest req) {
        Form16Service.Export e = form16.form24q(auth.assertPerm("pay.close"), fy, quarter, RequestMeta.of(req).ip());
        return PeopleController.csv(e.filename(), e.body());
    }
}
