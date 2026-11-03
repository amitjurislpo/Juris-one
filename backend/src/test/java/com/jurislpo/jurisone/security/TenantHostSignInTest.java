package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.service.MailService;
import com.jurislpo.jurisone.service.MailTemplates;
import com.jurislpo.jurisone.service.RateLimiter;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Per-business addresses, step 5, flag on: the sign-in code, sign-in, the access token and the refresh token only work on the host of the
 * person's own business (platform users: the admin host). Everything else is refused, audited, and looks like an unknown email (a wrong
 * business host) or a neutral 404 (a host that names no business).
 */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost",
        "jurisone.otp.requests-per-ip=6", "jurisone.otp.requests-per-email=3"})
class TenantHostSignInTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimiter limiter;
    @Autowired JwtService jwtService;
    @MockitoSpyBean MailService mail;

    String slugA, slugB, hostA, hostB;
    UUID businessA, businessB, personA, personB;
    String emailA, emailB;

    @BeforeEach
    void setUp() {
        limiter.clear();
        slugA = "ha-" + TestData.letters(8);
        slugB = "hb-" + TestData.letters(8);
        hostA = slugA + ".localhost:3005";
        hostB = slugB + ".localhost:3005";
        TestData a = new TestData(jdbc).inBusiness(slugA), b = new TestData(jdbc).inBusiness(slugB);
        businessA = a.business();
        businessB = b.business();
        emailA = "a." + TestData.letters(8) + "@example.test";
        emailB = "b." + TestData.letters(8) + "@example.test";
        personA = a.employee("A" + TestData.letters(5).toUpperCase(), emailA, a.role("member", "self", "dir"));
        personB = b.employee("B" + TestData.letters(5).toUpperCase(), emailB, b.role("member", "self", "dir"));
    }

    // ── helpers ──

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String host, String body, String... headers) {
        request.header("origin", "http://localhost:3005").header("host", host).contentType(MediaType.APPLICATION_JSON).content(body);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return request;
    }

    MvcResult otp(String host, String email, String... headers) throws Exception {
        return mockMvc.perform(json(post("/api/v1/auth/otp-requests"), host, "{\"email\":\"" + email + "\"}", headers)).andReturn();
    }

    MvcResult login(String host, String email, String code, String... headers) throws Exception {
        return mockMvc.perform(json(post("/api/v1/auth/session"), host, "{\"identifier\":\"" + email + "\",\"otp\":\"" + code + "\"}", headers)).andReturn();
    }

    MvcResult refresh(String host, Cookie refreshCookie, String... headers) throws Exception {
        return mockMvc.perform(json(post("/api/v1/auth/session-refreshes"), host, "{}", headers).cookie(refreshCookie)).andReturn();
    }

    MvcResult me(String host, Cookie accessCookie, String... headers) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/me").header("host", host).cookie(accessCookie);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return mockMvc.perform(request).andReturn();
    }

    record Cookies(Cookie access, Cookie refresh) {}

    static String cookieValue(MvcResult result, String name) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            Matcher matcher = Pattern.compile("^" + name + "=([^;]*)").matcher(header);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    Cookies signIn(String host, String email) throws Exception {
        MvcResult issued = SignIn.session(mockMvc, email, host).andReturn();
        assertThat(issued.getResponse().getStatus()).isEqualTo(201);
        return new Cookies(new Cookie("jo_at", cookieValue(issued, "jo_at")), new Cookie("jo_rt", cookieValue(issued, "jo_rt")));
    }

    static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    int refusals(UUID actor, String step, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND actor_id = ? AND after->>'step' = ? AND after->>'reason' = ?",
                Integer.class, actor, step, reason);
    }

    int anonymousRefusals(String step, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND actor_id IS NULL AND after->>'step' = ? AND after->>'reason' = ?",
                Integer.class, step, reason);
    }

    int codesFor(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email);
    }

    UUID platformAdmin(String email) {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + id.toString().substring(0, 6), email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        return id;
    }

    /** Hosts that name no business: unknown slug, retired slug, bare domain, nested, IP, injection-looking, oversized. */
    List<String> unknownHosts() {
        String retired = "old-" + TestData.letters(8);
        jdbc.update("INSERT INTO business_slug_history (slug, business_id) VALUES (?, ?)", retired, businessA);
        return List.of("nobody-" + TestData.letters(8) + ".localhost:3005", retired + ".localhost:3005", "localhost:3005", "a.b.localhost", "127.0.0.1:3005",
                "evil.com#." + slugA + ".localhost", "x'--.localhost", "x".repeat(300) + ".localhost", slugA + ".evil.com", slugA + ".localhost.evil.com");
    }

    // ── a right host works, and the token carries the business ──

    @Test
    void signInOnTheOwnBusinessHostWorksAndTheTokenNamesTheBusiness() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(cookies.access().getValue().split("\\.")[1]));
        assertThat(payload).contains("\"bid\":\"" + businessA + "\"");
        assertThat(status(me(hostA, cookies.access()))).isEqualTo(200);
        assertThat(status(refresh(hostA, cookies.refresh()))).isEqualTo(201);
    }

    @Test
    void aPlatformUserSignsInOnlyOnTheAdminHost() throws Exception {
        String email = "root." + TestData.letters(8) + "@example.test";
        UUID admin = platformAdmin(email);
        Cookies cookies = signIn("admin.localhost:3005", email);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(cookies.access().getValue().split("\\.")[1]));
        assertThat(payload).contains("\"bid\":\"platform\"");
        assertThat(status(me("admin.localhost:3005", cookies.access()))).isEqualTo(200);
        assertThat(status(me(hostA, cookies.access()))).isEqualTo(401); // the platform token on a business host
        assertThat(refusals(admin, "access_token", "wrong_host")).isEqualTo(1);
        assertThat(status(refresh(hostA, cookies.refresh()))).isEqualTo(401);

        int before = codesFor(email);
        assertThat(status(otp(hostA, email))).isEqualTo(201); // neutral answer, but no code
        assertThat(codesFor(email)).isEqualTo(before);
        assertThat(status(login(hostA, email, SignIn.CODE))).isEqualTo(401);
        assertThat(refusals(admin, "otp_request", "wrong_host")).isEqualTo(1);
        assertThat(refusals(admin, "sign_in", "wrong_host")).isEqualTo(1);
    }

    @Test
    void aBusinessPersonIsRefusedOnTheAdminHost() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        assertThat(status(me("admin.localhost:3005", cookies.access()))).isEqualTo(401);
        int before = codesFor(emailA);
        assertThat(status(otp("admin.localhost:3005", emailA))).isEqualTo(201);
        assertThat(codesFor(emailA)).isEqualTo(before);
        assertThat(status(login("admin.localhost:3005", emailA, SignIn.CODE))).isEqualTo(401);
    }

    // ── the sign-in code ──

    @Test
    void aCodeRequestOnTheWrongBusinessHostLooksLikeAnUnknownEmailAndSendsNothing() throws Exception {
        MvcResult unknownEmail = otp(hostB, "nobody." + TestData.letters(8) + "@example.test");
        int before = codesFor(emailA);
        MvcResult wrongHost = otp(hostB, emailA);
        assertThat(status(wrongHost)).isEqualTo(status(unknownEmail)).isEqualTo(201);
        assertThat(body(wrongHost)).isEqualTo(body(unknownEmail));
        assertThat(codesFor(emailA)).isEqualTo(before);
        verify(mail, never()).send(eq(emailA), any(MailTemplates.Content.class));
        assertThat(refusals(personA, "otp_request", "wrong_host")).isEqualTo(1);

        assertThat(status(otp(hostA, emailA))).isEqualTo(201); // the right host does issue one
        assertThat(codesFor(emailA)).isEqualTo(before + 1);
    }

    @Test
    void aCodeIssuedForOneBusinessIsRefusedOnAnotherAndStaysUsableOnItsOwn() throws Exception {
        assertThat(status(otp(hostA, emailA))).isEqualTo(201);
        MvcResult unknownEmail = login(hostB, "nobody." + TestData.letters(8) + "@example.test", SignIn.CODE);
        MvcResult wrongHost = login(hostB, emailA, SignIn.CODE);
        assertThat(status(wrongHost)).isEqualTo(401).isEqualTo(status(unknownEmail));
        assertThat(body(wrongHost)).isEqualTo(body(unknownEmail));
        assertThat(wrongHost.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(refusals(personA, "sign_in", "wrong_host")).isEqualTo(1);
        assertThat(status(login(hostA, emailA, SignIn.CODE))).isEqualTo(201); // the code was not used up
    }

    @Test
    void wrongHostAttemptsNeverLockTheRealAccount() throws Exception {
        for (int i = 0; i < 7; i++) {
            limiter.clear();
            assertThat(status(login(hostB, emailA, SignIn.CODE))).isEqualTo(401);
        }
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM users WHERE employee_id = ?", Integer.class, personA)).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until IS NULL FROM users WHERE employee_id = ?", Boolean.class, personA)).isTrue();
        limiter.clear();
        assertThat(status(SignIn.session(mockMvc, emailA, hostA).andReturn())).isEqualTo(201);
    }

    // ── hosts that name no business ──

    @Test
    void anUnknownHostGetsTheSameNeutralNotFoundEverywhere() throws Exception {
        List<String> hosts = unknownHosts();
        String firstOtp = null, firstLogin = null, firstRefresh = null;
        int before = anonymousRefusals("otp_request", "unknown_host");
        for (String host : hosts) {
            limiter.clear();
            MvcResult otpResult = otp(host, emailA), loginResult = login(host, emailA, SignIn.CODE);
            MvcResult refreshResult = refresh(host, new Cookie("jo_rt", "anything"));
            for (MvcResult result : List.of(otpResult, loginResult, refreshResult)) {
                assertThat(status(result)).as(host).isEqualTo(404);
                assertThat(body(result)).doesNotContain(slugA).doesNotContain(slugB).doesNotContain(emailA);
                assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
            }
            if (firstOtp == null) { firstOtp = body(otpResult); firstLogin = body(loginResult); firstRefresh = body(refreshResult); }
            assertThat(body(otpResult)).as(host).isEqualTo(firstOtp);
            assertThat(body(loginResult)).as(host).isEqualTo(firstLogin);
            assertThat(body(refreshResult)).as(host).isEqualTo(firstRefresh);
        }
        assertThat(anonymousRefusals("otp_request", "unknown_host")).isEqualTo(before + hosts.size());
        assertThat(codesFor(emailA)).isZero();
        verify(mail, never()).send(eq(emailA), any(MailTemplates.Content.class));
    }

    @Test
    void anXForwardedHostSpoofNeverMakesAnUnknownHostKnown() throws Exception {
        String[] spoof = {"X-Forwarded-Host", hostA, "Forwarded", "host=" + hostA, "X-Original-Host", hostA, "X-Host", hostA};
        assertThat(status(otp("localhost:3005", emailA, spoof))).isEqualTo(404);
        assertThat(status(login("localhost:3005", emailA, SignIn.CODE, spoof))).isEqualTo(404);
        assertThat(codesFor(emailA)).isZero();

        Cookies cookies = signIn(hostA, emailA);
        assertThat(status(me("localhost:3005", cookies.access(), spoof))).isEqualTo(404);
        assertThat(status(refresh("localhost:3005", cookies.refresh(), spoof))).isEqualTo(404);
        assertThat(status(me(hostB, cookies.access(), "X-Forwarded-Host", hostA))).isEqualTo(401); // B's real host wins over the claimed A
        int codes = codesFor(emailA);
        assertThat(status(otp(hostB, emailA, "X-Forwarded-Host", hostA))).isEqualTo(201);
        assertThat(codesFor(emailA)).isEqualTo(codes);
        assertThat(status(me(hostA, cookies.access()))).isEqualTo(200);
    }

    // ── the access token ──

    @Test
    void aStolenAccessTokenIsDeadOnEveryOtherHost() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        assertThat(status(me(hostB, cookies.access()))).isEqualTo(401);
        assertThat(refusals(personA, "access_token", "wrong_host")).isEqualTo(1);
        List<String> hosts = unknownHosts();
        for (String host : hosts) assertThat(status(me(host, cookies.access()))).as(host).isEqualTo(404); // step 6: neutral 404 before the cookie is read
        assertThat(anonymousRefusals("request", "unknown_host")).isGreaterThanOrEqualTo(1);
        assertThat(status(me(hostA, cookies.access()))).isEqualTo(200);
    }

    @Test
    void aTokenWithTheWrongBusinessClaimIsRefusedEvenOnTheMatchingHost() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        String sessionId = jdbc.queryForObject("SELECT id FROM sessions WHERE employee_id = ?", String.class, personA);
        Cookie claimsB = new Cookie("jo_at", jwtService.issue(personA, sessionId, businessB));
        Cookie claimsPlatform = new Cookie("jo_at", jwtService.issue(personA, sessionId, null));
        assertThat(status(me(hostA, claimsB))).isEqualTo(401);
        assertThat(status(me(hostB, claimsB))).isEqualTo(401); // the claim fits B's host but the person is not B's
        assertThat(status(me("admin.localhost:3005", claimsPlatform))).isEqualTo(401);
        assertThat(status(me(hostA, claimsPlatform))).isEqualTo(401);
        assertThat(refusals(personA, "access_token", "token_not_for_host")).isEqualTo(2);
        assertThat(status(me(hostA, cookies.access()))).isEqualTo(200);
    }

    @Test
    void oneBusinessCannotReadAnotherBusinessThroughItsHost() throws Exception {
        UUID role = new TestData(jdbc).inBusiness(slugB).role("viewer", "people.view", "self", "dir");
        String viewerEmail = "v." + TestData.letters(8) + "@example.test";
        new TestData(jdbc).inBusiness(slugB).employee("V" + TestData.letters(5).toUpperCase(), viewerEmail, role);
        Cookies viewerOfB = signIn(hostB, viewerEmail);
        MockHttpServletRequestBuilder onB = get("/api/v1/people").header("host", hostB).cookie(viewerOfB.access());
        assertThat(status(mockMvc.perform(onB).andReturn())).isEqualTo(200);
        MockHttpServletRequestBuilder onA = get("/api/v1/people").header("host", hostA).cookie(viewerOfB.access());
        MvcResult refused = mockMvc.perform(onA).andReturn();
        assertThat(status(refused)).isEqualTo(401);
        assertThat(body(refused)).doesNotContain(emailA).doesNotContain(emailB);
    }

    // ── the refresh token ──

    @Test
    void aRefreshTokenReplayedOnAnotherHostIsRefusedAndLeavesTheOwnersSessionAlone() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        MvcResult replay = refresh(hostB, cookies.refresh());
        assertThat(status(replay)).isEqualTo(401);
        assertThat(replay.getResponse().getHeaders("Set-Cookie")).anyMatch(header -> header.startsWith("jo_at=") && header.contains("Max-Age=0"));
        assertThat(refusals(personA, "refresh", "wrong_host")).isEqualTo(1);
        assertThat(status(refresh("admin.localhost:3005", cookies.refresh()))).isEqualTo(401);
        assertThat(status(refresh("localhost:3005", cookies.refresh()))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM sessions WHERE employee_id = ?", Boolean.class, personA)).isTrue();

        MvcResult fine = refresh(hostA, cookies.refresh()); // the real owner is not affected
        assertThat(status(fine)).isEqualTo(201);
        assertThat(status(me(hostA, new Cookie("jo_at", cookieValue(fine, "jo_at"))))).isEqualTo(200);
    }

    @Test
    void aRefreshedTokenStaysBoundToTheBusiness() throws Exception {
        Cookies cookies = signIn(hostA, emailA);
        MvcResult refreshed = refresh(hostA, cookies.refresh());
        Cookie newAccess = new Cookie("jo_at", cookieValue(refreshed, "jo_at"));
        assertThat(status(me(hostA, newAccess))).isEqualTo(200);
        assertThat(status(me(hostB, newAccess))).isEqualTo(401);
    }

    // ── hostile input ──

    @Test
    void hostileInputIsRefusedWithoutErrorsOnABoundHost() throws Exception {
        String[] emails = {"a'; DROP TABLE employees;--@x.test", "<script>alert(1)</script>@x.test", "x".repeat(10_000) + "@x.test", "' OR '1'='1"};
        for (String email : emails) {
            limiter.clear();
            MvcResult result = otp(hostA, email);
            assertThat(status(result)).as(email.substring(0, Math.min(20, email.length()))).isIn(201, 400);
            assertThat(body(result)).doesNotContain("<script>").doesNotContain("DROP TABLE");
        }
        for (String code : new String[] {"' OR 1=1 --", "<script>", "12345678901234567890", "00000"}) {
            limiter.clear();
            assertThat(status(login(hostA, emailA, code))).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE id = ?", Integer.class, personA)).isEqualTo(1);
    }

    // ── limits stay ──

    @Test
    void theCodeRequestLimitPerAccountAndPerAddressStaysOnWrongAndUnknownHosts() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(status(otp(hostB, emailA))).isEqualTo(201);
        assertThat(status(otp(hostB, emailA))).isEqualTo(429); // per account (3), though the host is wrong
        limiter.clear();
        for (int i = 0; i < 6; i++) assertThat(status(otp("localhost:3005", "u" + i + "." + TestData.letters(6) + "@example.test"))).isEqualTo(404);
        assertThat(status(otp("localhost:3005", "u9." + TestData.letters(6) + "@example.test"))).isEqualTo(429); // per address (6): a scanner pays
    }
}
