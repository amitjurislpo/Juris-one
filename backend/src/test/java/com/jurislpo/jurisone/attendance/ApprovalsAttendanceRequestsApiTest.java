package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
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

/**
 * The Attendance queue of Approvals also holds On Duty and Remote Work requests: the Admin (who has no attendance page) sees and decides the requests of an HR
 * person who has no reporting manager; people who may not decide them see nothing; another business sees nothing.
 */
class ApprovalsAttendanceRequestsApiTest extends IntegrationTest {

    static final String ON_DUTY = "/api/v1/attendance/on-duty-requests";
    static final String REMOTE = "/api/v1/attendance/remote-work-requests";
    static final String APPROVALS = "/api/v1/approvals?tab=regularizations";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole, hrRole, adminRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("approvals-attendance-test");
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        adminRole = data.role("owner", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "roles.manage"); // no "self": the Admin has no attendance page
        jdbc.update("""
            INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{"trackingStartDate":"2026-04-01","lockDayOfNextMonth":28}', now())
            ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value""", data.business());
        jdbc.update("DELETE FROM settings WHERE business_id = ? AND key = 'attendance.regularization'", data.business());
        jdbc.update("DELETE FROM on_duty_requests WHERE business_id = ?", data.business());
        jdbc.update("DELETE FROM remote_work_requests WHERE employee_id IN (SELECT id FROM employees WHERE business_id = ?)", data.business());
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 7000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(TestData in, UUID role) throws Exception {
        String code = "JA-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = in.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    Person person(UUID role) throws Exception {
        return person(data, role);
    }

    static LocalDate workday() {
        LocalDate day = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date().minusDays(2);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.minusDays(1);
        return day;
    }

    String idOf(String table, Person person) {
        return jdbc.queryForObject("SELECT id::text FROM " + table + " WHERE employee_id = ? ORDER BY created_at DESC LIMIT 1", String.class, person.id());
    }

    String statusOf(String table, String id) {
        return jdbc.queryForObject("SELECT status::text FROM " + table + " WHERE id = ?::uuid", String.class, id);
    }

    JsonNode fetch(String url, Person person) throws Exception {
        return json.readTree(mockMvc.perform(get(url).cookie(person.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    static boolean hasCard(JsonNode cards, String id) {
        for (JsonNode card : cards) if (id.equals(card.get("id").asText())) return true;
        return false;
    }

    void raise(Person person, LocalDate day) throws Exception {
        mockMvc.perform(write(post(ON_DUTY)).cookie(person.cookie()).content("{\"date\":\"" + day + "\",\"part\":\"FULL\",\"location\":\"Client\",\"reason\":\"Client visit\"}")).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(person.cookie()).content("{\"fromDate\":\"" + day + "\",\"toDate\":\"" + day + "\",\"reason\":\"Internet repair\"}")).andExpect(status().isCreated());
    }

    @Test
    void theAdminSeesAndDecidesTheOnDutyAndRemoteWorkRequestsOfHrWithoutAManager() throws Exception {
        Person admin = person(adminRole), hr = person(hrRole), otherHr = person(hrRole);
        LocalDate day = workday();
        raise(hr, day);
        String onDutyId = idOf("on_duty_requests", hr), remoteId = idOf("remote_work_requests", hr);

        // the Admin may read the team list of Remote Work (it answered 403 before) and sees both requests as cards in the Attendance queue
        assertThat(hasCard(fetch(REMOTE + "?scope=team&status=PENDING", admin).get("items"), remoteId)).isTrue();
        JsonNode page = fetch(APPROVALS, admin);
        assertThat(hasCard(page.get("onDutyCards"), onDutyId)).isTrue();
        assertThat(hasCard(page.get("remoteWorkCards"), remoteId)).isTrue();
        assertThat(page.get("onDutyCount").asInt()).isEqualTo(1);
        assertThat(page.get("remoteWorkCount").asInt()).isEqualTo(1);
        // the pending badge counts them too
        assertThat(fetch("/api/v1/shell", admin).at("/badges/approvals").asInt()).isGreaterThanOrEqualTo(2);

        // another HR person may not decide them and does not see them as cards
        JsonNode otherPage = fetch(APPROVALS, otherHr);
        assertThat(hasCard(otherPage.get("onDutyCards"), onDutyId)).isFalse();
        assertThat(hasCard(otherPage.get("remoteWorkCards"), remoteId)).isFalse();
        mockMvc.perform(write(put(ON_DUTY + "/" + onDutyId + "/decision")).cookie(otherHr.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REMOTE + "/" + remoteId + "/decision")).cookie(otherHr.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());

        // the Admin approves both
        mockMvc.perform(write(put(ON_DUTY + "/" + onDutyId + "/decision")).cookie(admin.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
        mockMvc.perform(write(put(REMOTE + "/" + remoteId + "/decision")).cookie(admin.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
        assertThat(statusOf("on_duty_requests", onDutyId)).isEqualTo("APPROVED");
        assertThat(statusOf("remote_work_requests", remoteId)).isEqualTo("APPROVED");
        JsonNode after = fetch(APPROVALS, admin);
        assertThat(after.get("onDutyCards")).isEmpty();
        assertThat(after.get("remoteWorkCards")).isEmpty();
    }

    @Test
    void peopleWhoMayNotDecideAreRefusedAndOtherBusinessesSeeNothing() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole), manager = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), employee.id());
        LocalDate day = workday();
        raise(hr, day);
        String remoteId = idOf("remote_work_requests", hr);

        // an employee and a manager of ordinary employees are refused the team list, the approvals page, the details and the decision
        mockMvc.perform(get(REMOTE + "?scope=team").cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/approvals").cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get(REMOTE + "/" + remoteId).cookie(employee.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(REMOTE + "/" + remoteId + "/decision")).cookie(employee.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/approvals").cookie(manager.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REMOTE + "/" + remoteId + "/decision")).cookie(manager.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        assertThat(statusOf("remote_work_requests", remoteId)).isEqualTo("PENDING");

        // the Admin of another business sees none of it and cannot decide it
        TestData other = new TestData(jdbc).inBusiness("approvals-attendance-other");
        UUID otherAdminRole = other.role("owner", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "roles.manage");
        Person otherAdmin = person(other, otherAdminRole);
        JsonNode page = fetch(APPROVALS, otherAdmin);
        assertThat(page.get("onDutyCards")).isEmpty();
        assertThat(page.get("remoteWorkCards")).isEmpty();
        assertThat(fetch(REMOTE + "?scope=team", otherAdmin).get("items")).isEmpty();
        mockMvc.perform(get(REMOTE + "/" + remoteId).cookie(otherAdmin.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(REMOTE + "/" + remoteId + "/decision")).cookie(otherAdmin.cookie()).content("{\"approve\":true}")).andExpect(status().is4xxClientError());
        assertThat(statusOf("remote_work_requests", remoteId)).isEqualTo("PENDING");
    }
}
