package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.service.AttendanceService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
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

/** On Duty requests: the rules, the decisions, the effect on attendance, and the refusals (hostile input, other people's rows, other businesses, locked months, duplicates). */
class OnDutyApiTest extends IntegrationTest {

    static final String ZONE = "Asia/Kolkata";
    static final String URL = "/api/v1/attendance/on-duty-requests";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AttendanceService attendanceService;
    TestData data;
    UUID employeeRole, hrRole, financeRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("on-duty-test");
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        financeRole = data.role("fm", "self", "dir", "people.view", "pay.view");
        // the previous month stays open until the 28th, so a recent working day can always be asked for
        jdbc.update("""
            INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{"trackingStartDate":"2026-04-01","lockDayOfNextMonth":28}', now())
            ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value""", data.business());
        jdbc.update("DELETE FROM settings WHERE business_id = ? AND key = 'attendance.regularization'", data.business());
        jdbc.update("DELETE FROM on_duty_requests WHERE business_id = ?", data.business());
        jdbc.update("DELETE FROM holidays WHERE business_id = ?", data.business());
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, max_stretch, paid, needs_balance, sort_order, active)
            VALUES ('CL', 'Casual leave', 12, 1, 0, 3, true, true, 1, true) ON CONFLICT (code) DO NOTHING""");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 8000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(TestData in, UUID role) throws Exception {
        String code = "JD-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = in.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    Person person(UUID role) throws Exception {
        return person(data, role);
    }

    static LocalDate today() {
        return AttendanceEngine.localParts(Instant.now(), ZONE).date();
    }

    /** A working day (Mon-Fri) at least {@code minDaysAgo} days back. */
    static LocalDate workday(int minDaysAgo) {
        LocalDate day = today().minusDays(minDaysAgo);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.minusDays(1);
        return day;
    }

    static String body(LocalDate day, String part, String reason) {
        return "{\"date\":\"" + day + "\",\"part\":\"" + part + "\",\"location\":\"Client office, Pune\",\"reason\":\"" + reason + "\"}";
    }

    String idOf(Person person, LocalDate day) {
        return jdbc.queryForObject("SELECT id::text FROM on_duty_requests WHERE employee_id = ? AND work_date = ? ORDER BY created_at DESC LIMIT 1", String.class, person.id(), java.sql.Date.valueOf(day));
    }

    String statusOf(String id) {
        return jdbc.queryForObject("SELECT status::text FROM on_duty_requests WHERE id = ?::uuid", String.class, id);
    }

    int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    JsonNode summary(Person person, LocalDate from, LocalDate to) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/attendance/summary").queryParam("view", "tabular").queryParam("from", from.toString()).queryParam("to", to.toString()).cookie(person.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    String decision(boolean approve, String note) {
        return "{\"approve\":" + approve + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    @Test
    void anApprovedOnDutyDayCountsAsAPresentPayableDay() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit in Pune")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("On Duty request added successfully")).andExpect(jsonPath("$.data[0].ok").value(true));
        String id = idOf(employee, day);
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.on_duty_request' AND entity_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/attendance?tab=on-duty&scope=team'", hr.id())).isEqualTo(1);
        // a pending request changes nothing yet: the day (nobody checked in) is a loss-of-pay day
        JsonNode pending = summary(employee, day, day);
        assertThat(pending.get("counters").get("onDuty").asDouble()).isZero();
        assertThat(pending.get("days").get(0).get("status").asText()).isEqualTo("ABSENT");

        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.on_duty_approve' AND entity_id = ?", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/attendance?tab=on-duty'", employee.id())).isEqualTo(1);

        JsonNode approved = summary(employee, day, day);
        assertThat(approved.get("days").get(0).get("status").asText()).isEqualTo("PRESENT");
        assertThat(approved.get("days").get(0).get("label").asText()).isEqualTo("On duty");
        assertThat(approved.get("counters").get("onDuty").asDouble()).isEqualTo(1.0);
        assertThat(approved.get("counters").get("present").asDouble()).isZero();
        assertThat(approved.get("counters").get("payableDays").asDouble()).isEqualTo(1.0);
        assertThat(approved.get("counters").get("absent").asDouble()).isZero();
        assertThat(approved.get("counters").get("hours").get("onDuty").asDouble()).isPositive();
    }

    @Test
    void aHalfDayOnDutyIsHalfPayableAndTheOtherHalfStillNeedsWork() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(3);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "SECOND_HALF", "Site inspection"))).andExpect(status().isCreated());
        mockMvc.perform(write(put(URL + "/" + idOf(employee, day) + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isOk());
        JsonNode counters = summary(employee, day, day).get("counters");
        assertThat(counters.get("onDuty").asDouble()).isEqualTo(0.5);
        assertThat(counters.get("payableDays").asDouble()).isEqualTo(0.5);
        assertThat(counters.get("absent").asDouble()).isEqualTo(0.5); // nobody worked the other half
    }

    @Test
    void aLockedMonthKeepsItsOnDutyDays() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        mockMvc.perform(write(put(URL + "/" + idOf(employee, day) + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isOk());
        attendanceService.materialize(attendanceService.loadAttendanceEmployees("e.id = ?", employee.id()), day, day, true, Instant.now());
        assertThat(count("SELECT count(*) FROM attendance_days WHERE employee_id = ? AND work_date = ? AND locked AND on_duty_days = 1 AND lop_days = 0", employee.id(), java.sql.Date.valueOf(day))).isEqualTo(1);
        JsonNode locked = summary(employee, day, day);
        assertThat(locked.get("days").get(0).get("status").asText()).isEqualTo("PRESENT");
        assertThat(locked.get("counters").get("onDuty").asDouble()).isEqualTo(1.0);
        assertThat(locked.get("counters").get("payableDays").asDouble()).isEqualTo(1.0);
    }

    @Test
    void rejectingNeedsRemarksAndOnlyTheOwnerWithdrawsAPendingRequest() throws Exception {
        Person employee = person(employeeRole), other = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        String id = idOf(employee, day);
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(other.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(hr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(false, "  "))).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(false, "x".repeat(301)))).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{}")).andExpect(status().isBadRequest());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(false, "no client meeting that day"))).andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("DECLINED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.on_duty_decline' AND entity_id = ?", id)).isEqualTo(1);
        // the first decision is final, and a decided request can't be withdrawn
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(employee.cookie())).andExpect(status().isUnprocessableEntity());
        // the rejected day can be asked for again, and the new pending request can be withdrawn
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit, with the email"))).andExpect(status().isCreated());
        String second = idOf(employee, day);
        mockMvc.perform(write(delete(URL + "/" + second)).cookie(employee.cookie())).andExpect(jsonPath("$.message").value("Request withdrawn"));
        assertThat(statusOf(second)).isEqualTo("CANCELLED");
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.on_duty_cancel' AND entity_id = ?", second)).isEqualTo(1);
    }

    @Test
    void aDayHasOnlyOneOpenRequest() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FIRST_HALF", "Again")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ON_DUTY_ALREADY_REQUESTED"));
        mockMvc.perform(write(put(URL + "/" + idOf(employee, day) + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isOk());
        // still refused once approved
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Again"))).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ? AND work_date = ?", employee.id(), java.sql.Date.valueOf(day))).isEqualTo(1);
        // the database itself refuses a second open row for the day, whatever the code does
        try {
            jdbc.update("INSERT INTO on_duty_requests (id, business_id, employee_id, work_date, part, reason, status) VALUES (?, ?, ?, ?, 'FULL', 'x', 'PENDING')",
                    UUID.randomUUID(), data.business(), employee.id(), java.sql.Date.valueOf(day));
            throw new AssertionError("the unique index should have refused a second open request");
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            // refused
        }
    }

    @Test
    void theServerRefusesHostileAndImpossibleInput() throws Exception {
        Person employee = person(employeeRole);
        LocalDate day = workday(2);
        String[] refused = {
            "{}", "[]", "\"text\"", "{\"date\":\"2026-13-45\",\"reason\":\"x\"}", "{\"date\":\"" + day + "' OR '1'='1\",\"reason\":\"x\"}", "{\"date\":\"" + day + "\"}",
            "{\"date\":\"" + day + "\",\"reason\":\"   \"}", "{\"date\":\"" + day + "\",\"reason\":\"" + "a".repeat(501) + "\"}",
            "{\"date\":\"" + day + "\",\"part\":\"FULL'; DROP TABLE employees;--\",\"reason\":\"x\"}", "{\"date\":\"" + day + "\",\"part\":\"full\",\"reason\":\"x\"}",
            "{\"date\":\"" + day + "\",\"location\":\"" + "l".repeat(121) + "\",\"reason\":\"x\"}",
            "{\"date\":\"" + day + "\",\"toDate\":\"" + day.minusDays(1) + "\",\"reason\":\"x\"}", "{\"date\":\"" + day.minusDays(40) + "\",\"toDate\":\"" + day + "\",\"reason\":\"x\"}",
            "{\"date\":\"" + day + "\",\"toDate\":\"soon\",\"reason\":\"x\"}", "{\"date\":12345,\"reason\":\"x\"}", "{\"date\":\"" + day + "\",\"reason\":{\"a\":1}}", "not json at all",
        };
        for (String hostile : refused) {
            mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(hostile)).andExpect(status().is4xxClientError());
        }
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ?", employee.id())).isZero();
        assertThat(count("SELECT count(*) FROM employees WHERE id = ?", employee.id())).isEqualTo(1);

        // an id the client sends is never trusted: the request is always for the signed-in user
        Person colleague = person(employeeRole);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("{\"date\":\"" + day + "\",\"reason\":\"x\",\"employeeId\":\"" + colleague.id() + "\",\"status\":\"APPROVED\"}")).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ? AND status = 'PENDING'", employee.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ?", colleague.id())).isZero();

        // markup is stored as plain text and returned as it was written (the page escapes it)
        LocalDate other = workday(5);
        String script = "<script>alert(1)</script>";
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(other, "FULL", script))).andExpect(status().isCreated());
        mockMvc.perform(get(URL + "/" + idOf(employee, other)).cookie(employee.cookie())).andExpect(jsonPath("$.data.reason").value(script));

        // hostile filters are bound parameters, never SQL
        mockMvc.perform(get(URL).queryParam("search", "x' OR '1'='1' --").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL).queryParam("employeeId", "1; DROP TABLE employees").cookie(employee.cookie())).andExpect(status().isOk()); // ignored for the own list
        mockMvc.perform(get(URL).queryParam("departmentId", "1; DROP TABLE employees").cookie(employee.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).queryParam("status", "PENDING' --").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL).queryParam("month", "2026-99").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/not-a-uuid").cookie(employee.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(write(delete(URL + "/not-a-uuid")).cookie(employee.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(day, "FULL", "x"))).andExpect(status().is4xxClientError()); // no session
    }

    @Test
    void holidaysWeeklyOffsAndLeaveCannotBeOnDuty() throws Exception {
        Person employee = person(employeeRole);
        LocalDate holiday = workday(4);
        jdbc.update("INSERT INTO holidays (id, business_id, date, name, region) VALUES (?, ?, ?, 'Founders day', 'ALL')", UUID.randomUUID(), data.business(), java.sql.Date.valueOf(holiday));
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(holiday, "FULL", "x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("That day is a holiday or weekly off."));
        LocalDate saturday = today().minusDays(2);
        while (AttendanceEngine.dowOf(saturday) != 6) saturday = saturday.minusDays(1);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(saturday, "FULL", "x"))).andExpect(status().isUnprocessableEntity());
        // a working day before the holiday, so the two never land on the same date whatever today's weekday is
        LocalDate leaveDay = holiday.minusDays(1);
        while (AttendanceEngine.dowOf(leaveDay) == 0 || AttendanceEngine.dowOf(leaveDay) == 6) leaveDay = leaveDay.minusDays(1);
        jdbc.update("INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days, status) VALUES (?, ?, 'CL', ?, ?, 1, 'APPROVED')", UUID.randomUUID(), employee.id(),
                java.sql.Date.valueOf(leaveDay), java.sql.Date.valueOf(leaveDay));
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(leaveDay, "FULL", "x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("You are on leave that day."));
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ?", employee.id())).isZero();
    }

    @Test
    void theWindowAndTheMonthlyLockAreEnforcedOnRequestAndOnApproval() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        // too far back (default 30 days) and too far ahead (90 days)
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(workday(45), "FULL", "x"))).andExpect(jsonPath("$.detail").value("Requests can be made up to 30 days back."));
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(today().plusDays(95), "FULL", "x")))
                .andExpect(jsonPath("$.detail").value("An On Duty request can be made at most 90 days ahead."));
        // a planned duty tomorrow-ish is fine
        LocalDate planned = today().plusDays(7);
        while (AttendanceEngine.dowOf(planned) == 0 || AttendanceEngine.dowOf(planned) == 6) planned = planned.plusDays(1);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(planned, "FULL", "Planned client visit"))).andExpect(status().isCreated());

        // a closed month: with a long back-date limit the lock still refuses it
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.regularization', '{\"backDateLimitDays\":400}', now())", data.business());
        LocalDate old = workday(70);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(old, "FULL", "x")))
                .andExpect(jsonPath("$.detail").value("Attendance for this month is locked. Ask Finance to adjust LOP in payroll."));
        // a request that was pending when its month closed can be rejected but not approved
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO on_duty_requests (id, business_id, employee_id, work_date, part, reason, status) VALUES (?, ?, ?, ?, 'FULL', 'late', 'PENDING')", id, data.business(), employee.id(), java.sql.Date.valueOf(old));
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isUnprocessableEntity());
        assertThat(statusOf(id.toString())).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(false, "month is closed"))).andExpect(status().isOk());
        assertThat(statusOf(id.toString())).isEqualTo("DECLINED");
    }

    @Test
    void otherPeopleAndOtherBusinessesNeverSeeOrDecideARequest() throws Exception {
        Person employee = person(employeeRole), colleague = person(employeeRole), hr = person(hrRole), finance = person(financeRole);
        TestData stranger = data.inBusiness("on-duty-other");
        Person outsiderHr = person(stranger, stranger.role("hrm", "self", "dir", "att.all", "appr.reg"));
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        String id = idOf(employee, day);

        mockMvc.perform(get(URL + "/" + id).cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + id).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + id).cookie(colleague.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/" + id).cookie(finance.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/" + id).cookie(outsiderHr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(outsiderHr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(outsiderHr.cookie()).content(decision(true, null))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(outsiderHr.cookie()).content("{\"ids\":[\"" + id + "\"],\"approve\":true}"))
                .andExpect(jsonPath("$.data[0].ok").value(false));
        assertThat(statusOf(id)).isEqualTo("PENDING");

        // the whole-business list and every decision belong to Admin / HR (appr.reg); the others are refused
        for (Person denied : new Person[] {employee, colleague, finance}) {
            mockMvc.perform(get(URL).queryParam("scope", "all").cookie(denied.cookie())).andExpect(status().isForbidden());
            mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(denied.cookie()).content(decision(true, null))).andExpect(status().isForbidden());
            mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(denied.cookie()).content("{\"ids\":[\"" + id + "\"],\"approve\":true}")).andExpect(status().isForbidden());
        }
        assertThat(statusOf(id)).isEqualTo("PENDING");
        mockMvc.perform(get(URL).queryParam("scope", "all").cookie(outsiderHr.cookie())).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get(URL).queryParam("scope", "all").cookie(hr.cookie())).andExpect(jsonPath("$.data.total").value(1));
        // the own list never shows somebody else's request, even when the filter names them
        mockMvc.perform(get(URL).queryParam("employeeId", employee.id().toString()).cookie(colleague.cookie())).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get(URL).queryParam("scope", "mine").cookie(employee.cookie())).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get(URL).queryParam("scope", "all").queryParam("search", "x' OR '1'='1' --").cookie(hr.cookie())).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void severalRequestsAreDecidedTogetherAndOwnRequestsCannotBeApproved() throws Exception {
        Person first = person(employeeRole), second = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        mockMvc.perform(write(post(URL)).cookie(first.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        mockMvc.perform(write(post(URL)).cookie(second.cookie()).content(body(day, "FULL", "Client visit"))).andExpect(status().isCreated());
        mockMvc.perform(write(post(URL)).cookie(hr.cookie()).content(body(day, "FULL", "HR on a visit"))).andExpect(status().isCreated());
        String own = idOf(hr, day);
        String[] ids = {idOf(first, day), idOf(second, day), own, UUID.randomUUID().toString()};
        String list = "[\"" + String.join("\",\"", ids) + "\"]";
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(hr.cookie()).content("{\"ids\":" + list + ",\"approve\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(hr.cookie()).content("{\"ids\":[],\"approve\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(hr.cookie()).content("{\"ids\":" + list + "}")).andExpect(status().isBadRequest());
        JsonNode result = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(hr.cookie()).content("{\"ids\":" + list + ",\"approve\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(result.get(0).get("ok").asBoolean()).isTrue();
        assertThat(result.get(1).get("ok").asBoolean()).isTrue();
        assertThat(result.get(2).get("ok").asBoolean()).isFalse(); // their own request
        assertThat(result.get(3).get("ok").asBoolean()).isFalse(); // unknown
        assertThat(statusOf(ids[0])).isEqualTo("APPROVED");
        assertThat(statusOf(ids[1])).isEqualTo("APPROVED");
        assertThat(statusOf(own)).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + own + "/decision")).cookie(hr.cookie()).content(decision(true, null))).andExpect(status().isUnprocessableEntity());
        String ids51 = "[" + java.util.stream.IntStream.range(0, 51).mapToObj(n -> "\"" + UUID.randomUUID() + "\"").collect(java.util.stream.Collectors.joining(",")) + "]";
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(hr.cookie()).content("{\"ids\":" + ids51 + ",\"approve\":true}")).andExpect(status().isBadRequest());
    }

    @Test
    void aRangeMakesOneRequestPerWorkingDayAndReportsEachDay() throws Exception {
        Person employee = person(employeeRole);
        LocalDate last = workday(2);
        LocalDate first = last.minusDays(6); // seven calendar days: always includes a weekend
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("{\"date\":\"" + first + "\",\"toDate\":\"" + last + "\",\"reason\":\"Training week\"}"))
                .andExpect(status().isCreated());
        int expected = 0;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) if (AttendanceEngine.dowOf(day) != 0 && AttendanceEngine.dowOf(day) != 6) expected++;
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ?", employee.id())).isEqualTo(expected);
        assertThat(count("SELECT count(DISTINCT batch_id) FROM on_duty_requests WHERE employee_id = ? AND batch_id IS NOT NULL", employee.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'attendance.on_duty_request' AND actor_id = ?", employee.id())).isEqualTo(expected);
        // asking again for the same range: every day already has a request, so nothing is made and the first reason is shown
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("{\"date\":\"" + first + "\",\"toDate\":\"" + last + "\",\"reason\":\"Again\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT count(*) FROM on_duty_requests WHERE employee_id = ?", employee.id())).isEqualTo(expected);
    }
}
