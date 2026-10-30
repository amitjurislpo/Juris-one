package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** A business's short name is its web address: valid DNS label, not reserved, derived safely, and fixed after creation. */
class BusinessSlugApiTest extends IntegrationTest {

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

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    String businessBody(String name, String code) throws Exception {
        java.util.Map<String, Object> business = new java.util.LinkedHashMap<>();
        business.put("name", name);
        if (code != null) business.put("code", code);
        business.put("employeePrefix", "SL");
        return json.writeValueAsString(java.util.Map.of("business", business));
    }

    /** An update sends the profile itself, not wrapped in "business". */
    String profileBody(String name, String code) throws Exception {
        return json.writeValueAsString(json.readTree(businessBody(name, code)).get("business"));
    }

    ResultActions create(String name, String code) throws Exception {
        return mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(admin).content(businessBody(name, code)));
    }

    String idOfCreated(String name, String code) throws Exception {
        return body(create(name, code).andExpect(status().isCreated())).at("/data/id").asText();
    }

    String codeOf(String businessId) {
        return jdbc.queryForObject("SELECT code FROM businesses WHERE id = ?::uuid", String.class, businessId);
    }

    long businessCount() {
        return jdbc.queryForObject("SELECT count(*) FROM businesses", Long.class);
    }

    @Test
    void aGoodShortNameIsStoredAndAuditedOnCreate() throws Exception {
        String code = "slug-" + suffix;
        String id = idOfCreated("Slug Co " + suffix, code);
        assertThat(codeOf(id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'business.create' AND entity_id = ? AND after::text LIKE ?", Integer.class, id, "%" + code + "%"))
                .isEqualTo(1);
        // typed in capitals or with spaces around it: normalised
        String second = "up-" + suffix;
        assertThat(codeOf(idOfCreated("Upper Co " + suffix, "  " + second.toUpperCase() + " "))).isEqualTo(second);
        // a double dash is fine except at the 3rd and 4th place
        String insideDash = "ab-x--" + suffix;
        assertThat(codeOf(idOfCreated("Inner Dash " + suffix, insideDash))).isEqualTo(insideDash);
        create("Double Dash " + suffix, "ab--" + suffix).andExpect(status().isBadRequest());
    }

    @Test
    void badShortNamesAreRefusedAndNothingIsCreated() throws Exception {
        long before = businessCount();
        String[] bad = {"admin", "www", "api", "login", "platform", "jurisone", "juristrack", "localhost", "ns1", "ab", "x", "x".repeat(41), "x".repeat(10_000), "-acme", "acme-",
                "xn--acme", "ab--cd", "ac me", "acme.example", "acme_x", "аcme-" + suffix, "café-" + suffix, "acme'; DROP TABLE businesses; --", "acme' OR '1'='1",
                "<script>alert(1)</script>", "../etc/passwd", "acme\u0000x"};
        for (String code : bad) {
            int status = create("Hostile " + suffix, code).andReturn().getResponse().getStatus();
            assertThat(status).as(code.length() > 50 ? "long" : code).isEqualTo(400);
        }
        assertThat(businessCount()).isEqualTo(before);
        // the problem is the usual problem-details shape and says what is wrong
        create("Reserved " + suffix, "admin").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("\"admin\" is reserved. Choose another short name."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses WHERE name LIKE 'Hostile%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses", Integer.class)).isNotNull(); // the table is still there
    }

    @Test
    void aTakenShortNameIsAConflict() throws Exception {
        String code = "taken-" + suffix;
        idOfCreated("First " + suffix, code);
        create("Second " + suffix, code).andExpect(status().isConflict());
        create("Third " + suffix, code.toUpperCase()).andExpect(status().isConflict());
    }

    @Test
    void aDerivedShortNameFallsBackAndNeverCollides() throws Exception {
        String name = "Derived Firm " + suffix;
        assertThat(codeOf(idOfCreated(name, null))).isEqualTo("derived-firm-" + suffix);
        // the same name again: a numbered suffix instead of an error
        assertThat(codeOf(idOfCreated(name, null))).isEqualTo("derived-firm-" + suffix + "-2");
        // a reserved, too short or unusable result gets a safe fallback
        assertThat(codeOf(idOfCreated("Platform", ""))).startsWith("platform-co");
        assertThat(codeOf(idOfCreated("Ab", null))).startsWith("ab-co");
        assertThat(codeOf(idOfCreated("हिन्दी कंपनी", "   "))).startsWith("business");
        assertThat(codeOf(idOfCreated("L".repeat(100), null))).hasSize(40);
    }

    @Test
    void theShortNameCannotBeChangedAfterCreation() throws Exception {
        String code = "fixed-" + suffix;
        String id = idOfCreated("Fixed Co " + suffix, code);
        String url = "/api/v1/platform/businesses/" + id;

        // a different short name (valid and free, reserved, malformed, hostile) is refused and nothing else changes
        for (String attempt : new String[] {"other-" + suffix, "admin", "x", "'; DROP TABLE businesses; --"}) {
            mockMvc.perform(write(put(url)).cookie(admin).content(profileBody("Renamed Co " + suffix, attempt)))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("The short name is the business's web address and can't be changed."));
        }
        assertThat(codeOf(id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?::uuid", String.class, id)).isEqualTo("Fixed Co " + suffix);

        // the same short name (what the edit form sends), in any letter case, or none at all, is fine and edits the rest
        mockMvc.perform(write(put(url)).cookie(admin).content(profileBody("Renamed Co " + suffix, code.toUpperCase()))).andExpect(status().isOk());
        mockMvc.perform(write(put(url)).cookie(admin).content(profileBody("Renamed Again " + suffix, null))).andExpect(status().isOk());
        mockMvc.perform(write(put(url)).cookie(admin).content(profileBody("Renamed Blank " + suffix, ""))).andExpect(status().isOk());
        assertThat(codeOf(id)).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?::uuid", String.class, id)).isEqualTo("Renamed Blank " + suffix);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'business.update' AND entity_id = ?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void aBusinessOwnerCannotEditOrCreateBusinessesAtAll() throws Exception {
        String ownedCode = "owned-" + suffix;
        UUID business = setup.create(new BusinessSetupService.NewBusiness("Owned " + suffix, null, ownedCode, "OW", "Asia/Kolkata", null, null, null, null), null);
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise) VALUES (?, ?, ?, current_date, current_date + 30, 0)",
                UUID.randomUUID(), business, TestData.testPlan(jdbc));
        String ownerEmail = "owner." + suffix + "@owned.test";
        TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Owner", ownerEmail, null), null));
        Cookie owner = signIn(ownerEmail);
        mockMvc.perform(write(put("/api/v1/platform/businesses/" + business)).cookie(owner).content(profileBody("Mine now", "hijack-" + suffix))).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(owner).content(businessBody("Mine too", "mine-" + suffix))).andExpect(status().isForbidden());
        assertThat(codeOf(business.toString())).isEqualTo(ownedCode);
    }

    @Test
    void theDatabaseRefusesABadShortNameEvenWhenTheApplicationIsBypassed() {
        for (String code : new String[] {"ab", "Acme-bad", "-abc", "abc-", "xn--abc", "ab--cd", "a b c", "x".repeat(41), "abc.def"}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO businesses (id, name, code, employee_prefix) VALUES (?, 'Direct', ?, 'DX')", UUID.randomUUID(), code))
                    .as(code).isInstanceOf(DataIntegrityViolationException.class);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO businesses (id, name, code, employee_prefix) VALUES (?, 'Direct', ?, 'DX')", id, "direct-" + suffix);
        assertThatThrownBy(() -> jdbc.update("UPDATE businesses SET code = 'Bad Code' WHERE id = ?", id)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
