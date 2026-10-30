package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.service.JobsService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

/** Check-in/out, regularization requests, the register and the jobs through the API. */
class AttendanceApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JobsService jobs;
    TestData data;
    UUID empRole, hrRole, nightShift;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage");
        nightShift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_NIGHT'", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (nightShift == null) {
            nightShift = UUID.randomUUID();
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_NIGHT', 'India Night', '19:30', '04:30', 15, 'IN')", nightShift);
        }
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (business_id, key) DO NOTHING",
                data.business());
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 5000;

    String code() {
        return "JC-" + (++sequence) + (int) (Math.random() * 1000);
    }

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String code = code();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        jdbc.update("UPDATE employees SET shift_id = ? WHERE id = ?", nightShift, id);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com")
                .andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, new Cookie("jo_at", matcher.group(1)));
    }

    @Test
    void checkInAndOutFollowTheButtonTheUserSaw() throws Exception {
        Person person = person(empRole);
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"OUT\"}")).andExpect(jsonPath("$.detail").value("You're not checked in."));
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Checked in. Have a productive shift.")).andExpect(jsonPath("$.data.kind").value("IN"));
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}")).andExpect(jsonPath("$.detail").value("You're already checked in."));
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"OUT\"}")).andExpect(jsonPath("$.detail").value("Just recorded — give it a few seconds."));
        jdbc.update("UPDATE punches SET at = at - interval '2 hours' WHERE employee_id = ?", person.id());
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"OUT\"}"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.matchesPattern("Checked out — 2h 0[01]m recorded today")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'WEB' AND ip IS NULL", Integer.class, person.id())).isEqualTo(2);
    }

    @Test
    void myAttendanceComputesNightShiftsAndRegularizationsAreChecked() throws Exception {
        Person person = person(empRole);
        // night shift of Mon 14 Sep 2026: in 19:40 IST, out 02:00 next day → 6h20m = half day, late by 10 min
        LocalDate shiftDay = LocalDate.parse("2026-09-14");
        Instant punchIn = AttendanceEngine.atLocal(shiftDay, "19:50", "Asia/Kolkata"), out = AttendanceEngine.atLocal(shiftDay.plusDays(1), "02:00", "Asia/Kolkata");
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), person.id(), Timestamp.from(punchIn), UUID.randomUUID(), person.id(), Timestamp.from(out));
        JsonNode page = json.readTree(mockMvc.perform(get("/api/v1/attendance/mine?month=2026-09&date=2026-09-14").cookie(person.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(page.get("tracked").asBoolean()).isTrue();
        assertThat(page.get("selectedDate").asText()).isEqualTo("2026-09-14");
        JsonNode day = null;
        for (JsonNode dayNode : page.get("days")) if (dayNode.get("date").asText().equals("2026-09-14")) day = dayNode;
        assertThat(day.get("status").asText()).isEqualTo("HALF_DAY");
        assertThat(day.get("late").asBoolean()).isTrue();
        assertThat(day.get("firstIn").asText()).isEqualTo("7:50 PM");
        assertThat(day.get("lastOut").asText()).isEqualTo("2:00 AM");
        // September 2026 locks on 1 Oct 10:00 IST
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(person.cookie())
                .content("{\"date\":\"2026-09-14\",\"kind\":\"LATE_CHECKIN\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"traffic\"}"))
                .andExpect(jsonPath("$.detail").value("Attendance for this month is locked. Ask Finance to adjust LOP in payroll."));
    }

    @Test
    void regularizationInAnOpenMonth() throws Exception {
        Person person = person(empRole);
        LocalDate today = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
        // a working day earlier this month with no punches → absent (LOP) once the day is over
        LocalDate day = today.withDayOfMonth(1);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.plusDays(1);
        if (!day.isBefore(today.minusDays(1))) return; // too early in the month for a finished working day
        String body = "{\"date\":\"" + day + "\",\"kind\":\"ABSENT\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"was at client\"}";
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(person.cookie()).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Regularization sent — HR, Finance or Admin can approve it"));
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(person.cookie()).content(body)).andExpect(jsonPath("$.detail").value("You already have a pending request for this day."));
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(person.cookie())
                .content("{\"date\":\"" + day + "\",\"kind\":\"LATE_CHECKIN\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"\"}"))
                .andExpect(jsonPath("$.detail").value("There's no late mark on this day."));
        String id = jdbc.queryForObject("SELECT id::text FROM regularization_requests WHERE employee_id = ?", String.class, person.id());
        mockMvc.perform(write(delete("/api/v1/attendance/regularizations/" + id)).cookie(person.cookie())).andExpect(jsonPath("$.message").value("Request withdrawn"));
        mockMvc.perform(write(delete("/api/v1/attendance/regularizations/" + id)).cookie(person.cookie())).andExpect(jsonPath("$.detail").value("Only pending requests can be withdrawn."));
    }

    @Test
    void registerShowsZonesAndExportsCsv() throws Exception {
        Person employee = person(empRole);
        Person hrPerson = person(hrRole);
        LocalDate day = LocalDate.parse("2026-09-16");
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), employee.id(), Timestamp.from(AttendanceEngine.atLocal(day, "19:31", "Asia/Kolkata")),
                UUID.randomUUID(), employee.id(), Timestamp.from(AttendanceEngine.atLocal(day.plusDays(1), "04:40", "Asia/Kolkata")));
        mockMvc.perform(get("/api/v1/attendance/register?date=2026-09-16&search=" + employee.code()).cookie(hrPerson.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("Present"))
                .andExpect(jsonPath("$.data.rows[0].checkIn.text").value("7:31 PM IST"))
                .andExpect(jsonPath("$.data.rows[0].checkOut.text").value("4:40 AM IST (+1)"));
        mockMvc.perform(get("/api/v1/attendance/register?date=2026-09-16&timeZone=America/New_York&search=" + employee.code()).cookie(hrPerson.cookie()))
                .andExpect(jsonPath("$.data.rows[0].checkIn.text").value("10:01 AM EDT")).andExpect(jsonPath("$.data.rows[0].checkIn.local").value("7:31 PM IST"));
        mockMvc.perform(get("/api/v1/attendance/register/export?view=month&month=2026-09&search=" + employee.code()).cookie(hrPerson.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("﻿Employee ID,Name,Department,Team,2026-09-01,")));
        mockMvc.perform(get("/api/v1/attendance/register").cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attendance/register")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attendance/register/export").cookie(employee.cookie())).andExpect(status().isForbidden());
    }

    @Test
    void jobsAreIdempotentAndLockMonths() {
        // jobs run business by business; results are keyed by business code
        Map<String, Object> first = jobs.runJobs(Instant.now());
        @SuppressWarnings("unchecked")
        Map<String, Object> mine = (Map<String, Object>) first.get(TestData.DEFAULT_BUSINESS);
        assertThat(mine).containsKeys("status", "lock", "finalize", "yearEnd", "accrual", "compOff");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock' AND key = '2026-04' AND business_id = ?", Integer.class,
                data.business())).isEqualTo(1);
        int ledger = jdbc.queryForObject("SELECT count(*) FROM leave_ledger", Integer.class);
        Map<String, Object> second = jobs.runJobs(Instant.now());
        Map<?, ?> again = (Map<?, ?>) second.get(TestData.DEFAULT_BUSINESS);
        assertThat(((Map<?, ?>) again.get("lock")).get("locked")).asList().isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger", Integer.class)).isEqualTo(ledger);
        // the advisory lock was released (taken and freed on the same connection)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = 74202", Integer.class)).isZero();
    }
}
