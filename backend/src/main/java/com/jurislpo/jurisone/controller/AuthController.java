package com.jurislpo.jurisone.controller;

import com.jurislpo.jurisone.service.AuthService;
import com.jurislpo.jurisone.service.SessionService;
import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.UnauthenticatedException;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/auth/* — the sign-in pages of the web app call these. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String identifier, String otp, Boolean remember, String next) {}

    public record OtpRequest(String email) {}

    private final AuthService auth;
    private final SessionService sessions;
    private final Auth current;

    public AuthController(AuthService auth, SessionService sessions, Auth current) {
        this.auth = auth;
        this.sessions = sessions;
        this.current = current;
    }

    private static String[] setCookies(List<ResponseCookie> cookies) {
        return cookies.stream().map(ResponseCookie::toString).toArray(String[]::new);
    }

    /**
     * Sign in: 201 {data:{redirect}} + Set-Cookie {@code jo_at} (5-minute JWT) and {@code jo_rt} (refresh token);
     * 400 invalid input, 401 wrong credentials / blocked, 429 throttled.
     */
    @PostMapping("/session")
    public ResponseEntity<ApiResult<Map<String, String>>> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        RequestMeta meta = RequestMeta.of(request);
        com.jurislpo.jurisone.security.AuditTrail.hint(request, "identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(body.identifier()));
        boolean remember = Boolean.TRUE.equals(body.remember());
        AuthService.LoginResult loginResult = auth.loginWithOtp(body.identifier(), body.otp(), remember, body.next(), meta);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, setCookies(loginResult.cookies())).body(ApiResult.success(Map.of("redirect", loginResult.redirect())));
    }

    /** Email a 6-digit sign-in code (always the same answer, so it doesn't reveal who has an account). */
    @PostMapping("/otp-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> requestOtp(@RequestBody OtpRequest body, HttpServletRequest request) {
        com.jurislpo.jurisone.security.AuditTrail.hint(request, "identifier", com.jurislpo.jurisone.security.AuditTrail.maskEmail(body.email()));
        return ApiResult.message(auth.requestOtp(body.email(), RequestMeta.of(request)));
    }

    /**
     * Renew the access token with the refresh cookie (rotates the refresh token). 201 + new cookies, or 401 and the
     * cookies cleared when the session has ended, was revoked or the refresh token was replayed.
     */
    @PostMapping("/session-refreshes")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        try {
            SessionService.Issued issued = sessions.refresh(JwtAuthFilter.cookie(request, SessionService.REFRESH_COOKIE), RequestMeta.of(request));
            return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, setCookies(issued.cookies())).body(ApiResult.success());
        } catch (SessionService.RefreshRefused e) {
            ProblemDetail problem = Problems.of(HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE, request.getRequestURI());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, setCookies(sessions.clearCookies())).body(problem);
        }
    }

    /** Sign out: always 200; ends this session and clears the cookies. */
    @DeleteMapping("/session")
    public ResponseEntity<ApiResult<Map<String, String>>> logout(HttpServletRequest request) {
        auth.logout(current.current(), JwtAuthFilter.cookie(request, SessionService.REFRESH_COOKIE), RequestMeta.of(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, setCookies(sessions.clearCookies()))
                .body(ApiResult.success(Map.of("redirect", "/login?signedout=1")));
    }
}
