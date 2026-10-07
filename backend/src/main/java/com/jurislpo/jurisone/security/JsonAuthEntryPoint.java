package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Signed-out request to a protected endpoint. The seven file/export routes that existed in the Next.js app answer
 * 403 (their contract, checked by the e2e tests); every other endpoint answers 401. The message is the same.
 */
@Component
public class JsonAuthEntryPoint implements AuthenticationEntryPoint {

    /** Paths of the original route handlers (src/app/api/**). */
    public static final List<String> LEGACY_ROUTES = List.of(
            "/api/attendance/register", "/api/declarations/proof", "/api/payroll/export", "/api/payslips/", "/api/people/export", "/api/tax/export");

    private final ObjectMapper json;

    public JsonAuthEntryPoint(ObjectMapper json) {
        this.json = json;
    }

    public static boolean isLegacyRoute(String uri) {
        return LEGACY_ROUTES.stream().anyMatch(uri::startsWith);
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        res.setStatus(isLegacyRoute(req.getRequestURI()) ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_UNAUTHORIZED);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), ApiResult.fail(UnauthenticatedException.MESSAGE));
    }
}
