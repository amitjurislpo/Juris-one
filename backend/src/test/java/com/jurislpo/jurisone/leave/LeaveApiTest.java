package com.jurislpo.jurisone.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
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

/** Apply / cancel leave and the approvals flows through the API. */
class LeaveApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID empRole, hrRole, finRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage");
        finRole = data.role("fm", "self", "dir", "people.view", "struct.manage", "pay.view");
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'appr.reg') ON CONFLICT DO NOTHING", finRole);
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, max_stretch, paid, needs_balance, sort_order, active, expiry_days)
            VALUES ('CL', 'Casual leave', 12, 1, 0, 3, true, true, 1, true, NULL), ('EL', 'Earned leave', 18, 1.5, 30, NULL, true, true, 2, true, NULL),
                   ('CO', 'Comp-off', 0, 0, 0, NULL, true, true, 3, true, 60), ('LOP', 'Leave without pay', 0, 0, 0, NULL, false, false, 4, true, NULL)
            ON CONFLICT (code) DO NOTHING""");
        UUID shift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_DAY'", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (shift == null) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    /** GET /api/leave/previews for a full-day request. */
    static MockHttpServletRequestBuilder preview(Person person, String type, LocalDate from, LocalDate to) {
        return get("/api/leave/previews").cookie(person.cookie()).param("type", type).param("from", from.toString()).param("to", to.toString()).param("part", "FULL");
    }

    static int sequence = 7000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String code = "JC-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = 'IN_DAY') WHERE id = ?", id);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    void credit(UUID employee, String type, double days) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, at) VALUES (?, ?, ?, ?, 'ADJUSTMENT', now())", UUID.randomUUID(), employee, type, BigDecimal.valueOf(days));
    }

    /** The next Mon–Wed run at least a week out (no holidays in the test DB). */
    static LocalDate nextMonday() {
        LocalDate nextWeek = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date().plusDays(7);
        while (AttendanceEngine.dowOf(nextWeek) != 1) nextWeek = nextWeek.plusDays(1);
        return nextWeek;
    }

    @Test
    void applyShortBalanceBecomesLopAndHrApprovesOnce() throws Exception {
        Person employee = person(empRole);
        Person hrPerson = person(hrRole);
        credit(employee.id(), "EL", 1.5);
        LocalDate monday = nextMonday();
        String body = "{\"type\":\"EL\",\"from\":\"" + monday + "\",\"to\":\"" + monday.plusDays(2) + "\",\"part\":\"FULL\",\"reason\":\"family\"}";
        mockMvc.perform(preview(employee, "EL", monday, monday.plusDays(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(3.0)).andExpect(jsonPath("$.data.paidDays").value(1.5)).andExpect(jsonPath("$.data.lopDays").value(1.5))
                .andExpect(jsonPath("$.data.balanceAfter").value(0.0));
        mockMvc.perform(write(post("/api/leave/requests")).cookie(employee.cookie()).content(body)).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith("sent to HR · 1.5 day(s) unpaid")));
        mockMvc.perform(write(post("/api/leave/requests")).cookie(employee.cookie()).content(body))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("You already have a pending request from " + monday)));
        mockMvc.perform(preview(employee, "CL", monday, monday.plusDays(3)))
                .andExpect(jsonPath("$.data.error").value("Casual leave can be taken for at most 3 days at a stretch."));

        String id = jdbc.queryForObject("SELECT id::text FROM leave_requests WHERE employee_id = ?", String.class, employee.id());
        mockMvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(employee.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/approvals").cookie(hrPerson.cookie())).andExpect(jsonPath("$.data.leaveCards[?(@.id == '" + id + "')].impact")
                .value(org.hamcrest.Matchers.hasItem("Only 1.5 available — 1.5 day(s) become loss of pay")));
        mockMvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(hrPerson.cookie()).content("{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(jsonPath("$.message").value("Approved Person " + employee.code() + "’s earned leave"));
        mockMvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(hrPerson.cookie()).content("{\"approve\":false}"))
                .andExpect(jsonPath("$.detail").value("This request was already decided."));
        assertThat(jdbc.queryForObject("SELECT delta FROM leave_ledger WHERE ref_id = ? AND reason = 'USED'", BigDecimal.class, id)).isEqualByComparingTo("-1.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/leave'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM email_outbox WHERE \"to\" = ? ORDER BY created_at DESC LIMIT 1", String.class, employee.code().toLowerCase() + "@example.com"))
                .startsWith("Earned leave (").endsWith(") approved");
    }

    @Test
    void cancelIsOnlyForMyPendingRequests() throws Exception {
        Person employee = person(empRole);
        Person other = person(empRole);
        credit(employee.id(), "CL", 2);
        LocalDate monday = nextMonday();
        mockMvc.perform(write(post("/api/leave/requests")).cookie(employee.cookie()).content("{\"type\":\"CL\",\"from\":\"" + monday + "\",\"to\":\"" + monday + "\",\"part\":\"FIRST_HALF\",\"reason\":\"\"}"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Casual leave for 0.5 days")));
        String id = jdbc.queryForObject("SELECT id::text FROM leave_requests WHERE employee_id = ?", String.class, employee.id());
        mockMvc.perform(write(delete("/api/leave/requests/" + id)).cookie(other.cookie())).andExpect(jsonPath("$.detail").value("Only your pending requests can be cancelled."));
        mockMvc.perform(write(delete("/api/leave/requests/" + id)).cookie(employee.cookie())).andExpect(jsonPath("$.message").value("Leave request cancelled"));
        mockMvc.perform(get("/api/leave?status=CANCELLED").cookie(employee.cookie())).andExpect(jsonPath("$.data.requests[0].status").value("CANCELLED"));
    }

    @Test
    void financeApprovesAMissedCheckoutAndHolidayWorkEarnsCompOff() throws Exception {
        Person employee = person(empRole);
        Person finance = person(finRole);
        LocalDate today = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
        LocalDate day = today.minusDays(1);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.minusDays(1);
        if (!day.toString().substring(0, 7).equals(today.toString().substring(0, 7))) return; // needs an open month
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB')", UUID.randomUUID(), employee.id(),
                Timestamp.from(AttendanceEngine.atLocal(day, "09:30", "Asia/Kolkata")));
        UUID regularizationId = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_out, status) VALUES (?, ?, ?, 'MISSED_CHECKOUT', ?, 'PENDING')",
                regularizationId, employee.id(), Date.valueOf(day), Timestamp.from(AttendanceEngine.atLocal(day, "18:45", "Asia/Kolkata")));
        mockMvc.perform(get("/api/approvals?tab=regularizations").cookie(finance.cookie())).andExpect(jsonPath("$.data.regularizationCards[?(@.id == '" + regularizationId + "')].detail")
                .value(org.hamcrest.Matchers.hasItem("Checked in 9:30 AM · asks to set check-out to 6:45 PM")));
        mockMvc.perform(write(put("/api/attendance/regularizations/" + regularizationId + "/decision")).cookie(finance.cookie()).content("{\"approve\":true}"))
                .andExpect(jsonPath("$.message").value("Approved Person " + employee.code() + "’s missed check-out regularization"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'REGULARIZATION' AND kind = 'OUT'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT decided_role FROM regularization_requests WHERE id = ?", String.class, regularizationId)).isEqualTo("Fm");

        // weekend work of 5h → ½ day comp-off
        LocalDate saturday = today.minusDays(1);
        while (AttendanceEngine.dowOf(saturday) != 6) saturday = saturday.minusDays(1);
        if (!saturday.toString().substring(0, 7).equals(today.toString().substring(0, 7))) return;
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), employee.id(), Timestamp.from(AttendanceEngine.atLocal(saturday, "10:00", "Asia/Kolkata")),
                UUID.randomUUID(), employee.id(), Timestamp.from(AttendanceEngine.atLocal(saturday, "15:00", "Asia/Kolkata")));
        UUID holidayId = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, status) VALUES (?, ?, ?, 'HOLIDAY_WORK', 'PENDING')", holidayId, employee.id(), Date.valueOf(saturday));
        mockMvc.perform(write(put("/api/attendance/regularizations/" + holidayId + "/decision")).cookie(finance.cookie()).content("{\"approve\":true}"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith("comp-off request · 0.5 comp-off credited")));
        assertThat(jdbc.queryForObject("SELECT expires_on FROM leave_ledger WHERE ref_id = ?", Date.class, holidayId.toString()).toLocalDate()).isEqualTo(saturday.plusDays(60));
    }

    @Test
    void earnedLeaveIsNotAvailableDuringProbation() throws Exception {
        Person employee = person(empRole);
        credit(employee.id(), "EL", 5);
        LocalDate monday = nextMonday();
        jdbc.update("UPDATE employees SET status = 'PROBATION' WHERE id = ?", employee.id());
        mockMvc.perform(preview(employee, "EL", monday, monday))
                .andExpect(jsonPath("$.data.error").value("Earned leave isn't available during probation. Choose another leave type."));
        mockMvc.perform(write(post("/api/leave/requests")).cookie(employee.cookie()).content("{\"type\":\"EL\",\"from\":\"" + monday + "\",\"to\":\"" + monday + "\",\"part\":\"FULL\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/leave").cookie(employee.cookie())).andExpect(jsonPath("$.data.types[?(@.code=='EL')].lockedReason").value("Earned leave isn't available during probation. Choose another leave type."));
        // once permanent, the same request is fine
        jdbc.update("UPDATE employees SET status = 'ACTIVE' WHERE id = ?", employee.id());
        mockMvc.perform(preview(employee, "EL", monday, monday)).andExpect(jsonPath("$.data.error").doesNotExist());
    }
}
