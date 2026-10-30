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
import com.jurislpo.jurisone.SignIn;
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
 * The super admin runs businesses and subscriptions. Before a subscription starts (or without one) nobody signs in; once it has ended the
 * business is read-only: it can sign in, view and export, but every write is refused by the API.
 * Juris Track comes with the plan or a separate grant; without it the menu item and the API don't exist.
 */
class SubscriptionApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JobsService jobs;
    Cookie superAdmin;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void superAdmin() throws Exception {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
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
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        superAdmin = signIn(email);
    }

    record Created(UUID business, String ownerEmail) {}

    Created createBusiness(String planId, String startsOn, String endsOn) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String ownerEmail = "owner." + suffix + "@biz.test";
        String body = """
            {"business":{"name":"Nimbus Legal 0%s","contactEmail":"Hello@Nimbus.test","gstin":"27aapfu0939f1zv"},
             "subscription":{"planId":"%s","startsOn":"%s","endsOn":"%s"},
             "owner":{"name":"Neha Owner","email":"%s"}}""".formatted(suffix, planId, startsOn, endsOn, ownerEmail);
        JsonNode created = json.readTree(mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(superAdmin).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Created(UUID.fromString(created.at("/data/id").asText()), ownerEmail);
    }

    String plan(Integer maxEmployees, String... modules) throws Exception {
        String mods = modules.length == 0 ? "[]" : "[\"" + String.join("\",\"", modules) + "\"]";
        String body = "{\"name\":\"Plan " + UUID.randomUUID().toString().substring(0, 6) + "\",\"price\":4999.5,\"periodMonths\":12,\"maxEmployees\":"
                + (maxEmployees == null ? "null" : maxEmployees) + ",\"modules\":" + mods + "}";
        return json.readTree(mockMvc.perform(write(post("/api/v1/platform/plans")).cookie(superAdmin).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Test
    void theSuperAdminCreatesABusinessWithAPlanAndASubAdminWhoRunsIt() throws Exception {
        String plan = plan(null);
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan, today.toString(), today.plusMonths(12).minusDays(1).toString());

        JsonNode detail = json.readTree(mockMvc.perform(get("/api/v1/platform/businesses/" + created.business()).cookie(superAdmin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(detail.at("/business/employeePrefix").asText()).isEqualTo("NL");
        assertThat(detail.at("/business/gstin").asText()).isEqualTo("27AAPFU0939F1ZV");
        assertThat(detail.at("/owners/0/code").asText()).isEqualTo("NL-001");
        assertThat(detail.at("/subscription/state").asText()).isEqualTo("ACTIVE");
        assertThat(detail.at("/subscriptions/0/pricePaise").asLong()).isEqualTo(499_950);

        Cookie owner = signIn(created.ownerEmail());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(jsonPath("$.data.landing").value("/overview")).andExpect(jsonPath("$.data.subscriptionActive").value(true))
                .andExpect(jsonPath("$.data.user.perms[?(@ == 'business.manage')]").exists());
        mockMvc.perform(get("/api/v1/business/subscription").cookie(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscription.active").value(true)).andExpect(jsonPath("$.data.history.length()").value(1));
        mockMvc.perform(get("/api/v1/shell").cookie(owner)).andExpect(jsonPath("$.data.businessName").value(org.hamcrest.Matchers.startsWith("Nimbus Legal")))
                .andExpect(jsonPath("$.data.subscription.state").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/roles").cookie(owner)).andExpect(status().isOk());

        // a second sub admin: no invite email, they sign in with the emailed code
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + created.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"Second Owner\",\"email\":\"second." + created.ownerEmail() + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("NL-002"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, "second." + created.ownerEmail())).isZero();
        signIn("second." + created.ownerEmail());
        // the same email can't be used twice on the platform
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + created.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"Copy\",\"email\":\"" + created.ownerEmail() + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("That email is already in use."));
        // businesses can't use the platform API
        mockMvc.perform(get("/api/v1/platform/businesses").cookie(owner)).andExpect(status().isForbidden());
    }

    @Test
    void theContactPhoneMustBeValidForItsCountryCode() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.toString(), today.plusMonths(1).toString());
        String path = "/api/v1/platform/businesses/" + created.business();
        java.util.function.Function<String, String> body = phone -> "{\"name\":\"Nimbus Legal\",\"employeePrefix\":\"NL\",\"contactPhone\":" + (phone == null ? "null" : "\"" + phone + "\"") + "}";

        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("+91 98765 43210"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT contact_phone FROM businesses WHERE id = ?", String.class, created.business())).isEqualTo("+91 9876543210");
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("+971 501234567"))).andExpect(status().isOk());
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("+91 98765"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("India numbers have 10 digits"));
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("+91 1234567890"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("That isn't a valid India mobile number"));
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("+999 1234567890"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Choose the country code"));
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply("9876543210"))).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(path)).cookie(superAdmin).content(body.apply(null))).andExpect(status().isOk()); // optional
    }

    @Test
    void afterExpiryTheBusinessCanSignInViewAndExportButChangeNothing() throws Exception {
        String plan = plan(null);
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan, today.minusMonths(12).toString(), today.minusDays(1).toString());
        Cookie owner = signIn(created.ownerEmail()); // signing in still works

        // every read works
        mockMvc.perform(get("/api/v1/people").cookie(owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/roles").cookie(owner)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscriptionActive").value(true))
                .andExpect(jsonPath("$.data.readOnly").value(true)).andExpect(jsonPath("$.data.pendingPolicies").value(0));
        mockMvc.perform(get("/api/v1/shell").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscription.state").value("EXPIRED"))
                .andExpect(jsonPath("$.data.subscription.active").value(false));
        mockMvc.perform(get("/api/v1/business/subscription").cookie(owner)).andExpect(status().isOk());

        // every write is refused by the API itself, whatever the UI shows
        mockMvc.perform(write(post("/api/v1/departments")).cookie(owner).content("{\"name\":\"Legal\"}")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("view and export")));
        mockMvc.perform(write(post("/api/v1/people")).cookie(owner).content("{}")).andExpect(status().isPaymentRequired());
        mockMvc.perform(write(put("/api/v1/roles/anything")).cookie(owner).content("{}")).andExpect(status().isPaymentRequired());
        mockMvc.perform(write(delete("/api/v1/departments/" + UUID.randomUUID())).cookie(owner)).andExpect(status().isPaymentRequired());
        // signing out and marking notifications read are still allowed
        mockMvc.perform(write(post("/api/v1/auth/session-refreshes")).cookie(owner)).andExpect(status().is(org.hamcrest.Matchers.not(402)));

        // the owner can still take the employee list out
        byte[] xlsx = mockMvc.perform(get("/api/v1/business/employees/export").cookie(owner)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/vnd.openxmlformats")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(workbook.getSheet("Employees").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Employee ID");
            assertThat(workbook.getSheet("Employees").getLastRowNum()).isZero(); // only the header: the owner (business admin) is never exported
        }

        // renewing unblocks at once
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + created.business() + "/subscriptions")).cookie(superAdmin)
                .content("{\"planId\":\"" + plan + "\",\"startsOn\":\"" + today + "\"}")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(jsonPath("$.data.readOnly").value(false));
        mockMvc.perform(write(post("/api/v1/departments")).cookie(owner).content("{\"name\":\"Legal\"}")).andExpect(status().is(org.hamcrest.Matchers.not(402)));
    }

    @Test
    void beforeTheSubscriptionStartsNobodyOfTheBusinessCanSignIn() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.plusDays(4).toString(), today.plusMonths(12).toString());
        String starts = today.plusDays(4).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
        SignIn.session(mockMvc, created.ownerEmail()).andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"))
                .andExpect(jsonPath("$.detail").value("Your organisation's subscription starts on " + starts + ". You can sign in from then."));
        // asking for a code gives the same answer as for anyone: the state isn't revealed to whoever asks
        mockMvc.perform(write(post("/api/v1/auth/otp-requests")).content("{\"email\":\"" + created.ownerEmail() + "\"}")).andExpect(status().isCreated());

        // the day it starts they get in
        jdbc.update("UPDATE business_subscriptions SET starts_on = ? WHERE business_id = ?", Date.valueOf(today), created.business());
        signIn(created.ownerEmail());
    }

    @Test
    void aBusinessWithoutAnySubscriptionCannotSignIn() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String ownerEmail = "owner." + suffix + "@none.test";
        mockMvc.perform(write(post("/api/v1/platform/businesses")).cookie(superAdmin)
                .content("{\"business\":{\"name\":\"Nimbus Legal 1%s\"},\"owner\":{\"name\":\"Neha Owner\",\"email\":\"%s\"}}".formatted(suffix, ownerEmail)))
                .andExpect(status().isCreated());
        SignIn.session(mockMvc, ownerEmail).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.detail").value("Your organisation has no active subscription. Please contact your administrator."));
    }

    @Test
    void aSessionWhoseSubscriptionWasCancelledIsRefusedEverywhereButMe() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.toString(), today.plusMonths(1).toString());
        Cookie owner = signIn(created.ownerEmail());
        jdbc.update("UPDATE business_subscriptions SET status = 'CANCELLED' WHERE business_id = ?", created.business());
        mockMvc.perform(get("/api/v1/people").cookie(owner)).andExpect(status().isPaymentRequired());
        mockMvc.perform(get("/api/v1/business/subscription").cookie(owner)).andExpect(status().isPaymentRequired());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.subscriptionActive").value(false))
                .andExpect(jsonPath("$.data.readOnly").value(false));
    }

    @Test
    void thePlanCapsTheNumberOfEmployees() throws Exception {
        String plan = plan(1); // the owner is the one
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan, today.toString(), today.plusMonths(1).toString());
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + created.business() + "/owners")).cookie(superAdmin)
                .content("{\"name\":\"One Too Many\",\"email\":\"extra." + created.ownerEmail() + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("allows up to 1 employees")));
    }

    @Test
    void suspendingABusinessSignsEveryoneOutUntilItIsReactivated() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.toString(), today.plusMonths(1).toString());
        Cookie owner = signIn(created.ownerEmail());
        mockMvc.perform(write(put("/api/v1/platform/businesses/" + created.business() + "/status")).cookie(superAdmin).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(status().isUnauthorized());
        // no code is sent to a suspended business; the person is told by email instead
        SignIn.session(mockMvc, created.ownerEmail()).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject = 'Your JurisOne sign-in request'", Integer.class, created.ownerEmail())).isEqualTo(1);
        mockMvc.perform(write(put("/api/v1/platform/businesses/" + created.business() + "/status")).cookie(superAdmin).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        signIn(created.ownerEmail());
    }

    @Test
    void ownersAreWarnedOnceBeforeTheSubscriptionEnds() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.minusMonths(11).toString(), today.plusDays(5).toString());
        Map<String, Object> first = TenantContext.call(created.business(), () -> jobs.jobSubscriptionWarnings());
        assertThat(first).containsEntry("kind", "d7").containsEntry("sent", 1);
        Map<String, Object> again = TenantContext.call(created.business(), () -> jobs.jobSubscriptionWarnings());
        assertThat(again).containsEntry("sent", 0);
        UUID owner = jdbc.queryForObject("SELECT id FROM employees WHERE email = ?", UUID.class, created.ownerEmail());
        assertThat(jdbc.queryForObject("SELECT text FROM notifications WHERE employee_id = ?", String.class, owner)).contains("ends in 5 days");
        assertThat(jdbc.queryForObject("SELECT subject FROM email_outbox WHERE \"to\" = ?", String.class, created.ownerEmail())).isEqualTo("Your JurisOne subscription ends in 5 days");
    }

    @Test
    void plansAreValidated() throws Exception {
        mockMvc.perform(write(post("/api/v1/platform/plans")).cookie(superAdmin).content("{\"name\":\"X\",\"price\":-1,\"periodMonths\":12}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/platform/plans")).cookie(superAdmin).content("{\"name\":\"Bad module\",\"price\":0,\"periodMonths\":12,\"modules\":[\"NOPE\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Unknown module NOPE."));
        String id = plan(null, "JURIS_TRACK");
        mockMvc.perform(get("/api/v1/platform/plans").cookie(superAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')].modules[0]").value("JURIS_TRACK"));
    }

    // ───────────────────────── Juris Track ─────────────────────────

    boolean menuHasJurisTrack(Cookie cookie) throws Exception {
        return mockMvc.perform(get("/api/v1/shell").cookie(cookie)).andReturn().getResponse().getContentAsString().contains("\"juris-track\"");
    }

    @Test
    void jurisTrackExistsOnlyWithThePlanModuleOrAGrant() throws Exception {
        LocalDate today = LocalDate.now();
        Created created = createBusiness(plan(null), today.toString(), today.plusMonths(12).toString());
        Cookie owner = signIn(created.ownerEmail());

        // not on the plan: no menu item, the API doesn't exist
        assertThat(menuHasJurisTrack(owner)).isFalse();
        mockMvc.perform(get("/api/v1/juris-track").cookie(owner)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(jsonPath("$.data.modules.length()").value(0));

        // a paid grant needs a price and an end date
        String base = "/api/v1/platform/businesses/" + created.business() + "/modules";
        mockMvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":999}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A paid grant needs an end date."));
        mockMvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"endsOn\":\"" + today.plusMonths(6) + "\"}")).andExpect(status().isBadRequest());

        // complimentary, open-ended: the menu item and the page appear
        String grant = json.readTree(mockMvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"COMPLIMENTARY\",\"notes\":\"Launch partner\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        assertThat(menuHasJurisTrack(owner)).isTrue();
        mockMvc.perform(get("/api/v1/juris-track").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(jsonPath("$.data.modules[0]").value("JURIS_TRACK"));
        mockMvc.perform(get("/api/v1/business/subscription").cookie(owner)).andExpect(jsonPath("$.data.subscription.modules[0]").value("JURIS_TRACK"));
        // an overlapping second grant is refused
        mockMvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":999,\"endsOn\":\"" + today.plusMonths(6) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get(base).cookie(superAdmin)).andExpect(jsonPath("$.data[0].state").value("ACTIVE")).andExpect(jsonPath("$.data[0].source").value("COMPLIMENTARY"));

        // revoked: gone again, the grant stays on record
        mockMvc.perform(write(delete("/api/v1/platform/modules/" + grant)).cookie(superAdmin)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/juris-track").cookie(owner)).andExpect(status().isNotFound());
        assertThat(menuHasJurisTrack(owner)).isFalse();
        mockMvc.perform(get("/api/v1/platform/businesses/" + created.business()).cookie(superAdmin)).andExpect(jsonPath("$.data.modules[0].state").value("REVOKED"));

        // a paid grant that starts later doesn't count yet
        mockMvc.perform(write(post(base)).cookie(superAdmin).content("{\"source\":\"PAID\",\"price\":1499.5,\"startsOn\":\"" + today.plusDays(10)
                + "\",\"endsOn\":\"" + today.plusMonths(6) + "\"}")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/juris-track").cookie(owner)).andExpect(status().isNotFound());

        // employees can't manage grants
        mockMvc.perform(get(base).cookie(owner)).andExpect(status().isForbidden());
    }

    @Test
    void jurisTrackOnThePlanIsViewOnlyOnceTheSubscriptionHasEnded() throws Exception {
        LocalDate today = LocalDate.now();
        String plan = plan(null, "JURIS_TRACK");
        Created created = createBusiness(plan, today.toString(), today.plusMonths(1).toString());
        Cookie owner = signIn(created.ownerEmail());
        assertThat(menuHasJurisTrack(owner)).isTrue();
        mockMvc.perform(get("/api/v1/juris-track").cookie(owner)).andExpect(status().isOk());

        Created ended = createBusiness(plan, today.minusMonths(2).toString(), today.minusDays(1).toString());
        Cookie endedOwner = signIn(ended.ownerEmail());
        assertThat(menuHasJurisTrack(endedOwner)).isTrue(); // still there to look at
        mockMvc.perform(get("/api/v1/juris-track").cookie(endedOwner)).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/juris-track/anything")).cookie(endedOwner).content("{}")).andExpect(status().isPaymentRequired());

        jdbc.update("UPDATE business_subscriptions SET status = 'CANCELLED' WHERE business_id = ?", ended.business());
        assertThat(menuHasJurisTrack(endedOwner)).isFalse(); // no subscription at all: nothing
        mockMvc.perform(get("/api/v1/juris-track").cookie(endedOwner)).andExpect(status().isPaymentRequired());
    }
}
