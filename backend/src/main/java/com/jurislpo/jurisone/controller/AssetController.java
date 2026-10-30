package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AssetService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Asset tracking: everyone reads their own assets ({@code /mine}); HR / the owner ({@code assets.manage}) keep the register
 * and assign / take back assets.
 */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final Auth auth;
    private final AssetService assets;

    public AssetController(Auth auth, AssetService assets) {
        this.auth = auth;
        this.assets = assets;
    }

    private CurrentUser manager() {
        return auth.assertPerm(Permissions.ASSETS_MANAGE);
    }

    /** What is assigned to the signed-in user, and what they have returned. */
    @GetMapping("/mine")
    public ApiResult<Map<String, Object>> mine() {
        return ApiResult.success(assets.mine(auth.assertPerm()));
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list() {
        manager();
        return ApiResult.success(assets.list());
    }

    /** Active employees an asset can be assigned to. */
    @GetMapping("/assignees")
    public ApiResult<List<Map<String, Object>>> assignees() {
        manager();
        return ApiResult.success(assets.assignees());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Map<String, String>> create(@RequestBody AssetService.AssetInput body, HttpServletRequest request) {
        String id = assets.create(manager(), body, RequestMeta.of(request).ipAddress());
        return ApiResult.success("Asset added", Map.of("id", id));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        manager();
        return ApiResult.success(assets.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResult<Void> update(@PathVariable String id, @RequestBody AssetService.AssetInput body, HttpServletRequest request) {
        assets.update(manager(), id, body, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Asset saved");
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        assets.delete(manager(), id, RequestMeta.of(request).ipAddress());
        return ApiResult.message("Asset deleted");
    }

    /** {@code {employeeId, assignedOn?, notes?}} — hands the asset to an employee. */
    @PostMapping("/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> assign(@PathVariable String id, @RequestBody AssetService.AssignInput body, HttpServletRequest request) {
        return ApiResult.message(assets.assign(manager(), id, body, RequestMeta.of(request).ipAddress()));
    }

    /** {@code {condition, returnedOn?, notes?}} — closes the open assignment. */
    @PatchMapping("/{id}/assignments/current")
    public ApiResult<Void> giveBack(@PathVariable String id, @RequestBody AssetService.ReturnInput body, HttpServletRequest request) {
        return ApiResult.message(assets.giveBack(manager(), id, body, RequestMeta.of(request).ipAddress()));
    }
}
