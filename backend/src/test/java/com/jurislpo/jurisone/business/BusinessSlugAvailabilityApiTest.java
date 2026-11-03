package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.config.BusinessSlugs;
import com.jurislpo.jurisone.service.BusinessSetupService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;
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

/** The super admin's live "is this short name free?" check: reasons, suggestions, access and hostile input. */
class BusinessSlugAvailabilityApiTest extends IntegrationTest {

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
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    ResultActions check(Cookie who, String slug) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/platform/business-slug-availabilities");
        if (slug != null) request = request.param("slug", slug);
        return mockMvc.perform(who == null ? request : request.cookie(who));
    }

    JsonNode answer(String slug) throws Exception {
        ResultActions result = check(admin, slug).andExpect(status().isOk());
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    void createBusiness(String code) throws Exception {
        String content = json.writeValueAsString(java.util.Map.of("business", java.util.Map.of("name", "Avail Co " + code, "code", code, "employeePrefix", "AV")));
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(content)).andExpect(status().isCreated());
    }

    String idOf(String code) {
        return jdbc.queryForObject("SELECT id::text FROM businesses WHERE code = ?", String.class, code);
    }

    void assertSuggestionsAreUsable(JsonNode data) {
        HashSet<String> seen = new HashSet<>();
        assertThat(data.get("suggestions").size()).isLessThanOrEqualTo(BusinessSlugs.MAX_SUGGESTIONS);
        for (JsonNode node : data.get("suggestions")) {
            String suggestion = node.asText();
            assertThat(seen.add(suggestion)).as("unique " + suggestion).isTrue();
            assertThat(BusinessSlugs.problem(suggestion)).isNull();
            assertThat(suggestion.length()).isLessThanOrEqualTo(40);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code = ?", Integer.class, suggestion)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_slug_history WHERE slug = ?", Integer.class, suggestion)).isZero();
        }
    }

    @Test
    void aFreeShortNameIsAvailableAndNormalised() throws Exception {
        JsonNode data = answer("  Free-" + suffix.toUpperCase() + " ");
        assertThat(data.get("slug").asText()).isEqualTo("free-" + suffix);
        assertThat(data.get("available").asBoolean()).isTrue();
        assertThat(data.get("reason").isNull()).isTrue();
        assertThat(data.get("suggestions")).isEmpty();
    }

    @Test
    void aTakenShortNameIsTakenWithOnlyAvailableSuggestions() throws Exception {
        String taken = "taken-" + suffix;
        createBusiness(taken);
        createBusiness(taken + "-2"); // the obvious suggestion is taken too and must not be offered
        JsonNode data = answer(taken);
        assertThat(data.get("available").asBoolean()).isFalse();
        assertThat(data.get("reason").asText()).isEqualTo("TAKEN");
        assertThat(data.get("message").asText()).contains(taken);
        assertThat(data.get("suggestions")).isNotEmpty();
        assertThat(json.convertValue(data.get("suggestions"), java.util.List.class)).doesNotContain(taken, taken + "-2");
        assertSuggestionsAreUsable(data);
    }

    @Test
    void aRetiredShortNameIsRetired() throws Exception {
        String retired = "retired-" + suffix;
        createBusiness(retired);
        String id = idOf(retired);
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + id + "/slug-changes")).cookie(admin).content("{\"slug\":\"current-" + suffix + "\"}")).andExpect(status().isCreated());
        JsonNode data = answer(retired);
        assertThat(data.get("available").asBoolean()).isFalse();
        assertThat(data.get("reason").asText()).isEqualTo("RETIRED");
        assertThat(data.get("suggestions")).isNotEmpty();
        assertSuggestionsAreUsable(data);
    }

    @Test
    void reservedAndInvalidNamesGetAReasonAndUsableSuggestions() throws Exception {
        JsonNode reserved = answer("admin");
        assertThat(reserved.get("reason").asText()).isEqualTo("RESERVED");
        assertThat(reserved.get("available").asBoolean()).isFalse();
        assertSuggestionsAreUsable(reserved);

        JsonNode spaced = answer("Juris Consultants " + suffix);
        assertThat(spaced.get("reason").asText()).isEqualTo("INVALID");
        assertThat(json.convertValue(spaced.get("suggestions"), java.util.List.class)).contains("juris-consultants-" + suffix);
        assertSuggestionsAreUsable(spaced);

        assertThat(answer("ab").get("reason").asText()).isEqualTo("INVALID");
        assertThat(answer("xn--bad").get("available").asBoolean()).isFalse();
        for (String slug : new String[] {"", "   ", null}) {
            JsonNode data = answer(slug);
            assertThat(data.get("reason").asText()).isEqualTo("INVALID");
            assertThat(data.get("suggestions")).isEmpty();
        }
    }

    @Test
    void aSuggestionThatIsTakenIsNeverOffered() throws Exception {
        String base = "pick-" + suffix;
        for (String ending : new String[] {"-2", "-hr", "-co", "-india", "-group"}) createBusiness(base + ending);
        createBusiness(base);
        JsonNode data = answer(base);
        assertThat(data.get("suggestions")).hasSize(1); // only "-3" is left
        assertThat(data.get("suggestions").get(0).asText()).isEqualTo(base + "-3");
        assertSuggestionsAreUsable(data);
    }

    @Test
    void onlyTheSuperAdminMayAsk() throws Exception {
        check(null, "anything-" + suffix).andExpect(status().isUnauthorized());

        String code = "owned-" + suffix;
        UUID business = setup.create(new BusinessSetupService.NewBusiness("Owned " + code, null, code, "OW", "Asia/Kolkata", null, null, null, null), null);
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise) VALUES (?, ?, ?, current_date, current_date + 30, 0)",
                UUID.randomUUID(), business, TestData.testPlan(jdbc));
        String ownerEmail = "owner." + suffix + "@owned.test";
        TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Owner", ownerEmail, null), null));
        check(signIn(ownerEmail), "anything-" + suffix).andExpect(status().isForbidden());

        TestData data = new TestData(jdbc).inBusiness("emp-" + suffix);
        String employeeEmail = TestData.letters(8) + "@employee.test";
        data.employee("EM-" + TestData.letters(4).toUpperCase(), employeeEmail, data.role("employee", "self", "dir"));
        check(signIn(employeeEmail), "anything-" + suffix).andExpect(status().isForbidden());
    }

    @Test
    void hostileInputIsAnsweredSafelyAndNothingChanges() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM businesses", Integer.class);
        String[] bad = {"a'; DROP TABLE businesses; --", "x' OR '1'='1", "<script>alert(1)</script>", "../etc/passwd", "x".repeat(100_000), "日本語の名前", "аcme-" + suffix, "emoji-😀",
                "nul\u0000x", "%00", "a\nb", "$(rm -rf)", "${jndi:ldap://x}"};
        for (String slug : bad) {
            JsonNode data = answer(slug);
            assertThat(data.get("available").asBoolean()).as(slug.length() > 40 ? "long" : slug).isFalse();
            assertThat(data.get("slug").asText().length()).isLessThanOrEqualTo(BusinessSlugs.MAX_INPUT_LENGTH);
            assertThat(data.get("message").asText().length()).isLessThan(300);
            assertSuggestionsAreUsable(data);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses", Integer.class)).isEqualTo(before);
        // the check never writes
        check(admin, "free-" + suffix).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code = ?", Integer.class, "free-" + suffix)).isZero();
        // other methods do not exist
        mockMvc.perform(write(post("/api/v1/platform/business-slug-availabilities")).cookie(admin).content("{}")).andExpect(status().is4xxClientError());
    }

    @Test
    void creatingWithATakenShortNameIsRefusedWithoutASuffix() throws Exception {
        String taken = "nosuffix-" + suffix;
        createBusiness(taken);
        String content = json.writeValueAsString(java.util.Map.of("business", java.util.Map.of("name", "Another", "code", taken, "employeePrefix", "AN")));
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(content)).andExpect(status().isConflict());
        // a blank short name derived from the name collides the same way
        String derived = json.writeValueAsString(java.util.Map.of("business", java.util.Map.of("name", "Derived Twice " + suffix, "employeePrefix", "DT")));
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(derived)).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(derived)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code LIKE ?", Integer.class, "derived-twice-" + suffix + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE code LIKE ?", Integer.class, taken + "%")).isEqualTo(1);
    }
}
