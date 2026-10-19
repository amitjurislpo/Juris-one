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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/auth/* — the sign-in pages' server actions in the Next.js app call these. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String identifier, String password, String otp, Boolean remember, String next) {}

    public record OtpRequest(String email) {}

    public record ForgotRequest(String identifier) {}

    public record SetPasswordRequest(String token, String purpose, String password, String confirm) {}

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
    public ResponseEntity<ApiResult<Map<String, String>>> login(@RequestBody LoginRequest body, HttpServletRequest req) {
        RequestMeta meta = RequestMeta.of(req);
        boolean remember = Boolean.TRUE.equals(body.remember());
        AuthService.LoginResult r = body.otp() != null && !body.otp().isBlank()
                ? auth.loginWithOtp(body.identifier(), body.otp(), remember, body.next(), meta)
                : auth.login(body.identifier(), body.password(), remember, body.next(), meta);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, setCookies(r.cookies())).body(ApiResult.success(Map.of("redirect", r.redirect())));
    }

    /** Email a 6-digit sign-in code (always the same answer, so it doesn't reveal who has an account). */
    @PostMapping("/otp-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> requestOtp(@RequestBody OtpRequest body, HttpServletRequest req) {
        return ApiResult.message(auth.requestOtp(body.email(), RequestMeta.of(req)));
    }

    /**
     * Renew the access token with the refresh cookie (rotates the refresh token). 200 + new cookies, or 401 and the
     * cookies cleared when the session has ended, was revoked or the refresh token was replayed.
     */
    @PostMapping("/session/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest req) {
        try {
            SessionService.Issued issued = sessions.refresh(JwtAuthFilter.cookie(req, SessionService.REFRESH_COOKIE), RequestMeta.of(req));
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, setCookies(issued.cookies())).body(ApiResult.success());
        } catch (SessionService.RefreshRefused e) {
            ProblemDetail p = Problems.of(HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, UnauthenticatedException.MESSAGE, req.getRequestURI());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.SET_COOKIE, setCookies(sessions.clearCookies())).body(p);
        }
    }

    /** Sign out: always 200; ends this session and clears the cookies. */
    @DeleteMapping("/session")
    public ResponseEntity<ApiResult<Map<String, String>>> logout(HttpServletRequest req) {
        auth.logout(current.current(), JwtAuthFilter.cookie(req, SessionService.REFRESH_COOKIE), RequestMeta.of(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, setCookies(sessions.clearCookies()))
                .body(ApiResult.success(Map.of("redirect", "/login?signedout=1")));
    }

    /** Ask for a reset link (always the same answer, so it doesn't reveal who has an account). */
    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<Void> forgot(@RequestBody ForgotRequest body, HttpServletRequest req) {
        return ApiResult.message(auth.forgot(body.identifier(), RequestMeta.of(req)));
    }

    /** 200 {data:{name, code}} for a usable link, else 404. */
    @GetMapping("/tokens/{purpose}/{token}")
    public ResponseEntity<ApiResult<Map<String, String>>> token(@PathVariable String purpose, @PathVariable String token) {
        Map<String, String> info = auth.tokenInfo(token, purpose);
        if (info == null) throw new com.jurislpo.jurisone.exception.ApiException(HttpStatus.NOT_FOUND, "LINK_INVALID", "This link has expired or was already used.");
        return ResponseEntity.ok(ApiResult.success(info));
    }

    /** Set the password from an invite or reset link: 200 {data:{redirect}}; 400 bad link, 422 password rules, 410 expired/used. */
    @PutMapping("/password")
    public ApiResult<Map<String, String>> setPassword(@RequestBody SetPasswordRequest body, HttpServletRequest req) {
        return ApiResult.success(Map.of("redirect", auth.setPassword(body.token(), body.purpose(), body.password(), body.confirm(), RequestMeta.of(req))));
    }
}
