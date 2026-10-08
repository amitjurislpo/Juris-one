package com.jurislpo.jurisone.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Home, overview, My team and the audit log through the API. */
class DashboardApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID empRole, adminRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("dash-employee", "self", "dir");
        adminRole = data.role("dash-admin", "self", "dir", "roles.manage", "people.view");
        UUID shift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_DAY'", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (shift == null) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
    }

    static int seq = 9000;

    record Person(UUID id, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String c = "JC-" + (++seq) + (int) (Math.random() * 1000);
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role, PW);
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = 'IN_DAY') WHERE id = ?", id);
        String set = mvc.perform(post("/api/auth/session").header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON)
                .content("{\"identifier\":\"" + c.toLowerCase() + "@example.com\",\"password\":\"" + PW + "\"}")).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, new Cookie("jo_at", m.group(1)));
    }

    @Test
    void homeShowsTheEmployeesDayAndCards() throws Exception {
        Person emp = person(empRole);
        mvc.perform(get("/api/home").cookie(emp.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tracked").value(true))
                .andExpect(jsonPath("$.data.shiftText").value(org.hamcrest.Matchers.startsWith("Day shift · 9:30 AM – 6:30 PM IST")))
                .andExpect(jsonPath("$.data.ticks.length()").value(4))
                .andExpect(jsonPath("$.data.ticks[0]").value("9:30 AM"))
                .andExpect(jsonPath("$.data.ticks[3]").value("6:30 PM"))
                .andExpect(jsonPath("$.data.on").value(false))
                .andExpect(jsonPath("$.data.cards.myLeaves").isArray())
                .andExpect(jsonPath("$.data.cards.team").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.cards.payslip.ready").value(false));
    }

    @Test
    void managersSeeTheirTeamAndOthersDont() throws Exception {
        Person boss = person(empRole), report = person(empRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", boss.id(), report.id());
        mvc.perform(get("/api/team").cookie(boss.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.rows[0].shift").value("Day · 9:30 AM – 6:30 PM IST"))
                .andExpect(jsonPath("$.data.days.length()").value(5))
                .andExpect(jsonPath("$.data.away[0].cells.length()").value(5));
        mvc.perform(get("/api/team").cookie(report.cookie())).andExpect(status().isForbidden());
    }

    @Test
    void overviewAndAuditAreForAdminsAndPayIsRedacted() throws Exception {
        Person emp = person(empRole), admin = person(adminRole);
        mvc.perform(get("/api/overview").cookie(emp.cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").cookie(emp.cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/overview").cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head").isNumber()).andExpect(jsonPath("$.data.payroll.month").isString());

        jdbc.update("INSERT INTO audit_log (id, actor_id, action, entity, entity_id, after, at, business_id) VALUES (?, ?, 'payroll.close', 'payroll_run', 'x', '{\"net\": 123}'::jsonb, now(), ?)",
                UUID.randomUUID(), admin.id(), data.business());
        jdbc.update("INSERT INTO audit_log (id, actor_id, action, entity, entity_id, after, at, business_id) VALUES (?, ?, 'employee.update', 'employee', ?, '{\"name\": \"A_B\"}'::jsonb, now(), ?)",
                UUID.randomUUID(), admin.id(), emp.id().toString(), data.business());
        // no salary permission → payroll payloads are hidden
        mvc.perform(get("/api/audit").param("ev", "payroll").param("actor", admin.id().toString()).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.rows[0].redacted").value(true))
                .andExpect(jsonPath("$.data.rows[0].after").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.rows[0].at").value(org.hamcrest.Matchers.matchesPattern("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")));
        // subject is named; "_" in the search is literal
        mvc.perform(get("/api/audit").param("ev", "people").param("actor", admin.id().toString()).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].subject").value(org.hamcrest.Matchers.startsWith("Person ")))
                .andExpect(jsonPath("$.data.rows[0].after.name").value("A_B"));
        mvc.perform(get("/api/audit").param("q", "employee_update").param("actor", admin.id().toString()).cookie(admin.cookie()))
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
