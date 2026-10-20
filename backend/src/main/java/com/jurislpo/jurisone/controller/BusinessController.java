package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.BusinessService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.engine.Permissions;
import com.jurislpo.jurisone.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The business owner's (sub admin's) endpoints. Both stay available when the subscription has ended. */
@RestController
public class BusinessController {

    static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final Auth auth;
    private final BusinessService business;

    public BusinessController(Auth auth, BusinessService business) {
        this.auth = auth;
        this.business = business;
    }

    @GetMapping("/api/business/subscription")
    public ApiResult<Map<String, Object>> subscription() {
        auth.assertPerm(Permissions.BUSINESS_MANAGE);
        return ApiResult.success(business.subscriptionPage());
    }

    /** All employees as Excel; {@code ?identity=full} includes identity numbers, phone, bank account and address in full (audited). */
    @GetMapping("/api/business/employees/export")
    public ResponseEntity<byte[]> export(@RequestParam(name = "identity", required = false) String identity, HttpServletRequest req) throws IOException {
        BusinessService.Export e = business.employees(auth.assertPerm(Permissions.BUSINESS_MANAGE), "full".equals(identity), RequestMeta.of(req).ip());
        return ResponseEntity.ok().contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(e.body());
    }
}
