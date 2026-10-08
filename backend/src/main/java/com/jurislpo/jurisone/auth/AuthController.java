package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.common.ApiResult;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.security.Auth;
import com.jurislpo.jurisone.security.SessionAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    public record LoginRequest(String identifier, String password, Boolean remember, String next) {}

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

    /** Sign in: 201 {data:{redirect}} + Set-Cookie; 400 invalid input, 401 wrong credentials / blocked, 429 throttled. */
    @PostMapping("/session")
    public ResponseEntity<ApiResult<Map<String, String>>> login(@RequestBody LoginRequest body, HttpServletRequest req) {
        AuthService.LoginResult r = auth.login(body.identifier(), body.password(), Boolean.TRUE.equals(body.remember()), body.next(), RequestMeta.of(req));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.SET_COOKIE, r.cookie().toString()).body(ApiResult.success(Map.of("redirect", r.redirect())));
    }

    /** Sign out: always 200; ends the session and clears the cookie. */
    @DeleteMapping("/session")
    public ResponseEntity<ApiResult<Map<String, String>>> logout(HttpServletRequest req) {
        auth.logout(SessionAuthFilter.cookieValue(req), current.current(), RequestMeta.of(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessions.clearCookie().toString())
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
        return info == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResult.fail("This link has expired or was already used."))
                : ResponseEntity.ok(ApiResult.success(info));
    }

    /** Set the password from an invite or reset link: 200 {data:{redirect}}; 400 bad link, 422 password rules, 410 expired/used. */
    @PutMapping("/password")
    public ApiResult<Map<String, String>> setPassword(@RequestBody SetPasswordRequest body, HttpServletRequest req) {
        return ApiResult.success(Map.of("redirect", auth.setPassword(body.token(), body.purpose(), body.password(), body.confirm(), RequestMeta.of(req))));
    }
}
