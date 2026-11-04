package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The Attendance page's summary (/api/v1/attendance/summary) and its CSV / PDF: shape, filters, hostile input, own data only. */
class AttendanceSummaryApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        employeeRole = data.role("employee", "self", "dir");
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (business_id, key) DO NOTHING",
                data.business());
    }

    static int sequence = 7000;

    record Person(UUID id, Cookie cookie) {}

    Person person(TestData testData, UUID role) throws Exception {
        String code = "JS-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = testData.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    void punch(UUID employeeId, LocalDate day, String in, String out) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), employeeId, Timestamp.from(AttendanceEngine.atLocal(day, in, "Asia/Kolkata")),
                UUID.randomUUID(), employeeId, Timestamp.from(AttendanceEngine.atLocal(day, out, "Asia/Kolkata")));
    }

    JsonNode summary(Person person, String query) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/attendance/summary" + query).cookie(person.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    @Test
    void weekRowsAndCountersFollowTheEngine() throws Exception {
        Person person = person(data, employeeRole);
        // Mon 14 Sep 2026 (default day shift 09:30-18:30, grace 15): in 09:50 (late by 20), out 18:40 -> full day
        punch(person.id(), LocalDate.parse("2026-09-14"), "09:50", "18:40");
        JsonNode summary = summary(person, "?from=2026-09-14&to=2026-09-20&view=tabular");
        assertThat(summary.get("tracked").asBoolean()).isTrue();
        assertThat(summary.get("view").asText()).isEqualTo("tabular");
        assertThat(summary.get("days")).hasSize(7);
        assertThat(summary.get("previousFrom").asText()).isEqualTo("2026-09-07");
        assertThat(summary.get("nextFrom").asText()).isEqualTo("2026-09-21");
        JsonNode monday = summary.get("days").get(0);
        assertThat(monday.get("status").asText()).isEqualTo("PRESENT");
        assertThat(monday.get("firstIn").asText()).isEqualTo("9:50 AM");
        assertThat(monday.get("lateMinutes").asInt()).isEqualTo(20);
        assertThat(monday.get("earlyMinutes").asInt()).isZero();
        JsonNode counters = summary.get("counters");
        assertThat(counters.get("present").asDouble()).isEqualTo(1.0);
        assertThat(counters.get("lateCount").asInt()).isEqualTo(1);
        assertThat(counters.get("weekends").asDouble()).isEqualTo(2.0);
        assertThat(counters.get("onDuty").asDouble()).isZero();
        assertThat(counters.get("hours").get("present").asDouble()).isGreaterThan(8.0);
    }

    @Test
    void statusFilterKeepsCountersOfTheWholePeriod() throws Exception {
        Person person = person(data, employeeRole);
        JsonNode filtered = summary(person, "?from=2026-09-14&to=2026-09-20&status=WEEKEND");
        assertThat(filtered.get("days")).hasSize(2);
        assertThat(filtered.get("counters").get("weekends").asDouble()).isEqualTo(2.0);
        assertThat(summary(person, "?from=2026-09-14&to=2026-09-20&status=HOLIDAY").get("days")).hasSize(0);
    }

    @Test
    void defaultsAreTheCurrentWeekAndTheCurrentMonthForTheCalendar() throws Exception {
        Person person = person(data, employeeRole);
        assertThat(summary(person, "").get("days")).hasSize(7);
        JsonNode month = summary(person, "?view=calendar");
        assertThat(month.get("days").size()).isBetween(28, 31);
        assertThat(month.get("shift").get("name").asText()).isNotBlank();
    }

    @Test
    void hostileOrBrokenInputIsRefused() throws Exception {
        Person person = person(data, employeeRole);
        String[] bad = {"?from=2026-09-14&to=2026-09-01", "?from=2026-02-30&to=2026-03-01", "?from=2026-01-01&to=2026-12-31", "?from=x%27%3B%20DROP%20TABLE%20punches%3B--&to=2026-09-01",
                "?from=2026-09-01", "?view=%3Cscript%3E", "?view=list&status=PRESENT%27%20OR%201%3D1", "?from=1900-01-01&to=1900-01-07", "?from=2026-09-01&to=2099-01-01"};
        for (String query : bad) {
            mockMvc.perform(get("/api/v1/attendance/summary" + query).cookie(person.cookie())).andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/v1/attendance/summary/export" + query).cookie(person.cookie())).andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/attendance/summary/export?format=exe").cookie(person.cookie())).andExpect(status().isBadRequest());
    }

    @Test
    void requiresSignInAndNeverShowsAnotherPersonsOrBusinessesData() throws Exception {
        mockMvc.perform(get("/api/v1/attendance/summary")).andExpect(status().isUnauthorized());
        Person mine = person(data, employeeRole);
        TestData otherBusiness = new TestData(jdbc).inBusiness("other-summary-business");
        Person foreigner = person(otherBusiness, otherBusiness.role("employee", "self", "dir"));
        LocalDate day = LocalDate.parse("2026-09-15");
        punch(foreigner.id(), day, "09:30", "18:30");
        // an employeeId the client sends is ignored: the signed-in user's own days are returned
        JsonNode summary = summary(mine, "?from=2026-09-15&to=2026-09-15&employeeId=" + foreigner.id());
        assertThat(summary.get("days").get(0).get("firstIn").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/attendance/summary?from=2026-09-15&to=2026-09-15").cookie(foreigner.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days[0].firstIn").value("9:30 AM"));
    }

    @Test
    void exportsCsvAndPdfOfTheSameRows() throws Exception {
        Person person = person(data, employeeRole);
        punch(person.id(), LocalDate.parse("2026-09-14"), "09:30", "18:30");
        byte[] csv = mockMvc.perform(get("/api/v1/attendance/summary/export?from=2026-09-14&to=2026-09-20").cookie(person.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("Date,Day,Shift,Check-in,Check-out,Hours,Status,Late by (min),Early by (min)").contains("2026-09-14,Monday");
        assertThat(text.split("\r\n")).hasSize(8);
        byte[] pdf = mockMvc.perform(get("/api/v1/attendance/summary/export?format=pdf&from=2026-09-14&to=2026-09-20").cookie(person.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }
}
