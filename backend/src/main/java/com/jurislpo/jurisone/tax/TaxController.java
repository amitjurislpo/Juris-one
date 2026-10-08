package com.jurislpo.jurisone.tax;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.people.PeopleController;
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
    private final TaxOpsService ops;

    public TaxController(Auth auth, TaxOpsService ops) {
        this.auth = auth;
        this.ops = ops;
    }

    // declarations
    @GetMapping("/api/declarations")
    public ApiResult<Map<String, Object>> declare() {
        return ApiResult.success(ops.declarePage(auth.requirePage("declare")));
    }

    @PutMapping("/api/declarations/regime")
    public ApiResult<Void> regime(@RequestBody RegimeRequest body, HttpServletRequest req) {
        String msg = ops.setRegime(auth.assertPerm("self"), body.regime(), RequestMeta.of(req).ip());
        return msg == null ? ApiResult.success() : ApiResult.message(msg);
    }

    @PutMapping("/api/declarations/profile")
    public ApiResult<Void> profile(@RequestBody TaxOpsService.ProfileInput body) {
        ops.saveProfile(auth.assertPerm("self"), body);
        return ApiResult.success();
    }

    /** Set the declared amount (₹) for one item, e.g. {@code PUT /api/declarations/items/ppf {"amount": 50000}}. */
    @PutMapping("/api/declarations/items/{key}")
    public ApiResult<Void> item(@PathVariable String key, @RequestBody AmountBody body) {
        String msg = ops.saveItem(auth.assertPerm("self"), new TaxOpsService.ItemInput(key, body.amount()));
        return msg == null ? ApiResult.success() : ApiResult.message(msg);
    }

    /** Send every draft with an amount to Finance for verification. */
    @PostMapping("/api/declarations/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> submit(HttpServletRequest req) {
        return ApiResult.message(ops.submitForVerification(auth.assertPerm("self"), RequestMeta.of(req).ip()));
    }

    /** The original multipart route (file, item): 200 {ok, name} or 400 {error}. */
    @PostMapping(value = "/api/declarations/proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestPart(name = "file", required = false) MultipartFile file, @RequestParam(name = "item", required = false) String item) {
        try {
            String name = ops.uploadProof(auth.assertPerm("self"), item, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes());
            return ResponseEntity.ok(Map.of("ok", true, "name", name));
        } catch (UserException e) {
            if (e.status().value() != 400) throw e;
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The original route: view a proof (owner or proofs.verify); 404 when missing. */
    @GetMapping("/api/declarations/proof/{id}")
    public ResponseEntity<byte[]> proof(@PathVariable String id) {
        TaxOpsService.ProofFile f = ops.proofFile(auth.assertPerm(), id);
        if (f == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("Not found".getBytes());
        if (f.bytes() == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("The file is missing.".getBytes());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, f.mime())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + f.name() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff").body(f.bytes());
    }

    // proofs (Finance)
    @GetMapping("/api/proofs")
    public ApiResult<Map<String, Object>> proofs(@RequestParam Map<String, String> sp) {
        auth.requirePage("proofs");
        return ApiResult.success(ops.proofsPage(sp));
    }

    @GetMapping("/api/proofs/{employeeId}")
    public ApiResult<Map<String, Object>> proofDetail(@PathVariable String employeeId) {
        return ApiResult.success(ops.proofDetail(auth.assertPerm("proofs.verify"), employeeId));
    }

    /** Approve / reject one declared item ({@code {approve, reason?}}; a rejection needs a reason). */
    @PutMapping("/api/proofs/items/{id}/decision")
    public ApiResult<Void> decide(@PathVariable String id, @RequestBody ProofDecisionBody body, HttpServletRequest req) {
        return ApiResult.message(ops.decideProof(auth.assertPerm("proofs.verify"), new TaxOpsService.ProofDecision(id, body.approve(), body.reason()),
                RequestMeta.of(req).ip()));
    }

    /** Approve every pending item of one employee ({@code {"approve": true}}). */
    @PutMapping("/api/proofs/{employeeId}/decision")
    public ApiResult<Void> approveAll(@PathVariable String employeeId, @RequestBody ApproveAllBody body, HttpServletRequest req) {
        CurrentUser u = auth.assertPerm("proofs.verify");
        if (!Boolean.TRUE.equals(body.approve())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input: expected true");
        return ApiResult.message(ops.approveAllPending(u, employeeId, RequestMeta.of(req).ip()));
    }

    // Tax & TDS
    @GetMapping("/api/tax")
    public ApiResult<Map<String, Object>> tax(@RequestParam Map<String, String> sp) {
        return ApiResult.success(ops.taxPage(auth.requirePage("tax"), sp));
    }

    @GetMapping("/api/tax/{employeeId}")
    public ApiResult<Map<String, Object>> taxDetail(@PathVariable String employeeId) {
        auth.assertPerm("tax.view");
        return ApiResult.success(ops.taxDetail(employeeId));
    }

    /** Finance overrides an employee's regime ({@code {"regime": "NEW" | "OLD"}}). */
    @PutMapping("/api/tax/{employeeId}/regime")
    public ApiResult<Void> override(@PathVariable String employeeId, @RequestBody RegimeRequest body, HttpServletRequest req) {
        return ApiResult.message(ops.overrideRegime(auth.assertPerm("tax.view"), employeeId, body.regime(), RequestMeta.of(req).ip()));
    }

    /** Previous employer income and TDS this FY (Form 12B), in rupees. */
    @PutMapping("/api/tax/{employeeId}/previous-employer")
    public ApiResult<Void> previous(@PathVariable String employeeId, @RequestBody PreviousEmployerBody body, HttpServletRequest req) {
        return ApiResult.message(ops.setPreviousEmployer(auth.assertPerm("tax.view"), new TaxOpsService.PrevInput(employeeId, body.income(), body.tds()),
                RequestMeta.of(req).ip()));
    }

    /** The original route: CSV with the page's filters. */
    @GetMapping("/api/tax/export")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> sp) {
        TaxOpsService.Export e = ops.export(auth.assertPerm("tax.view"), sp);
        return PeopleController.csv(e.filename(), e.body());
    }
}
