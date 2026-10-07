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
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.jobs.JobsService;
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

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
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
        nightShift = jdbc.query("SELECT id FROM shifts WHERE code = 'IN_NIGHT'", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (nightShift == null) {
            nightShift = UUID.randomUUID();
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_NIGHT', 'India Night', '19:30', '04:30', 15, 'IN')", nightShift);
        }
        jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES ('attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (key) DO NOTHING");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int seq = 5000;

    String code() {
        return "JC-" + (++seq) + (int) (Math.random() * 1000);
    }

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String c = code();
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role, PW);
        jdbc.update("UPDATE employees SET shift_id = ? WHERE id = ?", nightShift, id);
        String set = mvc.perform(write(post("/api/auth/login")).content("{\"identifier\":\"" + c + "\",\"password\":\"" + PW + "\"}"))
                .andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_session=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, c, new Cookie("jo_session", m.group(1)));
    }

    @Test
    void checkInAndOutFollowTheButtonTheUserSaw() throws Exception {
        Person p = person(empRole);
        mvc.perform(write(post("/api/attendance/punch")).cookie(p.cookie()).content("{\"expect\":\"OUT\"}")).andExpect(jsonPath("$.error").value("You're not checked in."));
        mvc.perform(write(post("/api/attendance/punch")).cookie(p.cookie()).content("{\"expect\":\"IN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Checked in. Have a productive shift.")).andExpect(jsonPath("$.data.kind").value("IN"));
        mvc.perform(write(post("/api/attendance/punch")).cookie(p.cookie()).content("{\"expect\":\"IN\"}")).andExpect(jsonPath("$.error").value("You're already checked in."));
        mvc.perform(write(post("/api/attendance/punch")).cookie(p.cookie()).content("{\"expect\":\"OUT\"}")).andExpect(jsonPath("$.error").value("Just recorded — give it a few seconds."));
        jdbc.update("UPDATE punches SET at = at - interval '2 hours' WHERE employee_id = ?", p.id());
        mvc.perform(write(post("/api/attendance/punch")).cookie(p.cookie()).content("{\"expect\":\"OUT\"}"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.matchesPattern("Checked out — 2h 0[01]m recorded today")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'WEB' AND ip IS NULL", Integer.class, p.id())).isEqualTo(2);
    }

    @Test
    void myAttendanceComputesNightShiftsAndRegularizationsAreChecked() throws Exception {
        Person p = person(empRole);
        // night shift of Mon 14 Sep 2026: in 19:40 IST, out 02:00 next day → 6h20m = half day, late by 10 min
        LocalDate d = LocalDate.parse("2026-09-14");
        Instant in = AttendanceEngine.atLocal(d, "19:50", "Asia/Kolkata"), out = AttendanceEngine.atLocal(d.plusDays(1), "02:00", "Asia/Kolkata");
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), p.id(), Timestamp.from(in), UUID.randomUUID(), p.id(), Timestamp.from(out));
        JsonNode page = json.readTree(mvc.perform(get("/api/attendance/me?m=2026-09&d=2026-09-14").cookie(p.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(page.get("tracked").asBoolean()).isTrue();
        assertThat(page.get("initialSel").asText()).isEqualTo("2026-09-14");
        JsonNode day = null;
        for (JsonNode x : page.get("days")) if (x.get("date").asText().equals("2026-09-14")) day = x;
        assertThat(day.get("status").asText()).isEqualTo("HALF_DAY");
        assertThat(day.get("late").asBoolean()).isTrue();
        assertThat(day.get("firstIn").asText()).isEqualTo("7:50 PM");
        assertThat(day.get("lastOut").asText()).isEqualTo("2:00 AM");
        // September 2026 locks on 1 Oct 10:00 IST
        mvc.perform(write(post("/api/attendance/regularizations")).cookie(p.cookie())
                .content("{\"date\":\"2026-09-14\",\"kind\":\"LATE_CHECKIN\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"traffic\"}"))
                .andExpect(jsonPath("$.error").value("Attendance for this month is locked. Ask Finance to adjust LOP in payroll."));
    }

    @Test
    void regularizationInAnOpenMonth() throws Exception {
        Person p = person(empRole);
        LocalDate today = AttendanceEngine.localParts(Instant.now(), "Asia/Kolkata").date();
        // a working day earlier this month with no punches → absent (LOP) once the day is over
        LocalDate day = today.withDayOfMonth(1);
        while (AttendanceEngine.dowOf(day) == 0 || AttendanceEngine.dowOf(day) == 6) day = day.plusDays(1);
        if (!day.isBefore(today.minusDays(1))) return; // too early in the month for a finished working day
        String body = "{\"date\":\"" + day + "\",\"kind\":\"ABSENT\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"was at client\"}";
        mvc.perform(write(post("/api/attendance/regularizations")).cookie(p.cookie()).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Regularization sent — HR, Finance or Admin can approve it"));
        mvc.perform(write(post("/api/attendance/regularizations")).cookie(p.cookie()).content(body)).andExpect(jsonPath("$.error").value("You already have a pending request for this day."));
        mvc.perform(write(post("/api/attendance/regularizations")).cookie(p.cookie())
                .content("{\"date\":\"" + day + "\",\"kind\":\"LATE_CHECKIN\",\"proposedIn\":\"\",\"proposedOut\":\"\",\"reason\":\"\"}"))
                .andExpect(jsonPath("$.error").value("There's no late mark on this day."));
        String id = jdbc.queryForObject("SELECT id::text FROM regularization_requests WHERE employee_id = ?", String.class, p.id());
        mvc.perform(write(delete("/api/attendance/regularizations/" + id)).cookie(p.cookie())).andExpect(jsonPath("$.msg").value("Request withdrawn"));
        mvc.perform(write(delete("/api/attendance/regularizations/" + id)).cookie(p.cookie())).andExpect(jsonPath("$.error").value("Only pending requests can be withdrawn."));
    }

    @Test
    void registerShowsZonesAndExportsCsv() throws Exception {
        Person e = person(empRole);
        Person hr = person(hrRole);
        LocalDate d = LocalDate.parse("2026-09-16");
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                UUID.randomUUID(), e.id(), Timestamp.from(AttendanceEngine.atLocal(d, "19:31", "Asia/Kolkata")),
                UUID.randomUUID(), e.id(), Timestamp.from(AttendanceEngine.atLocal(d.plusDays(1), "04:40", "Asia/Kolkata")));
        mvc.perform(get("/api/register?day=2026-09-16&q=" + e.code()).cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].st").value("Present"))
                .andExpect(jsonPath("$.data.rows[0].inn.text").value("7:31 PM IST"))
                .andExpect(jsonPath("$.data.rows[0].out.text").value("4:40 AM IST (+1)"));
        mvc.perform(get("/api/register?day=2026-09-16&tz=America/New_York&q=" + e.code()).cookie(hr.cookie()))
                .andExpect(jsonPath("$.data.rows[0].inn.text").value("10:01 AM EDT")).andExpect(jsonPath("$.data.rows[0].inn.local").value("7:31 PM IST"));
        mvc.perform(get("/api/attendance/register?view=month&ym=2026-09&q=" + e.code()).cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("﻿Employee ID,Name,Department,Team,2026-09-01,")));
        mvc.perform(get("/api/attendance/register").cookie(e.cookie())).andExpect(status().isForbidden());
        mvc.perform(get("/api/attendance/register")).andExpect(status().isForbidden());
    }

    @Test
    void jobsAreIdempotentAndLockMonths() {
        Map<String, Object> first = jobs.runJobs(Instant.now());
        assertThat(first).containsKeys("status", "lock", "finalize", "yearEnd", "accrual", "compOff");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs WHERE name = 'attendance.lock' AND key = '2026-04'", Integer.class)).isEqualTo(1);
        int ledger = jdbc.queryForObject("SELECT count(*) FROM leave_ledger", Integer.class);
        Map<String, Object> second = jobs.runJobs(Instant.now());
        assertThat(((Map<?, ?>) second.get("lock")).get("locked")).asList().isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger", Integer.class)).isEqualTo(ledger);
        // the advisory lock was released (taken and freed on the same connection)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = 74202", Integer.class)).isZero();
    }
}
