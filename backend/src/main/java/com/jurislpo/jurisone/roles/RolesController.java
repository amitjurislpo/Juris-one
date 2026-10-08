package com.jurislpo.jurisone.roles;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/roles — Roles & permissions page (roles.manage). */
@RestController
@RequestMapping("/api/roles")
public class RolesController {


    public record CreateRole(String name, String fromRoleId) {}

    private final Auth auth;
    private final RolesService roles;

    public RolesController(Auth auth, RolesService roles) {
        this.auth = auth;
        this.roles = roles;
    }

    @GetMapping
    public ApiResult<List<RolesService.RoleView>> list() {
        auth.requirePage("roles");
        return ApiResult.success(roles.list());
    }

    /** Grant a permission to the role. */
    @PutMapping("/{id}/permissions/{perm}")
    public ApiResult<Void> grant(@PathVariable String id, @PathVariable String perm, HttpServletRequest req) {
        return ApiResult.message(roles.setPermission(auth.assertPerm("roles.manage"), id, perm, true, RequestMeta.of(req).ip()));
    }

    /** Take a permission away from the role. */
    @DeleteMapping("/{id}/permissions/{perm}")
    public ApiResult<Void> revoke(@PathVariable String id, @PathVariable String perm, HttpServletRequest req) {
        return ApiResult.message(roles.setPermission(auth.assertPerm("roles.manage"), id, perm, false, RequestMeta.of(req).ip()));
    }

    /** Let people with this role assign {@code roleId} to others. */
    @PutMapping("/{id}/assignable-roles/{roleId}")
    public ApiResult<Void> allowAssign(@PathVariable String id, @PathVariable String roleId, HttpServletRequest req) {
        return ApiResult.message(roles.setAssignable(auth.assertPerm("roles.manage"), id, roleId, true, RequestMeta.of(req).ip()));
    }

    @DeleteMapping("/{id}/assignable-roles/{roleId}")
    public ApiResult<Void> disallowAssign(@PathVariable String id, @PathVariable String roleId, HttpServletRequest req) {
        return ApiResult.message(roles.setAssignable(auth.assertPerm("roles.manage"), id, roleId, false, RequestMeta.of(req).ip()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody CreateRole body) {
        RolesService.Created c = roles.create(auth.assertPerm("roles.manage"), body.name(), body.fromRoleId());
        return ApiResult.success("Role “" + c.name() + "” created — adjust its permissions", Map.of("id", c.id()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest req) {
        return ApiResult.message(roles.delete(auth.assertPerm("roles.manage"), id, RequestMeta.of(req).ip()));
    }
}
