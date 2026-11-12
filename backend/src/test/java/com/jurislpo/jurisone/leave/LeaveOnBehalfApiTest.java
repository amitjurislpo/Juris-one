package com.jurislpo.jurisone.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Admin / HR (appr.leave) applying leave on behalf of an employee: permission, business isolation, the leave-year rule, auto-approve, audit and notification. */
class LeaveOnBehalfApiTest extends IntegrationTest {

    static final String REQUESTS = "/api/v1/leave/requests";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    TestData data;
    Person hr, employee, bystander, otherBusinessEmployee;

    record Person(UUID id, String code, Cookie cookie) {}

    @BeforeEach
    void setUp() throws Exception {
        data = new TestData(jdbc).inBusiness("leave-behalf-" + TestData.letters(8));
        LocalDate yearStart = today().minusDays(3);
        int startDay = yearStart.getMonthValue() == 2 && yearStart.getDayOfMonth() == 29 ? 28 : yearStart.getDayOfMonth();
        saveSetting(data, "leave.policy", "{\"yearStartMonth\":" + yearStart.getMonthValue() + ",\"yearStartDay\":" + startDay + ",\"yearEnd\":{}}");
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, max_stretch, paid, needs_balance, sort_order, active, expiry_days)
            VALUES ('CL', 'Casual leave', 12, 1, 0, 3, true, true, 1, true, NULL), ('EL', 'Earned leave', 18, 1.5, 30, NULL, true, true, 2, true, NULL),
                   ('CO', 'Comp-off', 0, 0, 0, NULL, true, true, 3, true, 60), ('LOP', 'Leave without pay', 0, 0, 0, NULL, false, false, 4, true, NULL)
            ON CONFLICT (code) DO NOTHING""");
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_DAY'", Integer.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
        hr = person(data, data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view"));
        UUID plainRole = data.role("employee", "self", "dir");
        employee = person(data, plainRole);
        bystander = person(data, plainRole);
        TestData other = new TestData(jdbc).inBusiness("leave-behalf-other-" + TestData.letters(8));
        otherBusinessEmployee = person(other, other.role("employee", "self", "dir"));
    }

    static LocalDate today() {
        return AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
    }

    void saveSetting(TestData business, String key, String json) {
        jdbc.update("INSERT INTO settings (key, value, updated_at, business_id) VALUES (?, ?::jsonb, now(), ?) ON CONFLICT (key, business_id) DO UPDATE SET value = EXCLUDED.value", key, json, business.business());
    }

    Person person(TestData business, UUID role) throws Exception {
        String code = "OB" + TestData.letters(8);
        UUID id = business.employee(code, code.toLowerCase() + "@example.com", role);
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = 'IN_DAY') WHERE id = ?", id);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, Person person, String body) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON).content(body).cookie(person.cookie());
    }

    /** A Monday a week or more ahead: inside the leave year, no holidays in the test database. */
    static LocalDate monday() {
        LocalDate day = today().plusDays(7);
        while (AttendanceEngine.dowOf(day) != 1) day = day.plusDays(1);
        return day;
    }

    static String body(LocalDate day, String employeeId) {
        return "{\"type\":\"CL\",\"from\":\"" + day + "\",\"to\":\"" + day + "\",\"part\":\"FULL\",\"reason\":\"raised by HR\"" + (employeeId == null ? "" : ",\"employeeId\":\"" + employeeId + "\"") + "}";
    }

    void credit(UUID person, double days) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, at) VALUES (?, ?, 'CL', ?, 'ADJUSTMENT', now())", UUID.randomUUID(), person, BigDecimal.valueOf(days));
    }

    int requestsOf(UUID person) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE employee_id = ?", Integer.class, person);
    }

    int audits(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND business_id = ?", Integer.class, action, data.business());
    }

    @Test
    void hrAppliesForAnEmployeeAndItWaitsForApprovalByDefault() throws Exception {
        credit(employee.id(), 2);
        LocalDate monday = monday();
        // the numbers are the employee's (2 days), not HR's (none)
        mockMvc.perform(get("/api/v1/leave/previews").cookie(hr.cookie()).param("type", "CL").param("from", monday.toString()).param("to", monday.toString()).param("part", "FULL")
                .param("employeeId", employee.id().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(2.0)).andExpect(jsonPath("$.data.lopDays").value(0.0));
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, employee.id().toString()))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("raised for Person " + employee.code())));
        assertThat(requestsOf(employee.id())).isEqualTo(1);
        assertThat(requestsOf(hr.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status::text FROM leave_requests WHERE employee_id = ?", String.class, employee.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = ? AND reason = 'USED'", Integer.class, employee.id())).isZero();
        // audited with the approver as the actor and the employee named; the employee is told
        assertThat(audits("leave.apply_on_behalf")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'leave.apply_on_behalf' AND business_id = ?", UUID.class, data.business())).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT after->>'onBehalfOf' FROM audit_log WHERE action = 'leave.apply_on_behalf' AND business_id = ?", String.class, data.business())).isEqualTo(employee.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/leave' AND text LIKE '% applied Casual leave for you (%'", Integer.class, employee.id())).isEqualTo(1);
        // the same days again: the employee's overlap rule applies
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, employee.id().toString()))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("You already have a pending request")));
        // HR can decide it (a different person than the employee); nobody can decide their own
        String requestId = jdbc.queryForObject("SELECT id::text FROM leave_requests WHERE employee_id = ?", String.class, employee.id());
        mockMvc.perform(write(put(REQUESTS + "/" + requestId + "/decision"), employee, "{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REQUESTS + "/" + requestId + "/decision"), hr, "{\"approve\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT delta FROM leave_ledger WHERE employee_id = ? AND reason = 'USED'", BigDecimal.class, employee.id())).isEqualByComparingTo("-1");
    }

    @Test
    void withAutoApproveOnItIsApprovedAtOnceAsTheApprover() throws Exception {
        saveSetting(data, "attendance.regularization", "{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":true}");
        credit(employee.id(), 2);
        LocalDate monday = monday();
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, employee.id().toString()))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith(" · approved")));
        assertThat(jdbc.queryForObject("SELECT status::text FROM leave_requests WHERE employee_id = ?", String.class, employee.id())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM leave_requests WHERE employee_id = ?", UUID.class, employee.id())).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT delta FROM leave_ledger WHERE employee_id = ? AND reason = 'USED'", BigDecimal.class, employee.id())).isEqualByComparingTo("-1");
        assertThat(audits("leave.apply_on_behalf")).isEqualTo(1);
        assertThat(audits("leave.approve")).isEqualTo(1);
    }

    @Test
    void autoApproveNeverApprovesARequestAPersonAppliedForThemselves() throws Exception {
        saveSetting(data, "attendance.regularization", "{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":true}");
        credit(hr.id(), 2);
        credit(employee.id(), 2);
        LocalDate monday = monday();
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, null))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REQUESTS), hr, body(monday.plusDays(7), hr.id().toString()))).andExpect(status().isCreated()); // naming oneself is the same as not naming anybody
        mockMvc.perform(write(post(REQUESTS), employee, body(monday, employee.id().toString()))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE employee_id IN (?, ?) AND status = 'PENDING'", Integer.class, hr.id(), employee.id())).isEqualTo(3);
        assertThat(audits("leave.apply_on_behalf")).isZero();
    }

    @Test
    void aPlainEmployeeCannotApplyOrPreviewForSomeoneElse() throws Exception {
        credit(bystander.id(), 2);
        LocalDate monday = monday();
        mockMvc.perform(write(post(REQUESTS), employee, body(monday, bystander.id().toString()))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leave/previews").cookie(employee.cookie()).param("type", "CL").param("from", monday.toString()).param("to", monday.toString()).param("part", "FULL")
                .param("employeeId", bystander.id().toString())).andExpect(status().isForbidden());
        assertThat(requestsOf(bystander.id())).isZero();
        assertThat(requestsOf(employee.id())).isZero();
        assertThat(audits("leave.apply_on_behalf")).isZero();
        mockMvc.perform(post(REQUESTS).header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON).content(body(monday, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherBusinessUnknownAndMalformedIdsAreRefusedAndChangeNothing() throws Exception {
        LocalDate monday = monday();
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, otherBusinessEmployee.id().toString()))).andExpect(status().isNotFound());
        mockMvc.perform(write(post(REQUESTS), hr, body(monday, UUID.randomUUID().toString()))).andExpect(status().isNotFound());
        for (String hostile : List.of("1; DROP TABLE leave_requests", "' OR '1'='1", "not-a-uuid", "%00", "<script>alert(1)</script>", "x".repeat(5000))) {
            mockMvc.perform(write(post(REQUESTS), hr, body(monday, hostile))).andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/leave/previews").cookie(hr.cookie()).param("type", "CL").param("from", monday.toString()).param("to", monday.toString()).param("part", "FULL")
                    .param("employeeId", hostile)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/leave/previews").cookie(hr.cookie()).param("type", "CL").param("from", monday.toString()).param("to", monday.toString()).param("part", "FULL")
                .param("employeeId", otherBusinessEmployee.id().toString())).andExpect(status().isNotFound());
        assertThat(requestsOf(otherBusinessEmployee.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_requests", Integer.class)).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM leave_requests", Integer.class)); // table still there
        assertThat(audits("leave.apply_on_behalf")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ?", Integer.class, otherBusinessEmployee.id())).isZero();
    }

    @Test
    void theLeaveYearRuleAndTheEmployeesOwnRulesApplyToTheOneItIsRaisedFor() throws Exception {
        LocalDate outsideTheYear = today().plusYears(2);
        while (AttendanceEngine.dowOf(outsideTheYear) != 1) outsideTheYear = outsideTheYear.plusDays(1);
        mockMvc.perform(write(post(REQUESTS), hr, body(outsideTheYear, employee.id().toString()))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Leave can only be applied within the current leave year (")));
        // a past day: the employee's own past rule applies (their work date can still be yesterday before their shift starts, so ask for three days back)
        mockMvc.perform(write(post(REQUESTS), hr, body(today().minusDays(3), employee.id().toString()))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Leave can't start in the past")));
        assertThat(requestsOf(employee.id())).isZero();
        assertThat(audits("leave.apply_on_behalf")).isZero();
    }
}
