package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.ShiftRosterService;
import com.jurislpo.jurisone.service.ShiftRosterService.AssignmentInput;
import com.jurislpo.jurisone.service.ShiftRosterService.Roster;
import com.jurislpo.jurisone.service.ShiftRosterService.ShiftInput;
import com.jurislpo.jurisone.service.ShiftRosterService.ShiftView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shifts and the shift roster. Everyone with attendance reads the shifts and their own roster; the whole business's roster needs
 * {@code att.all} or {@code shifts.manage}; creating and changing shifts and assignments needs {@code shifts.manage}.
 */
@RestController
public class ShiftRosterController {

    private final Auth auth;
    private final ShiftRosterService roster;

    public ShiftRosterController(Auth auth, ShiftRosterService roster) {
        this.auth = auth;
        this.roster = roster;
    }

    @GetMapping("/api/v1/attendance/shifts")
    public ApiResult<List<ShiftView>> shifts() {
        auth.assertPerm("self", "att.all", "shifts.manage");
        return ApiResult.success(roster.shifts());
    }

    @PostMapping("/api/v1/attendance/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<ShiftView> createShift(@RequestBody ShiftInput body, HttpServletRequest request) {
        return ApiResult.success("Shift created", roster.createShift(auth.assertPerm("shifts.manage"), body, RequestMeta.of(request).ipAddress()));
    }

    @PatchMapping("/api/v1/attendance/shifts/{id}")
    public ApiResult<ShiftView> updateShift(@PathVariable String id, @RequestBody ShiftInput body, HttpServletRequest request) {
        return ApiResult.success("Shift saved", roster.updateShift(auth.assertPerm("shifts.manage"), id, body, RequestMeta.of(request).ipAddress()));
    }

    /** ?from=YYYY-MM-DD&to=YYYY-MM-DD (at most 42 days)&scope=mine|all; scope=all needs att.all or shifts.manage. */
    @GetMapping("/api/v1/attendance/shift-roster")
    public ApiResult<Roster> roster(@RequestParam String from, @RequestParam String to, @RequestParam(defaultValue = "mine") String scope) {
        boolean everyone = "all".equals(scope);
        return ApiResult.success(roster.roster(everyone ? auth.assertPerm("att.all", "shifts.manage") : auth.assertPerm("self"), everyone, from, to));
    }

    /** Body: employeeIds[], shiftId, fromDate, toDate (empty = open ended). Answers with the ids of the new assignments. */
    @PostMapping("/api/v1/attendance/shift-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<List<String>> assign(@RequestBody AssignmentInput body, HttpServletRequest request) {
        return ApiResult.success("Shift assigned", roster.assign(auth.assertPerm("shifts.manage"), body, RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/api/v1/attendance/shift-assignments/{id}")
    public ApiResult<Void> removeAssignment(@PathVariable String id, HttpServletRequest request) {
        roster.removeAssignment(auth.assertPerm("shifts.manage"), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Shift assignment removed");
    }
}
