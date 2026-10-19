package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.ForbiddenException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.ExitService;
import com.jurislpo.jurisone.service.FnfService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee exit and full & final settlement.
 *
 * <ul>
 *   <li>{@code /api/exits/me}: the signed-in employee's own exit (resign, withdraw, status) — {@code self}.</li>
 *   <li>{@code /api/exits}: the cases, notice, status and clearance — HR ({@code exit.manage}); Finance ({@code pay.view}) can read them and
 *       clear the Finance item.</li>
 *   <li>{@code /api/exits/{id}/settlement}: the amounts — {@code pay.view}, {@code pay.close}, {@code struct.manage} and the owner only;
 *       drafted with {@code pay.view}, approved with {@code pay.close}. HR never receives a salary figure.</li>
 * </ul>
 */
@RestController
public class ExitController {

    private final Auth auth;
    private final ExitService exits;
    private final FnfService fnf;

    public ExitController(Auth auth, ExitService exits, FnfService fnf) {
        this.auth = auth;
        this.exits = exits;
        this.fnf = fnf;
    }

    // ───────────── my exit ─────────────

    @GetMapping("/api/exits/me")
    public ApiResult<Map<String, Object>> mine() {
        return ApiResult.success(exits.mine(auth.requirePage("my-exit")));
    }

    /** {@code {reason, requestedLastDay}} — the employee resigns. */
    @PostMapping("/api/exits/me")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> resign(@RequestBody ExitService.ResignInput body, HttpServletRequest req) {
        return ApiResult.message(exits.resign(auth.requirePage("my-exit"), body, RequestMeta.of(req).ip()));
    }

    public record WithdrawBody(String status, String comment) {}

    /** {@code {"status": "WITHDRAWN", comment?}} */
    @PutMapping("/api/exits/me/status")
    public ApiResult<Void> withdraw(@RequestBody WithdrawBody body, HttpServletRequest req) {
        CurrentUser u = auth.requirePage("my-exit");
        if (!"WITHDRAWN".equals(body.status())) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.BAD_REQUEST, "Invalid option: expected \"WITHDRAWN\"");
        return ApiResult.message(exits.withdraw(u, body.comment(), RequestMeta.of(req).ip()));
    }

    // ───────────── policy ─────────────

    @GetMapping("/api/exits/policy")
    public ApiResult<Map<String, Object>> policy() {
        auth.assertPerm("exit.manage");
        return ApiResult.success(exits.policy());
    }

    @PutMapping("/api/exits/policy")
    public ApiResult<Void> savePolicy(@RequestBody ExitService.PolicyInput body, HttpServletRequest req) {
        return ApiResult.message(exits.savePolicy(auth.assertPerm("exit.manage"), body, RequestMeta.of(req).ip()));
    }

    // ───────────── cases (HR; Finance reads) ─────────────

    @GetMapping("/api/exits")
    public ApiResult<List<Map<String, Object>>> list(@RequestParam Map<String, String> sp) {
        auth.requirePage("exits");
        return ApiResult.success(exits.list(sp));
    }

    /** {@code {employeeId, kind: TERMINATION | OTHER | RESIGNATION, reason, lastWorkingDay?, noticeDays?, noticeReason?}} */
    @PostMapping("/api/exits")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> initiate(@RequestBody ExitService.InitiateInput body, HttpServletRequest req) {
        ExitService.Created c = exits.initiate(auth.assertPerm("exit.manage"), body, RequestMeta.of(req).ip());
        return ApiResult.success(c.msg(), Map.of("id", c.id()));
    }

    @GetMapping("/api/exits/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.success(exits.detail(auth.requirePage("exits"), id));
    }

    /** {@code {lastWorkingDay, noticeDays?, reason?}} — agree the last day; a shorter notice needs a reason. */
    @PutMapping("/api/exits/{id}/notice")
    public ApiResult<Void> notice(@PathVariable String id, @RequestBody ExitService.NoticeInput body, HttpServletRequest req) {
        return ApiResult.message(exits.setNotice(auth.assertPerm("exit.manage"), id, body, RequestMeta.of(req).ip()));
    }

    /** {@code {"status": "NOTICE" | "CLEARANCE" | "FNF_PENDING" | "CLOSED" | "CANCELLED", comment?}} */
    @PutMapping("/api/exits/{id}/status")
    public ApiResult<Void> status(@PathVariable String id, @RequestBody ExitService.StatusInput body, HttpServletRequest req) {
        return ApiResult.message(exits.setStatus(auth.assertPerm("exit.manage"), id, body, RequestMeta.of(req).ip()));
    }

    /** {@code {dept: HR | FINANCE | IT | MANAGER, title}} */
    @PostMapping("/api/exits/{id}/clearance")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> addItem(@PathVariable String id, @RequestBody ExitService.ItemInput body, HttpServletRequest req) {
        return ApiResult.message(exits.addItem(auth.assertPerm("exit.manage"), id, body, RequestMeta.of(req).ip()));
    }

    /** {@code {cleared, remarks?}} — HR for any item, Finance for the Finance item. */
    @PutMapping("/api/exits/{id}/clearance/{itemId}")
    public ApiResult<Void> clear(@PathVariable String id, @PathVariable String itemId, @RequestBody ExitService.ClearInput body, HttpServletRequest req) {
        return ApiResult.message(exits.clear(auth.assertPerm("exit.manage", "pay.view"), id, itemId, body, RequestMeta.of(req).ip()));
    }

    // ───────────── full & final settlement (amounts) ─────────────

    /** Who sees amounts: Finance (pay.view / pay.close / struct.manage) and the owner. HR gets a 403. */
    private CurrentUser amounts() {
        CurrentUser u = auth.assertPerm();
        if (!u.can("pay.view", "pay.close", "struct.manage") && !"owner".equals(u.roleKey())) throw new ForbiddenException();
        return u;
    }

    @GetMapping("/api/exits/{id}/settlement")
    public ApiResult<Map<String, Object>> settlement(@PathVariable String id) {
        return ApiResult.success(fnf.view(amounts(), id));
    }

    /** {@code {additions: [{label, amount}], deductions: [{label, amount}], note?}} in paise — saves and recomputes the draft. */
    @PutMapping("/api/exits/{id}/settlement")
    public ApiResult<Void> draft(@PathVariable String id, @RequestBody FnfService.DraftInput body, HttpServletRequest req) {
        return ApiResult.message(fnf.saveDraft(auth.assertPerm("pay.view"), id, body, RequestMeta.of(req).ip()));
    }

    public record ApprovalBody(String status) {}

    /** {@code {"status": "APPROVED"}} (pay.close) — freezes the statement and settles the exit. */
    @PutMapping("/api/exits/{id}/settlement/status")
    public ApiResult<Void> approve(@PathVariable String id, @RequestBody ApprovalBody body, HttpServletRequest req) {
        CurrentUser u = auth.assertPerm("pay.close");
        if (!"APPROVED".equals(body.status())) throw new com.jurislpo.jurisone.exception.UserException(HttpStatus.BAD_REQUEST, "Invalid option: expected \"APPROVED\"");
        return ApiResult.message(fnf.approve(u, id, RequestMeta.of(req).ip()));
    }

    /** The approved statement as a PDF. */
    @GetMapping("/api/exits/{id}/settlement/statement")
    public ResponseEntity<byte[]> statement(@PathVariable String id) {
        amounts();
        FnfService.Statement s = fnf.statement(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + s.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(s.bytes());
    }
}
