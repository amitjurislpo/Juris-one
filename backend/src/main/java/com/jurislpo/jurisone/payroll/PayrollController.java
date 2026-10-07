package com.jurislpo.jurisone.payroll;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.people.PeopleController;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Payroll (pay.view / pay.close), salary structures (struct.manage), payslips (self) and the payroll file routes. */
@RestController
public class PayrollController {

    public record MonthRequest(String month) {}

    public record ResetRequest(String month, String employeeId) {}

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

    @PostMapping("/api/payroll/adjustments")
    public ApiResult<Void> save(@RequestBody PayrollOpsService.AdjInput body, HttpServletRequest req) {
        return ApiResult.message(ops.saveAdjustment(auth.assertPerm("pay.view"), body, RequestMeta.of(req).ip()));
    }

    @PostMapping("/api/payroll/adjustments/reset")
    public ApiResult<Void> reset(@RequestBody ResetRequest body) {
        return ApiResult.message(ops.resetAdjustment(auth.assertPerm("pay.view"), body.month(), body.employeeId()));
    }

    @PostMapping("/api/payroll/close")
    public ApiResult<Void> close(@RequestBody MonthRequest body) {
        return ApiResult.message(ops.approveAndClose(auth.assertPerm("pay.close"), body.month()));
    }

    @GetMapping("/api/structures")
    public ApiResult<Map<String, Object>> structures(@RequestParam Map<String, String> sp) {
        auth.requirePage("structures");
        return ApiResult.success(ops.structuresPage(sp));
    }

    @PostMapping("/api/structures")
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
