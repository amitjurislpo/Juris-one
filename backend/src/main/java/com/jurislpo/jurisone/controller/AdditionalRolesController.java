package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.service.AdditionalRolesService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The additional roles of a person: {@code /api/v1/people/{employeeId}/additional-roles}. */
@RestController
public class AdditionalRolesController {

    public record AdditionalRolesInput(List<String> roleIds) {}

    private final Auth auth;
    private final AdditionalRolesService additionalRoles;

    public AdditionalRolesController(Auth auth, AdditionalRolesService additionalRoles) {
        this.auth = auth;
        this.additionalRoles = additionalRoles;
    }

    @GetMapping("/api/v1/people/{employeeId}/additional-roles")
    public ApiResult<AdditionalRolesService.AdditionalRoles> get(@PathVariable String employeeId) {
        return ApiResult.success(additionalRoles.get(auth.assertPerm("people.view"), employeeId));
    }

    @PutMapping("/api/v1/people/{employeeId}/additional-roles")
    public ApiResult<AdditionalRolesService.AdditionalRoles> replace(@PathVariable String employeeId, @RequestBody(required = false) AdditionalRolesInput body, HttpServletRequest request) {
        return ApiResult.success("Roles saved", additionalRoles.replace(auth.assertPerm("people.add"), employeeId, body == null ? null : body.roleIds(), RequestMeta.of(request).ipAddress()));
    }
}
