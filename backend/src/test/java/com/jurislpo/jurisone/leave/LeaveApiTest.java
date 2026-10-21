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

    @Autowired MockMvc mvc;
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
        UUID shift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_DAY'", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (shift == null) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    /** GET /api/leave/previews for a full-day request. */
    static MockHttpServletRequestBuilder preview(Person p, String type, LocalDate from, LocalDate to) {
        return get("/api/leave/previews").cookie(p.cookie()).param("type", type).param("from", from.toString()).param("to", to.toString()).param("part", "FULL");
    }

    static int seq = 7000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String c = "JC-" + (++seq) + (int) (Math.random() * 1000);
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role);
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = 'IN_DAY') WHERE id = ?", id);
        String set = SignIn.session(mvc, c.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, c, new Cookie("jo_at", m.group(1)));
    }

    void credit(UUID employee, String type, double days) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, at) VALUES (?, ?, ?, ?, 'ADJUSTMENT', now())", UUID.randomUUID(), employee, type, BigDecimal.valueOf(days));
    }

    /** The next Mon–Wed run at least a week out (no holidays in the test DB). */
    static LocalDate nextMonday() {
        LocalDate d = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date().plusDays(7);
        while (AttendanceEngine.dowOf(d) != 1) d = d.plusDays(1);
        return d;
    }

    @Test
    void applyShortBalanceBecomesLopAndHrApprovesOnce() throws Exception {
        Person e = person(empRole);
        Person hr = person(hrRole);
        credit(e.id(), "EL", 1.5);
        LocalDate mon = nextMonday();
        String body = "{\"type\":\"EL\",\"from\":\"" + mon + "\",\"to\":\"" + mon.plusDays(2) + "\",\"part\":\"FULL\",\"reason\":\"family\"}";
        mvc.perform(preview(e, "EL", mon, mon.plusDays(2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(3.0)).andExpect(jsonPath("$.data.paidDays").value(1.5)).andExpect(jsonPath("$.data.lopDays").value(1.5))
                .andExpect(jsonPath("$.data.balanceAfter").value(0.0));
        mvc.perform(write(post("/api/leave/requests")).cookie(e.cookie()).content(body)).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.endsWith("sent to HR · 1.5 day(s) unpaid")));
        mvc.perform(write(post("/api/leave/requests")).cookie(e.cookie()).content(body))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("You already have a pending request from " + mon)));
        mvc.perform(preview(e, "CL", mon, mon.plusDays(3)))
                .andExpect(jsonPath("$.data.error").value("Casual leave can be taken for at most 3 days at a stretch."));

        String id = jdbc.queryForObject("SELECT id::text FROM leave_requests WHERE employee_id = ?", String.class, e.id());
        mvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(e.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/approvals").cookie(hr.cookie())).andExpect(jsonPath("$.data.leaveCards[?(@.id == '" + id + "')].impact")
                .value(org.hamcrest.Matchers.hasItem("Only 1.5 available — 1.5 day(s) become loss of pay")));
        mvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(jsonPath("$.msg").value("Approved Person " + e.code() + "’s earned leave"));
        mvc.perform(write(put("/api/leave/requests/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false}"))
                .andExpect(jsonPath("$.detail").value("This request was already decided."));
        assertThat(jdbc.queryForObject("SELECT delta FROM leave_ledger WHERE ref_id = ? AND reason = 'USED'", BigDecimal.class, id)).isEqualByComparingTo("-1.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/leave'", Integer.class, e.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT subject FROM email_outbox WHERE \"to\" = ? ORDER BY created_at DESC LIMIT 1", String.class, e.code().toLowerCase() + "@example.com"))
                .startsWith("Earned leave (").endsWith(") approved");
    }

    @Test
    void cancelIsOnlyForMyPendingRequests() throws Exception {
        Person e = person(empRole);
        Person other = person(empRole);
        credit(e.id(), "CL", 2);
        LocalDate mon = nextMonday();
        mvc.perform(write(post("/api/leave/requests")).cookie(e.cookie()).content("{\"type\":\"CL\",\"from\":\"" + mon + "\",\"to\":\"" + mon + "\",\"part\":\"FIRST_HALF\",\"reason\":\"\"}"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.startsWith("Casual leave for 0.5 days")));
        String id = jdbc.queryForObject("SELECT id::text FROM leave_requests WHERE employee_id = ?", String.class, e.id());
        mvc.perform(write(delete("/api/leave/requests/" + id)).cookie(other.cookie())).andExpect(jsonPath("$.detail").value("Only your pending requests can be cancelled."));
        mvc.perform(write(delete("/api/leave/requests/" + id)).cookie(e.cookie())).andExpect(jsonPath("$.msg").value("Leave request cancelled"));
        mvc.perform(get("/api/leave?status=CANCELLED").cookie(e.cookie())).andExpect(jsonPath("$.data.requests[0].status").value("CANCELLED"));
    }

    @Test
    void financeApprovesAMissedCheckoutAndHolidayWorkEarnsCompOff() throws Exception {
        Person e = person(empRole);
        Person fin = person(finRole);
        LocalDate today = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
        LocalDate day = today.minusDays(1);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.minusDays(1);
        if (!day.toString().substring(0, 7).equals(today.toString().substring(0, 7))) return; // needs an open month
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB')", UUID.randomUUID(), e.id(),
                Timestamp.from(AttendanceEngine.atLocal(day, "09:30", "Asia/Kolkata")));
        UUID reg = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_out, status) VALUES (?, ?, ?, 'MISSED_CHECKOUT', ?, 'PENDING')",
                reg, e.id(), Date.valueOf(day), Timestamp.from(AttendanceEngine.atLocal(day, "18:45", "Asia/Kolkata")));
        mvc.perform(get("/api/approvals?tab=regularizations").cookie(fin.cookie())).andExpect(jsonPath("$.data.regularizationCards[?(@.id == '" + reg + "')].detail")
                .value(org.hamcrest.Matchers.hasItem("Checked in 9:30 AM · asks to set check-out to 6:45 PM")));
        mvc.perform(write(put("/api/attendance/regularizations/" + reg + "/decision")).cookie(fin.cookie()).content("{\"approve\":true}"))
                .andExpect(jsonPath("$.msg").value("Approved Person " + e.code() + "’s missed check-out regularization"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'REGULARIZATION' AND kind = 'OUT'", Integer.class, e.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT decided_role FROM regularization_requests WHERE id = ?", String.class, reg)).isEqualTo("Fm");

        // weekend work of 5h → ½ day comp-off
        LocalDate sat = today.minusDays(1);
        while (AttendanceEngine.dowOf(sat) != 6) sat = sat.minusDays(1);
        if (!sat.toString().substring(0, 7).equals(today.toString().substring(0, 7))) return;
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), e.id(), Timestamp.from(AttendanceEngine.atLocal(sat, "10:00", "Asia/Kolkata")),
                UUID.randomUUID(), e.id(), Timestamp.from(AttendanceEngine.atLocal(sat, "15:00", "Asia/Kolkata")));
        UUID hw = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, status) VALUES (?, ?, ?, 'HOLIDAY_WORK', 'PENDING')", hw, e.id(), Date.valueOf(sat));
        mvc.perform(write(put("/api/attendance/regularizations/" + hw + "/decision")).cookie(fin.cookie()).content("{\"approve\":true}"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.endsWith("comp-off request · 0.5 comp-off credited")));
        assertThat(jdbc.queryForObject("SELECT expires_on FROM leave_ledger WHERE ref_id = ?", Date.class, hw.toString()).toLocalDate()).isEqualTo(sat.plusDays(60));
    }

    @Test
    void earnedLeaveIsNotAvailableDuringProbation() throws Exception {
        Person e = person(empRole);
        credit(e.id(), "EL", 5);
        LocalDate mon = nextMonday();
        jdbc.update("UPDATE employees SET status = 'PROBATION' WHERE id = ?", e.id());
        mvc.perform(preview(e, "EL", mon, mon))
                .andExpect(jsonPath("$.data.error").value("Earned leave isn't available during probation. Choose another leave type."));
        mvc.perform(write(post("/api/leave/requests")).cookie(e.cookie()).content("{\"type\":\"EL\",\"from\":\"" + mon + "\",\"to\":\"" + mon + "\",\"part\":\"FULL\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/leave").cookie(e.cookie())).andExpect(jsonPath("$.data.types[?(@.code=='EL')].lockedReason").value("Earned leave isn't available during probation. Choose another leave type."));
        // once permanent, the same request is fine
        jdbc.update("UPDATE employees SET status = 'ACTIVE' WHERE id = ?", e.id());
        mvc.perform(preview(e, "EL", mon, mon)).andExpect(jsonPath("$.data.error").doesNotExist());
    }
}
