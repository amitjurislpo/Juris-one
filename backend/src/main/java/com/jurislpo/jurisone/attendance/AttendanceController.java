package com.jurislpo.jurisone.attendance;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.people.PeopleController;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** My attendance + check-in (self), the register (att.all) and its CSV export. */
@RestController
public class AttendanceController {

    public record PunchRequest(String expect) {}

    private final Auth auth;
    private final MyAttendanceService mine;
    private final RegisterService register;

    public AttendanceController(Auth auth, MyAttendanceService mine, RegisterService register) {
        this.auth = auth;
        this.mine = mine;
        this.register = register;
    }

    /** ?m=YYYY-MM&d=YYYY-MM-DD; {@code tracked:false} when attendance isn't tracked for the user. */
    @GetMapping("/api/attendance/me")
    public ApiResult<MyAttendanceService.Page> me(@RequestParam Map<String, String> sp) {
        return ApiResult.success(mine.page(auth.requirePage("attendance"), sp));
    }

    @PostMapping("/api/attendance/regularizations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> requestRegularization(@RequestBody MyAttendanceService.RegInput body, HttpServletRequest req) {
        return ApiResult.message(mine.requestRegularization(auth.assertPerm("self"), body, RequestMeta.of(req).ip()));
    }

    @DeleteMapping("/api/attendance/regularizations/{id}")
    public ApiResult<Void> cancelRegularization(@PathVariable String id) {
        return ApiResult.message(mine.cancelRegularization(auth.assertPerm("self"), id));
    }

    /** Check in / check out ({@code {"expect": "IN" | "OUT"}} — the button the user saw). */
    @PostMapping("/api/attendance/punches")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> punch(@RequestBody PunchRequest body, HttpServletRequest req) {
        MyAttendanceService.PunchResult r = mine.punch(auth.assertPerm("self"), body.expect(), RequestMeta.of(req).ip());
        return ApiResult.success(r.msg(), Map.of("kind", r.kind()));
    }

    /** Register page data: daily log (?day=&tz=&st=) or monthly grid (?view=month&ym=), paged 50. */
    @GetMapping("/api/register")
    public ApiResult<Map<String, Object>> registerPage(@RequestParam Map<String, String> sp) {
        return ApiResult.success(register.page(auth.requirePage("register"), sp));
    }

    /** CSV (att.all) — same filters as the page; the original route path. */
    @GetMapping("/api/attendance/register")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> sp) {
        RegisterService.Export e = register.export(auth.assertPerm("att.all"), sp);
        return PeopleController.csv(e.filename(), e.body());
    }
}
