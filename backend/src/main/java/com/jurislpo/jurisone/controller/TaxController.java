package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.TaxOperationsService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Tax declarations (self), proofs (proofs.verify), Tax & TDS (tax.view), and the original proof / tax file routes. */
@RestController
public class TaxController {

    public record RegimeRequest(String regime) {}

    public record AmountBody(Double amount) {}

    public record ProofDecisionBody(Boolean approve, String reason) {}

    public record ApproveAllBody(Boolean approve) {}

    public record PreviousEmployerBody(Double income, Double tds) {}

    private final Auth auth;
    private final TaxOperationsService ops;

    public TaxController(Auth auth, TaxOperationsService ops) {
        this.auth = auth;
        this.ops = ops;
    }

    // declarations
    @GetMapping("/api/declarations")
    public ApiResult<Map<String, Object>> declare() {
        return ApiResult.success(ops.declarePage(auth.requirePage("declare")));
    }

    @PutMapping("/api/declarations/regime")
    public ApiResult<Void> regime(@RequestBody RegimeRequest body, HttpServletRequest request) {
        String msg = ops.setRegime(auth.assertPerm("self"), body.regime(), RequestMeta.of(request).ip());
        return msg == null ? ApiResult.success() : ApiResult.message(msg);
    }

    @PutMapping("/api/declarations/profile")
    public ApiResult<Void> profile(@RequestBody TaxOperationsService.ProfileInput body) {
        ops.saveProfile(auth.assertPerm("self"), body);
        return ApiResult.success();
    }

    /** Set the declared amount (₹) for one item, e.g. {@code PUT /api/declarations/items/ppf {"amount": 50000}}. */
    @PutMapping("/api/declarations/items/{key}")
    public ApiResult<Void> item(@PathVariable String key, @RequestBody AmountBody body) {
        String msg = ops.saveItem(auth.assertPerm("self"), new TaxOperationsService.ItemInput(key, body.amount()));
        return msg == null ? ApiResult.success() : ApiResult.message(msg);
    }

    /** Send every draft with an amount to Finance for verification. */
    @PostMapping("/api/declarations/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> submit(HttpServletRequest request) {
        return ApiResult.message(ops.submitForVerification(auth.assertPerm("self"), RequestMeta.of(request).ip()));
    }

    /** Multipart upload of a proof document ({@code file}, {@code item}): 201 {data:{name}}; failures use the standard problem response. */
    @PostMapping(value = "/api/declarations/proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> upload(@RequestPart(name = "file", required = false) MultipartFile file, @RequestParam(name = "item", required = false) String item) {
        try {
            String name = ops.uploadProof(auth.assertPerm("self"), item, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes());
            return ApiResult.success("Proof uploaded", Map.of("name", name));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The original route: view a proof (owner or proofs.verify); 404 when missing. */
    @GetMapping("/api/declarations/proofs/{id}")
    public ResponseEntity<byte[]> proof(@PathVariable String id) {
        TaxOperationsService.ProofFile file = ops.proofFile(auth.assertPerm(), id);
        if (file == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("Not found".getBytes());
        if (file.bytes() == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("The file is missing.".getBytes());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.mime())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.name() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(file.bytes());
    }

    // proofs (Finance)
    @GetMapping("/api/proofs")
    public ApiResult<Map<String, Object>> proofs(@RequestParam Map<String, String> queryParams) {
        auth.requirePage("proofs");
        return ApiResult.success(ops.proofsPage(queryParams));
    }

    @GetMapping("/api/proofs/{employeeId}")
    public ApiResult<Map<String, Object>> proofDetail(@PathVariable String employeeId) {
        return ApiResult.success(ops.proofDetail(auth.assertPerm("proofs.verify"), employeeId));
    }

    /** Approve / reject one declared item ({@code {approve, reason?}}; a rejection needs a reason). */
    @PutMapping("/api/proofs/items/{id}/decision")
    public ApiResult<Void> decide(@PathVariable String id, @RequestBody ProofDecisionBody body, HttpServletRequest request) {
        return ApiResult.message(ops.decideProof(auth.assertPerm("proofs.verify"), new TaxOperationsService.ProofDecision(id, body.approve(), body.reason()),
                RequestMeta.of(request).ip()));
    }

    /** Approve every pending item of one employee ({@code {"approve": true}}). */
    @PutMapping("/api/proofs/{employeeId}/decision")
    public ApiResult<Void> approveAll(@PathVariable String employeeId, @RequestBody ApproveAllBody body, HttpServletRequest request) {
        CurrentUser user = auth.assertPerm("proofs.verify");
        if (!Boolean.TRUE.equals(body.approve())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected true");
        return ApiResult.message(ops.approveAllPending(user, employeeId, RequestMeta.of(request).ip()));
    }

    // Tax & TDS
    @GetMapping("/api/tax")
    public ApiResult<Map<String, Object>> tax(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(ops.taxPage(auth.requirePage("tax"), queryParams));
    }

    @GetMapping("/api/tax/{employeeId}")
    public ApiResult<Map<String, Object>> taxDetail(@PathVariable String employeeId) {
        auth.assertPerm("tax.view");
        return ApiResult.success(ops.taxDetail(employeeId));
    }

    /** Finance overrides an employee's regime ({@code {"regime": "NEW" | "OLD"}}). */
    @PutMapping("/api/tax/{employeeId}/regime")
    public ApiResult<Void> override(@PathVariable String employeeId, @RequestBody RegimeRequest body, HttpServletRequest request) {
        return ApiResult.message(ops.overrideRegime(auth.assertPerm("tax.view"), employeeId, body.regime(), RequestMeta.of(request).ip()));
    }

    /** Previous employer income and TDS this FY (Form 12B), in rupees. */
    @PutMapping("/api/tax/{employeeId}/previous-employer")
    public ApiResult<Void> previous(@PathVariable String employeeId, @RequestBody PreviousEmployerBody body, HttpServletRequest request) {
        return ApiResult.message(ops.setPreviousEmployer(auth.assertPerm("tax.view"), new TaxOperationsService.PreviousEmployerInput(employeeId, body.income(), body.tds()),
                RequestMeta.of(request).ip()));
    }

    /** The original route: CSV with the page's filters. */
    @GetMapping("/api/tax/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> queryParams) {
        TaxOperationsService.Export e = ops.export(auth.assertPerm("tax.view"), queryParams);
        return PeopleController.csv(e.filename(), e.body());
    }
}
