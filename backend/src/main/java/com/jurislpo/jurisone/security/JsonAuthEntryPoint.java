package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Problems;
import com.jurislpo.jurisone.common.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Signed-out request to a protected endpoint, as {@code application/problem+json}. The seven file/export routes that
 * existed in the Next.js app answer 403 (their contract, checked by the e2e tests); every other endpoint answers 401,
 * with code {@code TOKEN_EXPIRED} when the access token has merely run out (the client should refresh and retry).
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
        HttpStatus status = isLegacyRoute(req.getRequestURI()) ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED;
        String code = Boolean.TRUE.equals(req.getAttribute(JwtAuthFilter.EXPIRED_ATTR)) ? Problems.TOKEN_EXPIRED : Problems.UNAUTHENTICATED;
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(res.getOutputStream(), Problems.of(status, code, UnauthenticatedException.MESSAGE, req.getRequestURI()));
    }
}
