package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The Team view of the Attendance page: Admin / HR see everyone, a manager sees only direct reports, everyone else is refused. */
class TeamRegisterApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole, hrRole;

    record Person(UUID id, String code, Cookie cookie) {}

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (business_id, key) DO NOTHING",
                data.business());
    }

    Person person(UUID role) throws Exception {
        String code = "TR-" + TestData.letters(8).toUpperCase();
        String email = code.toLowerCase() + "@example.com";
        UUID id = data.employee(code, email, role);
        String set = SignIn.session(mockMvc, email).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    List<String> codes(Person viewer, String query) throws Exception {
        JsonNode data = json.readTree(mockMvc.perform(get("/api/v1/attendance/team-register?date=2026-09-16" + query).cookie(viewer.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        List<String> codes = new ArrayList<>();
        data.get("rows").forEach(row -> codes.add(row.get("code").asText()));
        return codes;
    }

    @Test
    void aManagerSeesOnlyDirectReportsAndHrSeesEveryone() throws Exception {
        Person manager = person(employeeRole);
        Person report = person(employeeRole);
        Person stranger = person(employeeRole);
        Person hr = person(hrRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), report.id());

        assertThat(codes(manager, "")).containsExactly(report.code());
        // the scope is the signed-in manager's: a search, a department or a manager id sent by the client never widens it
        assertThat(codes(manager, "&search=" + stranger.code())).isEmpty();
        assertThat(codes(manager, "&managerId=" + stranger.id() + "&reportsOf=" + stranger.id())).containsExactly(report.code());
        assertThat(codes(hr, "")).contains(manager.code(), report.code(), stranger.code(), hr.code());
        // the monthly grid is scoped the same way
        assertThat(codes(manager, "&view=month&month=2026-09")).containsExactly(report.code());
    }

    @Test
    void everyoneElseIsRefused() throws Exception {
        Person employee = person(employeeRole);
        mockMvc.perform(get("/api/v1/attendance/team-register").cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attendance/team-register")).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagerWhoseReportsLeftSeesNothingAndIsRefused() throws Exception {
        Person manager = person(employeeRole);
        Person report = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), report.id());
        assertThat(codes(manager, "")).containsExactly(report.code());
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE id = ?", report.id());
        mockMvc.perform(get("/api/v1/attendance/team-register").cookie(manager.cookie())).andExpect(status().isForbidden());
    }
}
