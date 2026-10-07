package com.jurislpo.jurisone.org;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/departments (dept.manage) and /api/holidays (holidays.manage). */
@RestController
public class OrgController {

    public record CreateDepartment(String name, String headId, String description) {}

    public record AddHoliday(String date, String name, String region) {}

    private final Auth auth;
    private final DepartmentsService departments;
    private final HolidaysService holidays;

    public OrgController(Auth auth, DepartmentsService departments, HolidaysService holidays) {
        this.auth = auth;
        this.departments = departments;
        this.holidays = holidays;
    }

    @GetMapping("/api/departments")
    public ApiResult<DepartmentsService.Page> departments(@RequestParam Map<String, String> sp) {
        return ApiResult.success(departments.page(auth.requirePage("departments"), sp));
    }

    @PostMapping("/api/departments")
    public ApiResult<Void> createDepartment(@RequestBody CreateDepartment body, HttpServletRequest req) {
        return ApiResult.message(departments.create(auth.assertPerm("dept.manage"), body.name(), body.headId(), body.description(), RequestMeta.of(req).ip()));
    }

    @DeleteMapping("/api/departments/{id}")
    public ApiResult<Void> deleteDepartment(@PathVariable String id, HttpServletRequest req) {
        return ApiResult.message(departments.delete(auth.assertPerm("dept.manage"), id, RequestMeta.of(req).ip()));
    }

    @GetMapping("/api/holidays")
    public ApiResult<HolidaysService.Page> holidays(@RequestParam Map<String, String> sp) {
        return ApiResult.success(holidays.page(auth.requirePage("holidays"), sp));
    }

    @PostMapping("/api/holidays")
    public ApiResult<Void> addHoliday(@RequestBody AddHoliday body, HttpServletRequest req) {
        return ApiResult.message(holidays.add(auth.assertPerm("holidays.manage"), body.date(), body.name(), body.region(), RequestMeta.of(req).ip()));
    }

    @DeleteMapping("/api/holidays/{id}")
    public ApiResult<Void> deleteHoliday(@PathVariable String id, HttpServletRequest req) {
        return ApiResult.message(holidays.delete(auth.assertPerm("holidays.manage"), id, RequestMeta.of(req).ip()));
    }
}
