package com.jurislpo.jurisone.payroll;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.people.PeopleController;
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
    private final PayrollOpsService ops;

    public PayrollController(Auth auth, PayrollOpsService ops) {
        this.auth = auth;
        this.ops = ops;
    }

    @GetMapping("/api/payroll")
    public ApiResult<Map<String, Object>> page(@RequestParam Map<String, String> sp) {
        return ApiResult.success(ops.page(auth.requirePage("payroll"), sp));
    }

    /** Set Finance's edits for one employee while the run is open (each edit audited). */
    @PutMapping("/api/payroll/{month}/adjustments/{employeeId}")
    public ApiResult<Void> save(@PathVariable String month, @PathVariable String employeeId, @RequestBody AdjustmentBody body, HttpServletRequest req) {
        PayrollOpsService.AdjInput in = new PayrollOpsService.AdjInput(month, employeeId, body.lopDays(), body.bonus(), body.otherDeduction(), body.note());
        return ApiResult.message(ops.saveAdjustment(auth.assertPerm("pay.view"), in, RequestMeta.of(req).ip()));
    }

    /** Remove Finance's edits for one employee (back to the computed figures). */
    @DeleteMapping("/api/payroll/{month}/adjustments/{employeeId}")
    public ApiResult<Void> reset(@PathVariable String month, @PathVariable String employeeId) {
        return ApiResult.message(ops.resetAdjustment(auth.assertPerm("pay.view"), month, employeeId));
    }

    /** Approve & close the month ({@code {"status": "CLOSED"}}, pay.close): snapshots and publishes every payslip. */
    @PutMapping("/api/payroll/{month}/status")
    public ApiResult<Void> close(@PathVariable String month, @RequestBody StatusBody body) {
        CurrentUser u = auth.assertPerm("pay.close");
        if (!"CLOSED".equals(body.status())) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid option: expected \"CLOSED\"");
        return ApiResult.message(ops.approveAndClose(u, month));
    }

    @GetMapping("/api/structures")
    public ApiResult<Map<String, Object>> structures(@RequestParam Map<String, String> sp) {
        auth.requirePage("structures");
        return ApiResult.success(ops.structuresPage(sp));
    }

    @PostMapping("/api/structures")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> addStructure(@RequestBody PayrollOpsService.StructureInput body, HttpServletRequest req) {
        return ApiResult.message(ops.addStructure(auth.assertPerm("struct.manage"), body, RequestMeta.of(req).ip()));
    }

    @GetMapping("/api/payslips")
    public ApiResult<Map<String, Object>> payslips() {
        return ApiResult.success(ops.payslipsPage(auth.requirePage("payslips")));
    }

    /** The original route: 400 for a bad month/type, CSV otherwise. */
    @GetMapping("/api/payroll/export")
    public ResponseEntity<byte[]> export(@RequestParam(name = "m", required = false) String month, @RequestParam(required = false) String type, HttpServletRequest req) {
        PayrollOpsService.Export e = ops.export(auth.assertPerm("pay.view"), month, type, RequestMeta.of(req).ip());
        if (e == null) return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("Bad request".getBytes());
        return PeopleController.csv(e.filename(), e.body());
    }

    /** The original route: your own payslip (self) or anyone's (pay.view); 404 when missing. */
    @GetMapping("/api/payslips/{month}/{code}")
    public ResponseEntity<byte[]> payslip(@PathVariable String month, @PathVariable String code) {
        PayrollOpsService.PdfFile f = ops.payslipPdf(auth.assertPerm(), month, code);
        if (f == null) return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body("Not found".getBytes());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(f.bytes());
    }
}
