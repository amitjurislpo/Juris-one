package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.service.BusinessSetupService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Only the super admin changes a business's web address; the old one is retired for good and the business is signed out. */
class BusinessSlugChangeApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BusinessSetupService setup;
    Cookie admin;
    String suffix;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void superAdmin() throws Exception {
        new TestData(jdbc);
        suffix = TestData.letters(6);
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        String email = "platform." + suffix + "@jurisone.test";
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + suffix, email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        admin = signIn(email);
    }

    Cookie signIn(String email) throws Exception {
        return cookie(SignIn.session(mockMvc, email).andExpect(status().isCreated()), "jo_at");
    }

    static Cookie cookie(ResultActions result, String name) {
        for (String header : result.andReturn().getResponse().getHeaders("Set-Cookie")) {
            Matcher matcher = Pattern.compile("^" + name + "=([^;]+)").matcher(header);
            if (matcher.find()) return new Cookie(name, matcher.group(1));
        }
        throw new AssertionError("no " + name + " cookie");
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    ResultActions createBusiness(String code) throws Exception {
        String content = json.writeValueAsString(java.util.Map.of("business", java.util.Map.of("name", "Rename Co " + code, "code", code, "employeePrefix", "RN")));
        return mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(content));
    }

    String newBusiness(String code) throws Exception {
        return body(createBusiness(code).andExpect(status().isCreated())).at("/data/id").asText();
    }

    ResultActions rename(Cookie who, String businessId, String slug) throws Exception {
        return mockMvc.perform(write(post("/api/v1/platform/businesses/" + businessId + "/slug-changes")).cookie(who)
                .content(json.writeValueAsString(java.util.Collections.singletonMap("slug", slug))));
    }

    ResultActions history(Cookie who, String businessId) throws Exception {
        return mockMvc.perform(get("/api/v1/platform/businesses/" + businessId + "/slug-changes").cookie(who));
    }

    String codeOf(String businessId) {
        return jdbc.queryForObject("SELECT code FROM businesses WHERE id = ?::uuid", String.class, businessId);
    }

    /** A business with an owner who has a subscription; returns {businessId, ownerEmail}. */
    String[] businessWithOwner() throws Exception {
        String code = "owned-" + TestData.letters(6);
        UUID business = setup.create(new BusinessSetupService.NewBusiness("Owned " + code, null, code, "OW", "Asia/Kolkata", null, null, null, null), null);
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise) VALUES (?, ?, ?, current_date, current_date + 30, 0)",
                UUID.randomUUID(), business, TestData.testPlan(jdbc));
        String ownerEmail = "owner." + TestData.letters(6) + "@owned.test";
        TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Owner", ownerEmail, null), null));
        return new String[] {business.toString(), ownerEmail};
    }

    @Test
    void theSuperAdminChangesTheAddressAndTheOldOneIsRetired() throws Exception {
        String old = "before-" + suffix, fresh = "after-" + suffix;
        String id = newBusiness(old);
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + id + "/slug-changes")).cookie(admin).content("{\"slug\":\"  " + fresh.toUpperCase() + " \"}"))
                .andExpect(status().isCreated());

        assertThat(codeOf(id)).isEqualTo(fresh); // trimmed and lowercased
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE slug = ? AND business_id = ?::uuid AND released_by IS NOT NULL", Integer.class, old, id)).isEqualTo(1);
        history(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].slug").value(old)).andExpect(jsonPath("$.data[0].releasedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/platform/businesses/" + id).cookie(admin)).andExpect(jsonPath("$.data.business.code").value(fresh));

        // audited: old and new in the details, nothing else
        String after = jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'business.slug.change' AND entity_id = ?", String.class, id);
        assertThat(after).contains(old).contains(fresh);
        assertThat(jdbc.queryForObject("SELECT before::text FROM audit_log WHERE action = 'business.slug.change' AND entity_id = ?", String.class, id)).contains(old);

        // newest first, and a second change keeps the first
        String third = "third-" + suffix;
        rename(admin, id, third).andExpect(status().isCreated());
        history(admin, id).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].slug").value(fresh)).andExpect(jsonPath("$.data[1].slug").value(old));
    }

    @Test
    void everybodyOfTheBusinessIsSignedOutAtOnce() throws Exception {
        String[] business = businessWithOwner();
        String ownerEmail = business[1];
        ResultActions signedIn = SignIn.session(mockMvc, ownerEmail).andExpect(status().isCreated());
        Cookie accessToken = cookie(signedIn, "jo_at"), refreshToken = cookie(signedIn, "jo_rt");
        mockMvc.perform(get("/api/v1/me").cookie(accessToken)).andExpect(status().isOk());
        String[] bystander = businessWithOwner();
        Cookie bystanderAccess = cookie(SignIn.session(mockMvc, bystander[1]).andExpect(status().isCreated()), "jo_at");

        rename(admin, business[0], "renamed-" + suffix).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/me").cookie(accessToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/v1/auth/session-refreshes")).cookie(refreshToken)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE revoked_at IS NULL AND employee_id IN (SELECT id FROM employees WHERE business_id = ?::uuid)", Integer.class, business[0]))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sessions WHERE revoked_reason = 'web_address_changed'", Integer.class)).isPositive();
        // another business is not affected, and the user can sign in again (the address is not part of the sign-in yet)
        mockMvc.perform(get("/api/v1/me").cookie(bystanderAccess)).andExpect(status().isOk());
        signIn(ownerEmail);
    }

    @Test
    void aBusinessOwnerAndAnEmployeeAreRefused() throws Exception {
        String[] business = businessWithOwner();
        String code = codeOf(business[0]);
        Cookie owner = signIn(business[1]);
        rename(owner, business[0], "hijack-" + suffix).andExpect(status().isForbidden());
        history(owner, business[0]).andExpect(status().isForbidden());

        TestData data = new TestData(jdbc).inBusiness("emp-" + suffix);
        String employeeEmail = TestData.letters(8) + "@employee.test";
        data.employee("EM-" + TestData.letters(4).toUpperCase(), employeeEmail, data.role("employee", "self", "dir"));
        Cookie employee = signIn(employeeEmail);
        rename(employee, business[0], "hijack2-" + suffix).andExpect(status().isForbidden());
        history(employee, business[0]).andExpect(status().isForbidden());

        // not signed in at all
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + business[0] + "/slug-changes")).content("{\"slug\":\"nobody-" + suffix + "\"}")).andExpect(status().isUnauthorized());

        assertThat(codeOf(business[0])).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, business[0])).isZero();
    }

    @Test
    void anUnknownOrPlatformBusinessIs404() throws Exception {
        rename(admin, UUID.randomUUID().toString(), "ghost-" + suffix).andExpect(status().isNotFound());
        history(admin, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        rename(admin, "not-a-uuid", "ghost-" + suffix).andExpect(status().isBadRequest());
        UUID platform = jdbc.queryForObject("SELECT id FROM businesses WHERE is_platform LIMIT 1", UUID.class);
        if (platform != null) rename(admin, platform.toString(), "platform-" + suffix).andExpect(status().isNotFound());
    }

    @Test
    void aBusinessCannotReadAnotherBusinessesHistory() throws Exception {
        String[] mine = businessWithOwner();
        String old = "history-" + suffix;
        String other = newBusiness(old);
        rename(admin, other, "moved-" + suffix).andExpect(status().isCreated());
        Cookie owner = signIn(mine[1]);
        history(owner, other).andExpect(status().isForbidden());
        // the table itself shows nothing to a business, even when the application is bypassed
        Integer visible = TenantContext.call(UUID.fromString(mine[0]), () -> jdbc.queryForObject("SELECT count(*) FROM business_slug_history", Integer.class));
        assertThat(visible).isZero();
        Integer visibleToTheOtherItself = TenantContext.call(UUID.fromString(other), () -> jdbc.queryForObject("SELECT count(*) FROM business_slug_history", Integer.class));
        assertThat(visibleToTheOtherItself).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, other)).isEqualTo(1);
    }

    @Test
    void aRetiredAddressCanNeverBeTakenAgain() throws Exception {
        String retired = "retired-" + suffix;
        String id = newBusiness(retired);
        rename(admin, id, "current-" + suffix).andExpect(status().isCreated());

        // not by another business (create or rename), not by the same business going back, not through the database directly
        createBusiness(retired).andExpect(status().isConflict());
        createBusiness(retired.toUpperCase()).andExpect(status().isConflict());
        String other = newBusiness("another-" + suffix);
        rename(admin, other, retired).andExpect(status().isConflict());
        rename(admin, id, retired).andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE businesses SET code = ? WHERE id = ?::uuid", retired, other))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("INSERT INTO businesses (id, name, code, employee_prefix) VALUES (?, 'Direct', ?, 'DX')", UUID.randomUUID(), retired))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(codeOf(other)).isEqualTo("another-" + suffix);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code = ?", Integer.class, retired)).isZero();

        // an address another business uses now is a conflict too
        rename(admin, other, "current-" + suffix).andExpect(status().isConflict());
    }

    @Test
    void theSameAddressAsNowIs422() throws Exception {
        String code = "same-" + suffix;
        String id = newBusiness(code);
        rename(admin, id, code).andExpect(status().isUnprocessableEntity());
        rename(admin, id, " " + code.toUpperCase()).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'business.slug.change' AND entity_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void hostileInputIsRefusedAndNothingChanges() throws Exception {
        String code = "safe-" + suffix;
        String id = newBusiness(code);
        String[] bad = {"admin", "www", "platform", "juristrack", "localhost", "ab", "x".repeat(41), "x".repeat(10_000), "-bad", "bad-", "xn--bad", "ab--cd", "has space", "dot.ted",
                "under_score", "аcme-" + suffix, "café-" + suffix, "日本語の名前", "emoji-😀", "a'; DROP TABLE businesses; --", "x' OR '1'='1", "<script>alert(1)</script>", "../etc/passwd",
                "nul\u0000x", "", "   "};
        for (String slug : bad) {
            assertThat(rename(admin, id, slug).andReturn().getResponse().getStatus()).as(slug.length() > 50 ? "long" : slug).isEqualTo(400);
        }
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + id + "/slug-changes")).cookie(admin).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + id + "/slug-changes")).cookie(admin).content("{\"slug\":[\"x\"]}")).andExpect(status().is4xxClientError());
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + id + "/slug-changes")).cookie(admin).content("not json")).andExpect(status().isBadRequest());
        // a hostile id in the path
        rename(admin, "x'; DROP TABLE businesses; --", "fine-" + suffix).andExpect(status().isBadRequest());
        rename(admin, "1 OR 1=1", "fine-" + suffix).andExpect(status().isBadRequest());
        rename(admin, id, "admin").andExpect(jsonPath("$.detail").value("\"admin\" is reserved. Choose another short name."));

        assertThat(codeOf(id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses", Integer.class)).isPositive();
    }

    @Test
    void twoRenamesToTheSameAddressAtOnceHaveOneWinner() throws Exception {
        String target = "target-" + suffix;
        String first = newBusiness("first-" + suffix), second = newBusiness("second-" + suffix);
        List<Integer> statuses = inParallel(() -> rename(admin, first, target).andReturn().getResponse().getStatus(), () -> rename(admin, second, target).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code = ?", Integer.class, target)).isEqualTo(1);
        // the loser changed nothing: no history row for it, its address is unchanged
        String loser = codeOf(first).equals(target) ? second : first;
        assertThat(codeOf(loser)).isEqualTo(loser.equals(first) ? "first-" + suffix : "second-" + suffix);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, loser)).isZero();

        // the same business asked twice: one change, the repeat is "already the address"
        String single = newBusiness("single-" + suffix);
        String wanted = "wanted-" + suffix;
        List<Integer> repeated = inParallel(() -> rename(admin, single, wanted).andReturn().getResponse().getStatus(), () -> rename(admin, single, wanted).andReturn().getResponse().getStatus());
        assertThat(repeated).containsExactlyInAnyOrder(201, 422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE business_id = ?::uuid", Integer.class, single)).isEqualTo(1);
    }

    @SafeVarargs
    private static List<Integer> inParallel(Callable<Integer>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (Callable<Integer> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Integer> out = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) out.add(future.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
