package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.PayrollOperationsService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Payroll (pay.view / pay.close), salary structures (struct.manage), payslips (self) and the payroll file routes. */
@RestController
public class PayrollController {

    /** Finance's edits for one employee's month, in rupees ({@code lopDays} null = use attendance). */
    public record AdjustmentBody(Double lopDays, Double bonus, Double otherDeduction, String note) {}

    public record StatusBody(String status) {}

    private final Auth auth;
    private final PayrollOperationsService ops;

    public PayrollController(Auth auth, PayrollOperationsService ops) {
        this.auth = auth;
        this.ops = ops;
    }

    @GetMapping("/api/payroll")
    public ApiResult<Map<String, Object>> page(@RequestParam Map<String, String> queryParams) {
        return ApiResult.success(ops.page(auth.requirePage("payroll"), queryParams));
    }

    /** Set Finance's edits for one employee while the run is open (each edit audited). */
    @PutMapping("/api/payroll/{month}/adjustments/{employeeId}")
    public ApiResult<Void> save(@PathVariable String month, @PathVariable String employeeId, @RequestBody AdjustmentBody body, HttpServletRequest request) {
        PayrollOperationsService.AdjustmentInput in = new PayrollOperationsService.AdjustmentInput(month, employeeId, body.lopDays(), body.bonus(), body.otherDeduction(), body.note());
        return ApiResult.message(ops.saveAdjustment(auth.assertPerm("pay.view"), in, RequestMeta.of(request).ip()));
    }

    /** Remove Finance's edits for one employee (back to the computed figures). */
    @DeleteMapping("/api/payroll/{month}/adjustments/{employeeId}")
    public ApiResult<Void> reset(@PathVariable String month, @PathVariable String employeeId) {
        return ApiResult.message(ops.resetAdjustment(auth.assertPerm("pay.view"), month, employeeId));
    }

    /** Approve & close the month ({@code {"status": "CLOSED"}}, pay.close): snapshots and publishes every payslip. */
    @PutMapping("/api/payroll/{month}/status")
    public ApiResult<Void> close(@PathVariable String month, @RequestBody StatusBody body) {
        CurrentUser user = auth.assertPerm("pay.close");
        if (!"CLOSED".equals(body.status())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid option: expected \"CLOSED\"");
        return ApiResult.message(ops.approveAndClose(user, month));
    }

    @GetMapping("/api/structures")
    public ApiResult<Map<String, Object>> structures(@RequestParam Map<String, String> queryParams) {
        auth.requirePage("structures");
        return ApiResult.success(ops.structuresPage(queryParams));
    }

    @PostMapping("/api/structures")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> addStructure(@RequestBody PayrollOperationsService.StructureInput body, HttpServletRequest request) {
        return ApiResult.message(ops.addStructure(auth.assertPerm("struct.manage"), body, RequestMeta.of(request).ip()));
    }

    @GetMapping("/api/payslips")
    public ApiResult<Map<String, Object>> payslips() {
        return ApiResult.success(ops.payslipsPage(auth.requirePage("payslips")));
    }

    /** The original route: 400 for a bad month/type, CSV otherwise. */
    @GetMapping("/api/payroll/export")
    public ResponseEntity<byte[]> export(@RequestParam(name = "month", required = false) String month, @RequestParam(required = false) String type, HttpServletRequest request) {
        PayrollOperationsService.Export e = ops.export(auth.assertPerm("pay.view"), month, type, RequestMeta.of(request).ip());
        if (e == null) return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Bad request".getBytes());
        return PeopleController.csv(e.filename(), e.body());
    }

    /** The original route: your own payslip (self) or anyone's (pay.view); 404 when missing. */
    @GetMapping("/api/payslips/{month}/{code}")
    public ResponseEntity<byte[]> payslip(@PathVariable String month, @PathVariable String code) {
        PayrollOperationsService.PdfFile file = ops.payslipPdf(auth.assertPerm(), month, code);
        if (file == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("Not found".getBytes());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(file.bytes());
    }
}
