package com.jurislpo.jurisone.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.service.JobsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The leave year, the year-end choice per leave type, the apply check against the leave year, and the year-end job. Each test has its own business. */
class LeavePolicyApiTest extends IntegrationTest {

    static final Instant JANUARY_2027 = LocalDate.parse("2027-01-02").atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(7 * 3600);
    static final Instant APRIL_2027 = LocalDate.parse("2027-04-02").atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(7 * 3600);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobsService jobs;

    TestData data;
    UUID adminRole, hrRole, plainRole;
    Person admin, hr, plain;

    record Person(UUID id, Cookie cookie) {}

    @BeforeEach
    void setUp() throws Exception {
        data = new TestData(jdbc).inBusiness("leave-year-" + TestData.letters(8));
        adminRole = data.role("admin", "self", "dir", "roles.manage");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage");
        plainRole = data.role("employee", "self", "dir");
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, max_stretch, paid, needs_balance, sort_order, active, expiry_days)
            VALUES ('CL', 'Casual leave', 12, 1, 0, 3, true, true, 1, true, NULL), ('EL', 'Earned leave', 18, 1.5, 30, NULL, true, true, 2, true, NULL),
                   ('CO', 'Comp-off', 0, 0, 0, NULL, true, true, 3, true, 60), ('LOP', 'Leave without pay', 0, 0, 0, NULL, false, false, 4, true, NULL)
            ON CONFLICT (code) DO NOTHING""");
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_DAY'", Integer.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
        admin = person(adminRole);
        hr = person(hrRole);
        plain = person(plainRole);
    }

    Person person(UUID role) throws Exception {
        String code = "LY" + TestData.letters(8);
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = 'IN_DAY') WHERE id = ?", id);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Person person, String body) {
        MockHttpServletRequestBuilder built = request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON).content(body);
        return person == null ? built : built.cookie(person.cookie());
    }

    static final String POLICY = "/api/v1/settings/leave-policy";

    int auditCount(String action, String entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ? AND business_id = ?", Integer.class, action, entityId, data.business());
    }

    @Test
    void theDefaultIsTheCalendarYearAndTheTypesKeepTheirOwnYearEnd() throws Exception {
        for (Person reader : List.of(admin, hr)) {
            mockMvc.perform(get(POLICY).cookie(reader.cookie())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.year.startMonth").value(1)).andExpect(jsonPath("$.data.year.startDay").value(1))
                    .andExpect(jsonPath("$.data.year.currentFrom").value(org.hamcrest.Matchers.endsWith("-01-01")))
                    .andExpect(jsonPath("$.data.year.currentTo").value(org.hamcrest.Matchers.endsWith("-12-31")))
                    .andExpect(jsonPath("$.data.types[?(@.code=='CL')].yearEndAction").value("COLLAPSE"))
                    .andExpect(jsonPath("$.data.types[?(@.code=='EL')].yearEndAction").value("CARRY_CAPPED"))
                    .andExpect(jsonPath("$.data.types[?(@.code=='EL')].carryForwardCap").value(30))
                    .andExpect(jsonPath("$.data.types[?(@.code=='CO')]").isEmpty()).andExpect(jsonPath("$.data.types[?(@.code=='LOP')]").isEmpty());
        }
        mockMvc.perform(get(POLICY)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(POLICY).cookie(plain.cookie())).andExpect(status().isForbidden());
    }

    @Test
    void adminAndHrChooseTheYearEndPerLeaveTypeAndItIsAudited() throws Exception {
        mockMvc.perform(write(patch(POLICY + "/types/CL"), hr, "{\"yearEndAction\":\"CARRY_ALL\"}")).andExpect(status().isOk());
        mockMvc.perform(write(patch(POLICY + "/types/EL"), admin, "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":12.5}")).andExpect(status().isOk());
        mockMvc.perform(get(POLICY).cookie(hr.cookie()))
                .andExpect(jsonPath("$.data.types[?(@.code=='CL')].yearEndAction").value("CARRY_ALL"))
                .andExpect(jsonPath("$.data.types[?(@.code=='CL')].carryForwardCap").value(0))
                .andExpect(jsonPath("$.data.types[?(@.code=='EL')].carryForwardCap").value(12.5));
        // the Settings page shows the same choice
        mockMvc.perform(get("/api/v1/settings/leave").cookie(admin.cookie())).andExpect(jsonPath("$.data.types[?(@.code=='CL')].yearEndAction").value("CARRY_ALL"));
        mockMvc.perform(write(patch(POLICY + "/types/EL"), hr, "{\"yearEndAction\":\"COLLAPSE\"}")).andExpect(status().isOk());
        mockMvc.perform(get(POLICY).cookie(admin.cookie())).andExpect(jsonPath("$.data.types[?(@.code=='EL')].yearEndAction").value("COLLAPSE"))
                .andExpect(jsonPath("$.data.types[?(@.code=='EL')].carryForwardCap").value(0));
        assertThat(auditCount("leave.year_end_change", "CL")).isEqualTo(1);
        assertThat(auditCount("leave.year_end_change", "EL")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT \"after\"::text FROM audit_log WHERE action = 'leave.year_end_change' AND entity_id = 'CL' AND business_id = ?", String.class, data.business()))
                .contains("CARRY_ALL");
        // the choice belongs to this business only: the shared leave type is untouched
        assertThat(jdbc.queryForObject("SELECT carry_forward_cap FROM leave_types WHERE code = 'EL'", BigDecimal.class)).isEqualByComparingTo("30");
    }

    @Test
    void theYearEndChoiceRefusesWrongInputAndWrongPeople() throws Exception {
        for (String body : List.of("{}", "{\"yearEndAction\":\"DELETE_EVERYTHING\"}", "{\"yearEndAction\":\"CARRY_CAPPED\"}", "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":0}",
                "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":-3}", "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":366}", "{\"yearEndAction\":\"COLLAPSE\",\"carryForwardCap\":5}",
                "{\"yearEndAction\":\"CARRY_ALL\",\"carryForwardCap\":\"x\"}", "{\"yearEndAction\":\"'; DROP TABLE leave_types; --\"}")) {
            mockMvc.perform(write(patch(POLICY + "/types/EL"), hr, body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").isNotEmpty());
        }
        // comp-off, unpaid leave and unknown codes have no year-end choice
        for (String code : List.of("CO", "LOP", "ZZ", "cl")) {
            mockMvc.perform(write(patch(POLICY + "/types/" + code), hr, "{\"yearEndAction\":\"COLLAPSE\"}")).andExpect(status().isNotFound());
        }
        mockMvc.perform(write(patch(POLICY + "/types/CL"), plain, "{\"yearEndAction\":\"COLLAPSE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(patch(POLICY + "/types/CL"), null, "{\"yearEndAction\":\"COLLAPSE\"}")).andExpect(status().isUnauthorized());
        assertThat(auditCount("leave.year_end_change", "CL")).isZero();
    }

    @Test
    void adminAndHrSetTheLeaveYearStart() throws Exception {
        mockMvc.perform(write(put(POLICY + "/year"), hr, "{\"startMonth\":4,\"startDay\":1}")).andExpect(status().isOk());
        mockMvc.perform(get(POLICY).cookie(admin.cookie())).andExpect(jsonPath("$.data.year.startMonth").value(4)).andExpect(jsonPath("$.data.year.startDay").value(1))
                .andExpect(jsonPath("$.data.year.currentFrom").value(org.hamcrest.Matchers.endsWith("-04-01"))).andExpect(jsonPath("$.data.year.currentTo").value(org.hamcrest.Matchers.endsWith("-03-31")));
        mockMvc.perform(write(put(POLICY + "/year"), admin, "{\"startMonth\":4,\"startDay\":1}")).andExpect(status().isOk()); // same again: nothing changes, nothing audited twice
        assertThat(auditCount("leave.year_change", "leave.policy")).isEqualTo(1);
        mockMvc.perform(write(put(POLICY + "/year"), admin, "{\"startMonth\":1,\"startDay\":1}")).andExpect(status().isOk());
        assertThat(auditCount("leave.year_change", "leave.policy")).isEqualTo(2);
        for (String body : List.of("{}", "{\"startMonth\":13,\"startDay\":1}", "{\"startMonth\":0,\"startDay\":1}", "{\"startMonth\":2,\"startDay\":29}", "{\"startMonth\":4,\"startDay\":31}",
                "{\"startMonth\":1,\"startDay\":0}", "{\"startMonth\":\"April\",\"startDay\":1}", "{\"startMonth\":99999999999,\"startDay\":1}")) {
            mockMvc.perform(write(put(POLICY + "/year"), hr, body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").isNotEmpty());
        }
        mockMvc.perform(write(put(POLICY + "/year"), plain, "{\"startMonth\":4,\"startDay\":1}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put(POLICY + "/year"), null, "{\"startMonth\":4,\"startDay\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void leaveForTheNextLeaveYearOrAcrossAYearEndIsRefused() throws Exception {
        LocalDate today = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
        // the leave year ends today: tomorrow already belongs to the next one (never 29 February: that cannot be a start day)
        LocalDate nextYearStart = today.plusDays(1);
        if (nextYearStart.getMonthValue() == 2 && nextYearStart.getDayOfMonth() == 29) nextYearStart = nextYearStart.plusDays(1);
        mockMvc.perform(write(put(POLICY + "/year"), hr, "{\"startMonth\":" + nextYearStart.getMonthValue() + ",\"startDay\":" + nextYearStart.getDayOfMonth() + "}")).andExpect(status().isOk());
        String detailStart = "Leave can only be applied within the current leave year (";
        for (LocalDate[] range : new LocalDate[][] {{nextYearStart, nextYearStart}, {today, nextYearStart}, {nextYearStart.plusDays(40), nextYearStart.plusDays(41)}}) {
            String body = "{\"type\":\"CL\",\"from\":\"" + range[0] + "\",\"to\":\"" + range[1] + "\",\"part\":\"FULL\",\"reason\":\"x\"}";
            mockMvc.perform(get("/api/v1/leave/previews").cookie(plain.cookie()).param("type", "CL").param("from", range[0].toString()).param("to", range[1].toString()).param("part", "FULL"))
                    .andExpect(jsonPath("$.data.error").value(org.hamcrest.Matchers.startsWith(detailStart)));
            mockMvc.perform(write(post("/api/v1/leave/requests"), plain, body)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(detailStart)));
            mockMvc.perform(write(post("/api/v1/leave/requests"), hr, body)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(detailStart)));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE employee_id IN (?, ?)", Integer.class, plain.id(), hr.id())).isZero();
    }

    // ───────────────────────── the year-end job ─────────────────────────

    void ledger(UUID employee, String type, double delta) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, at) VALUES (?, ?, ?, ?, 'ADJUSTMENT', '2026-06-01T10:00:00Z')",
                UUID.randomUUID(), employee, type, BigDecimal.valueOf(delta));
    }

    double lapsed(UUID employee, String type) {
        BigDecimal sum = jdbc.queryForObject("SELECT coalesce(sum(delta), 0) FROM leave_ledger WHERE employee_id = ? AND leave_type = ? AND reason = 'EXPIRED'", BigDecimal.class, employee, type);
        return sum.doubleValue();
    }

    Map<String, Object> runYearEnd(Instant now) {
        return TenantContext.call(data.business(), () -> jobs.jobLeaveYearEnd(now));
    }

    /** Saves the leave policy of this business directly (the API stamps today's date as the change day). */
    void savePolicy(String json) {
        jdbc.update("DELETE FROM settings WHERE key = 'leave.policy' AND business_id = ?", data.business());
        jdbc.update("INSERT INTO settings (key, value, updated_at, business_id) VALUES ('leave.policy', ?::jsonb, now(), ?)", json, data.business());
    }

    @Test
    void byDefaultCasualLeaveLapsesAndEarnedLeaveCarriesToItsCapOnceAYear() {
        ledger(plain.id(), "CL", 5);
        ledger(plain.id(), "EL", 40);
        ledger(plain.id(), "CO", 3);
        runYearEnd(JANUARY_2027);
        assertThat(lapsed(plain.id(), "CL")).isEqualTo(-5);
        assertThat(lapsed(plain.id(), "EL")).isEqualTo(-10);
        assertThat(lapsed(plain.id(), "CO")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'leave.year_end' AND key = '2026' AND business_id = ?", Integer.class, data.business())).isEqualTo(1);
        // a second run, even later in the year, changes nothing
        assertThat(runYearEnd(JANUARY_2027.plusSeconds(86_400))).containsEntry("lapsed", 0);
        assertThat(lapsed(plain.id(), "CL")).isEqualTo(-5);
        assertThat(lapsed(plain.id(), "EL")).isEqualTo(-10);
    }

    @Test
    void theBusinessChoiceDecidesWhatCollapsesAndWhatCarriesForward() {
        savePolicy("{\"yearStartMonth\":1,\"yearStartDay\":1,\"yearEnd\":{\"CL\":{\"action\":\"CARRY_ALL\"},\"EL\":{\"action\":\"COLLAPSE\"}}}");
        ledger(plain.id(), "CL", 5);
        ledger(plain.id(), "EL", 40);
        runYearEnd(JANUARY_2027);
        assertThat(lapsed(plain.id(), "CL")).isZero();
        assertThat(lapsed(plain.id(), "EL")).isEqualTo(-40);
    }

    @Test
    void aCappedChoiceCarriesUpToTheCap() {
        savePolicy("{\"yearStartMonth\":1,\"yearStartDay\":1,\"yearEnd\":{\"CL\":{\"action\":\"CARRY_CAPPED\",\"carryForwardCap\":2},\"EL\":{\"action\":\"CARRY_CAPPED\",\"carryForwardCap\":10}}}");
        ledger(plain.id(), "CL", 5);
        ledger(plain.id(), "EL", 40);
        runYearEnd(JANUARY_2027);
        assertThat(lapsed(plain.id(), "CL")).isEqualTo(-3);
        assertThat(lapsed(plain.id(), "EL")).isEqualTo(-30);
    }

    @Test
    void theJobFollowsTheBusinessLeaveYear() {
        savePolicy("{\"yearStartMonth\":4,\"yearStartDay\":1,\"yearEnd\":{}}");
        ledger(plain.id(), "CL", 5);
        // 2 January is mid-year for an April year (and the year that ended in March 2026 is before go-live): nothing happens
        assertThat(runYearEnd(JANUARY_2027)).containsEntry("lapsed", 0);
        assertThat(lapsed(plain.id(), "CL")).isZero();
        // 2 April 2027: the leave year 2026 (1 Apr 2026 - 31 Mar 2027) has ended
        runYearEnd(APRIL_2027);
        assertThat(lapsed(plain.id(), "CL")).isEqualTo(-5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'leave.year_end' AND key = '2026' AND business_id = ?", Integer.class, data.business())).isEqualTo(1);
        assertThat(runYearEnd(APRIL_2027)).containsEntry("lapsed", 0);
    }

    @Test
    void aYearEndBeforeTheStartDateWasChangedIsNotProcessed() {
        savePolicy("{\"yearStartMonth\":4,\"yearStartDay\":1,\"yearStartChangedOn\":\"2027-04-05\",\"yearEnd\":{}}");
        ledger(plain.id(), "CL", 5);
        assertThat(runYearEnd(APRIL_2027)).containsEntry("lapsed", 0);
        assertThat(lapsed(plain.id(), "CL")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'leave.year_end' AND business_id = ?", Integer.class, data.business())).isZero();
    }
}
