package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.service.JobsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayInputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The super admin runs businesses and subscriptions; an ended subscription blocks everything but sign-in and the export.
 * Juris Track comes with the plan or a separate grant; without it the menu item and the API don't exist.
 */
class SubscriptionApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";
    static final String OWNER_PW = "owner-pass-2026";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JobsService jobs;
    Cookie superAdmin;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email, String pw) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    @BeforeEach
    void superAdmin() throws Exception {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        String email = "sa." + UUID.randomUUID().toString().substring(0, 8) + "@jurisone.test";
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + id.toString().substring(0, 6), email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, password_hash, is_super_admin) VALUES (?, ?, true)", id, new Passwords().hash(PW));
        superAdmin = signIn(email, PW);
    }

    record Created(UUID business, String ownerEmail) {}

    Created createBusiness(String planId, String startsOn, String endsOn) throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        String ownerEmail = "owner." + s + "@biz.test";
        String body = """
            {"business":{"name":"Nimbus Legal 0%s","contactEmail":"Hello@Nimbus.test","gstin":"27aapfu0939f1zv"},
             "subscription":{"planId":"%s","startsOn":"%s","endsOn":"%s"},
             "owner":{"name":"Neha Owner","email":"%s","password":"%s"}}""".formatted(s, planId, startsOn, endsOn, ownerEmail, OWNER_PW);
        JsonNode r = json.readTree(mvc.perform(write(post("/api/platform/businesses")).cookie(superAdmin).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Created(UUID.fromString(r.at("/data/id").asText()), ownerEmail);
    }

    String plan(Integer maxEmployees, String... modules) throws Exception {
        String mods = modules.length == 0 ? "[]" : "[\"" + String.join("\",\"", modules) + "\"]";
        String body = "{\"name\":\"Plan " + UUID.randomUUID().toString().substring(0, 6) + "\",\"price\":4999.5,\"periodMonths\":12,\"maxEmployees\":"
                + (maxEmployees == null ? "null" : maxEmployees) + ",\"modules\":" + mods + "}";
        return json.readTree(mvc.perform(write(post("/api/platform/plans")).cookie(superAdmin).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Test
    void theSuperAdminCreatesABusinessWithAPlanAndASubAdminWhoRunsIt() throws Exception {
        String plan = plan(null);
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan, today.toString(), today.plusMonths(12).minusDays(1).toString());

        JsonNode detail = json.readTree(mvc.perform(get("/api/platform/businesses/" + c.business()).cookie(superAdmin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(detail.at("/business/employeePrefix").asText()).isEqualTo("NL");
        assertThat(detail.at("/business/gstin").asText()).isEqualTo("27AAPFU0939F1ZV");
        assertThat(detail.at("/owners/0/code").asText()).isEqualTo("NL-001");
        assertThat(detail.at("/subscription/state").asText()).isEqualTo("ACTIVE");
        assertThat(detail.at("/subscriptions/0/pricePaise").asLong()).isEqualTo(499_950);

        Cookie owner = signIn(c.ownerEmail(), OWNER_PW);
        mvc.perform(get("/api/me").cookie(owner)).andExpect(jsonPath("$.data.landing").value("/overview")).andExpect(jsonPath("$.data.subscriptionActive").value(true))
                .andExpect(jsonPath("$.data.user.perms[?(@ == 'business.manage')]").exists());
        mvc.perform(get("/api/business/subscription").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscription.active").value(true)).andExpect(jsonPath("$.data.history.length()").value(1));
        mvc.perform(get("/api/shell").cookie(owner)).andExpect(jsonPath("$.data.businessName").value(org.hamcrest.Matchers.startsWith("Nimbus Legal")))
                .andExpect(jsonPath("$.data.subscription.state").value("ACTIVE"));
        mvc.perform(get("/api/roles").cookie(owner)).andExpect(status().isOk());

        // a second sub admin, by invite
        mvc.perform(write(post("/api/platform/businesses/" + c.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"Second Owner\",\"email\":\"second." + c.ownerEmail() + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("NL-002"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, "second." + c.ownerEmail())).isEqualTo(1);
        // the same email can't be used twice on the platform
        mvc.perform(write(post("/api/platform/businesses/" + c.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"Copy\",\"email\":\"" + c.ownerEmail() + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("That email is already in use."));
        // businesses can't use the platform API
        mvc.perform(get("/api/platform/businesses").cookie(owner)).andExpect(status().isForbidden());
    }

    @Test
    void afterExpiryEverythingButSignInSubscriptionAndExportIsBlocked() throws Exception {
        String plan = plan(null);
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan, today.minusMonths(12).toString(), today.minusDays(1).toString());
        Cookie owner = signIn(c.ownerEmail(), OWNER_PW); // signing in still works

        mvc.perform(get("/api/people").cookie(owner)).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
        mvc.perform(get("/api/roles").cookie(owner)).andExpect(status().isPaymentRequired());
        mvc.perform(get("/api/me").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscriptionActive").value(false));
        mvc.perform(get("/api/shell").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscription.state").value("EXPIRED"))
                .andExpect(jsonPath("$.data.subscription.active").value(false));
        mvc.perform(get("/api/business/subscription").cookie(owner)).andExpect(status().isOk());

        // the owner can still take the employee list out
        byte[] xlsx = mvc.perform(get("/api/business/employees/export").cookie(owner)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/vnd.openxmlformats")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheet("Employees").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee ID");
            assertThat(wb.getSheet("Employees").getLastRowNum()).isZero(); // only the header: the owner (business admin) is never exported
        }

        // renewing unblocks at once
        mvc.perform(write(post("/api/platform/businesses/" + c.business() + "/subscriptions")).cookie(superAdmin)
                .content("{\"planId\":\"" + plan + "\",\"startsOn\":\"" + today + "\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/people").cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void thePlanCapsTheNumberOfEmployees() throws Exception {
        String plan = plan(1); // the owner is the one
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan, today.toString(), today.plusMonths(1).toString());
        mvc.perform(write(post("/api/platform/businesses/" + c.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"One Too Many\",\"email\":\"extra." + c.ownerEmail() + "\",\"password\":\"" + OWNER_PW + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("allows up to 1 employees")));
    }

    @Test
    void suspendingABusinessSignsEveryoneOutUntilItIsReactivated() throws Exception {
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan(null), today.toString(), today.plusMonths(1).toString());
        Cookie owner = signIn(c.ownerEmail(), OWNER_PW);
        mvc.perform(write(put("/api/platform/businesses/" + c.business() + "/status")).cookie(superAdmin).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(owner)).andExpect(status().isUnauthorized());
        mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + c.ownerEmail() + "\",\"password\":\"" + OWNER_PW + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("suspended")));
        mvc.perform(write(put("/api/platform/businesses/" + c.business() + "/status")).cookie(superAdmin).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        signIn(c.ownerEmail(), OWNER_PW);
    }

    @Test
    void ownersAreWarnedOnceBeforeTheSubscriptionEnds() throws Exception {
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan(null), today.minusMonths(11).toString(), today.plusDays(5).toString());
        Map<String, Object> first = TenantContext.call(c.business(), () -> jobs.jobSubscriptionWarnings());
        assertThat(first).containsEntry("kind", "d7").containsEntry("sent", 1);
        Map<String, Object> again = TenantContext.call(c.business(), () -> jobs.jobSubscriptionWarnings());
        assertThat(again).containsEntry("sent", 0);
        UUID owner = jdbc.queryForObject("SELECT id FROM employees WHERE email = ?", UUID.class, c.ownerEmail());
        assertThat(jdbc.queryForObject("SELECT text FROM notifications WHERE employee_id = ?", String.class, owner)).contains("ends in 5 days");
        assertThat(jdbc.queryForObject("SELECT subject FROM email_outbox WHERE \"to\" = ?", String.class, c.ownerEmail())).isEqualTo("Your JurisOne subscription ends in 5 days");
    }

    @Test
    void plansAreValidated() throws Exception {
        mvc.perform(write(post("/api/platform/plans")).cookie(superAdmin).content("{\"name\":\"X\",\"price\":-1,\"periodMonths\":12}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/platform/plans")).cookie(superAdmin).content("{\"name\":\"Bad module\",\"price\":0,\"periodMonths\":12,\"modules\":[\"NOPE\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Unknown module NOPE."));
        String id = plan(null, "JURIS_TRACK");
        mvc.perform(get("/api/platform/plans").cookie(superAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].modules[0]").value("JURIS_TRACK"));
    }

    // ───────────────────────── Juris Track ─────────────────────────

    boolean menuHasJurisTrack(Cookie c) throws Exception {
        return mvc.perform(get("/api/shell").cookie(c)).andReturn().getResponse().getContentAsString().contains("\"juris-track\"");
    }

    @Test
    void jurisTrackExistsOnlyWithThePlanModuleOrAGrant() throws Exception {
        LocalDate today = LocalDate.now();
        Created c = createBusiness(plan(null), today.toString(), today.plusMonths(12).toString());
        Cookie owner = signIn(c.ownerEmail(), OWNER_PW);

        // not on the plan: no menu item, the API doesn't exist
        assertThat(menuHasJurisTrack(owner)).isFalse();
        mvc.perform(get("/api/juris-track").cookie(owner)).andExpect(status().isNotFound());
        mvc.perform(get("/api/me").cookie(owner)).andExpect(jsonPath("$.data.modules.length()").value(0));

        // a paid grant needs a price and an end date
        String base = "/api/platform/businesses/" + c.business() + "/modules";
        mvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":999}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A paid grant needs an end date."));
        mvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"endsOn\":\"" + today.plusMonths(6) + "\"}")).andExpect(status().isBadRequest());

        // complimentary, open-ended: the menu item and the page appear
        String grant = json.readTree(mvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"COMPLIMENTARY\",\"notes\":\"Launch partner\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        assertThat(menuHasJurisTrack(owner)).isTrue();
        mvc.perform(get("/api/juris-track").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMING_SOON"));
        mvc.perform(get("/api/me").cookie(owner)).andExpect(jsonPath("$.data.modules[0]").value("JURIS_TRACK"));
        mvc.perform(get("/api/business/subscription").cookie(owner)).andExpect(jsonPath("$.data.subscription.modules[0]").value("JURIS_TRACK"));
        // an overlapping second grant is refused
        mvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":999,\"endsOn\":\"" + today.plusMonths(6) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get(base).cookie(superAdmin)).andExpect(jsonPath("$.data[0].state").value("ACTIVE")).andExpect(jsonPath("$.data[0].source").value("COMPLIMENTARY"));

        // revoked: gone again, the grant stays on record
        mvc.perform(write(delete("/api/platform/modules/" + grant)).cookie(superAdmin)).andExpect(status().isOk());
        mvc.perform(get("/api/juris-track").cookie(owner)).andExpect(status().isNotFound());
        assertThat(menuHasJurisTrack(owner)).isFalse();
        mvc.perform(get("/api/platform/businesses/" + c.business()).cookie(superAdmin)).andExpect(jsonPath("$.data.modules[0].state").value("REVOKED"));

        // a paid grant that starts later doesn't count yet
        mvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":1499.5,\"startsOn\":\"" + today.plusDays(10)
                + "\",\"endsOn\":\"" + today.plusMonths(6) + "\"}")).andExpect(status().isCreated());
        mvc.perform(get("/api/juris-track").cookie(owner)).andExpect(status().isNotFound());

        // employees can't manage grants
        mvc.perform(get(base).cookie(owner)).andExpect(status().isForbidden());
    }

    @Test
    void jurisTrackOnThePlanNeedsAnActiveSubscription() throws Exception {
        LocalDate today = LocalDate.now();
        String plan = plan(null, "JURIS_TRACK");
        Created c = createBusiness(plan, today.toString(), today.plusMonths(1).toString());
        Cookie owner = signIn(c.ownerEmail(), OWNER_PW);
        assertThat(menuHasJurisTrack(owner)).isTrue();
        mvc.perform(get("/api/juris-track").cookie(owner)).andExpect(status().isOk());

        Created ended = createBusiness(plan, today.minusMonths(2).toString(), today.minusDays(1).toString());
        Cookie endedOwner = signIn(ended.ownerEmail(), OWNER_PW);
        assertThat(menuHasJurisTrack(endedOwner)).isFalse();
        mvc.perform(get("/api/juris-track").cookie(endedOwner)).andExpect(status().isPaymentRequired());
    }
}
