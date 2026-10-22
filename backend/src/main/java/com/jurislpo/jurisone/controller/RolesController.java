package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.RolesService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
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

    /** Roles are set up for a business by the platform super admin (from the role templates); its owner can only look at them. */
    private CurrentUser editor() {
        CurrentUser user = auth.assertPerm("roles.manage");
        if (!user.can(Permissions.PLATFORM_MANAGE)) throw new UserException(HttpStatus.FORBIDDEN, "Roles are set up for your business by JurisOne.");
        return user;
    }

    @GetMapping
    public ApiResult<List<RolesService.RoleView>> list() {
        CurrentUser currentUser = auth.requirePage("roles");
        return ApiResult.success(roles.list(currentUser.can(Permissions.PLATFORM_MANAGE)));
    }

    /** Grant a permission to the role. */
    @PutMapping("/{id}/permissions/{perm}")
    public ApiResult<Void> grant(@PathVariable String id, @PathVariable String perm, HttpServletRequest request) {
        return ApiResult.message(roles.setPermission(editor(), id, perm, true, RequestMeta.of(request).ipAddress()));
    }

    /** Take a permission away from the role. */
    @DeleteMapping("/{id}/permissions/{perm}")
    public ApiResult<Void> revoke(@PathVariable String id, @PathVariable String perm, HttpServletRequest request) {
        return ApiResult.message(roles.setPermission(editor(), id, perm, false, RequestMeta.of(request).ipAddress()));
    }

    /** Let people with this role assign {@code roleId} to others. */
    @PutMapping("/{id}/assignable-roles/{roleId}")
    public ApiResult<Void> allowAssign(@PathVariable String id, @PathVariable String roleId, HttpServletRequest request) {
        return ApiResult.message(roles.setAssignable(editor(), id, roleId, true, RequestMeta.of(request).ipAddress()));
    }

    @DeleteMapping("/{id}/assignable-roles/{roleId}")
    public ApiResult<Void> disallowAssign(@PathVariable String id, @PathVariable String roleId, HttpServletRequest request) {
        return ApiResult.message(roles.setAssignable(editor(), id, roleId, false, RequestMeta.of(request).ipAddress()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody CreateRole body) {
        RolesService.Created created = roles.create(editor(), body.name(), body.fromRoleId());
        return ApiResult.success("Role “" + created.name() + "” created — adjust its permissions", Map.of("id", created.id()));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.message(roles.delete(editor(), id, RequestMeta.of(request).ipAddress()));
    }
}
