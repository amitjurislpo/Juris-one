package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.security.Auth;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The super admin's platform API (system mode: every business is visible). */
@RestController
public class PlatformController {

    public record BusinessRow(String id, String name, String code, String employeePrefix, String status, long employees, long owners, String createdAt) {}

    private final Auth auth;
    private final JdbcTemplate jdbc;

    public PlatformController(Auth auth, JdbcTemplate jdbc) {
        this.auth = auth;
        this.jdbc = jdbc;
    }

    /** Every business with its head-count and number of owners (sub admins). */
    @GetMapping("/api/platform/businesses")
    public ApiResult<List<BusinessRow>> businesses() {
        auth.assertPerm(Permissions.PLATFORM_MANAGE);
        return ApiResult.success(jdbc.query("""
            SELECT b.id::text, b.name, b.code, b.employee_prefix, b.status, b.created_at,
                   (SELECT count(*) FROM employees e WHERE e.business_id = b.id AND e.status <> 'EXITED') AS employees,
                   (SELECT count(*) FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.business_id = b.id AND r.key = 'owner') AS owners
            FROM businesses b ORDER BY b.name""",
                (rs, i) -> new BusinessRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(7), rs.getLong(8),
                        com.jurislpo.jurisone.engine.Format.jsIso(rs.getTimestamp(6).toInstant()))));
    }
}
