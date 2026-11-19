package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
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
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Attendance info: the day detail, the extra fields of the month, the employee's access card number and punch mode, and the business punch mode that
 * decides whether the web check-in is allowed. The days used are fixed days of September and August 2026 (long over), so nothing depends on today's date.
 */
class AttendanceInfoApiTest extends IntegrationTest {

    static final String ZONE = "Asia/Kolkata";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix;
    UUID employeeRole, hrRole, adminRole, departmentId, shiftId;
    final List<UUID> addedShifts = new java.util.ArrayList<>();
    Person hr, admin;

    record Person(UUID id, String code, String email, Cookie cookie) {}

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("info-" + suffix);
        shiftId = UUID.randomUUID();
        jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, ?, 'Info day', '09:30', '18:30', 15, 'IN')", shiftId, "INFO" + suffix.toUpperCase());
        for (String code : new String[] {"IN_DAY", "IN_NIGHT", "US_DAY"}) { // the shifts the employee form can pick: added only when the database has none, and removed again afterwards
            UUID added = UUID.randomUUID();
            if (jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    added, code, code.startsWith("IN_D") ? "India Day" : code.startsWith("IN_N") ? "India Night" : "US Day", "09:30", "18:30", code.startsWith("US") ? "US" : "IN", code) > 0) addedShifts.add(added);
        }
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "people.view", "people.add");
        adminRole = data.role("owner", "self", "dir", "roles.manage", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, employeeRole);
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Ops " + suffix, data.business());
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{\"trackingStartDate\":\"2026-04-01\"}', now()) ON CONFLICT (business_id, key) DO NOTHING",
                data.business());
        hr = person(data, hrRole, "hr");
        admin = person(data, adminRole, "admin");
    }

    Person person(TestData in, UUID role, String prefix) throws Exception {
        String code = prefix.toUpperCase() + TestData.letters(5).toUpperCase();
        String email = prefix + "." + TestData.letters(8) + "@example.com";
        UUID id = in.employee(code, email, role);
        jdbc.update("UPDATE employees SET shift_id = ? WHERE id = ?", shiftId, id);
        String set = SignIn.session(mockMvc, email).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, code, email, new Cookie("jo_at", matcher.group(1)));
    }

    Person employee() throws Exception {
        return person(data, employeeRole, "emp");
    }

    void punch(Person person, String date, String time, String kind) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, ?::\"PunchKind\", 'WEB')", UUID.randomUUID(), person.id(),
                Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse(date), time, ZONE)), kind);
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    @AfterEach
    void removeTheShiftsOfThisTest() {
        addedShifts.add(shiftId);
        for (UUID shift : addedShifts) jdbc.update("DELETE FROM shifts WHERE id = ?", shift); // people that point at them are set to no shift
    }

    /** The instant of a local time, written as the API writes it (millisecond ISO with Z). */
    static String iso(String date, String time) {
        return AttendanceEngine.atLocal(LocalDate.parse(date), time, ZONE).toString().replaceAll("(\\d{2}:\\d{2}:\\d{2})Z$", "$1.000Z");
    }

    @Test
    void theSummaryAndTheEditFormCarryThePunchMode() throws Exception {
        Person person = employee();
        mockMvc.perform(get("/api/v1/attendance/summary").cookie(person.cookie())).andExpect(jsonPath("$.data.punchMode").value("MANUAL"));
        setBusinessMode("MACHINE");
        mockMvc.perform(get("/api/v1/attendance/summary").cookie(person.cookie())).andExpect(jsonPath("$.data.punchMode").value("MACHINE"));
        mockMvc.perform(get("/api/v1/people/form-options?id=" + person.id()).cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.punchModePolicy").value("MACHINE")).andExpect(jsonPath("$.data.defaults.punchMode").value(""));
        jdbc.update("UPDATE employees SET access_card_number = 'ZZ-1', punch_mode = 'MANUAL' WHERE id = ?", person.id());
        mockMvc.perform(get("/api/v1/people/form-options?id=" + person.id()).cookie(hr.cookie()))
                .andExpect(jsonPath("$.data.defaults.accessCardNumber").value("ZZ-1")).andExpect(jsonPath("$.data.defaults.punchMode").value("MANUAL"));
        mockMvc.perform(get("/api/v1/people/form-options").cookie(hr.cookie())).andExpect(jsonPath("$.data.punchModePolicy").value("MACHINE"));
    }

    JsonNode dayOf(JsonNode month, String date) {
        for (JsonNode day : month.at("/data/days")) if (date.equals(day.get("date").asText())) return day;
        throw new AssertionError("no day " + date);
    }

    /** The days of the story: Sep 2 a half day with a break, Sep 3 a full long day, Sep 4 / 7 / 8 / 9 late, Aug 3 a plain day in the previous month. */
    void seedStory(Person person) {
        punch(person, "2026-09-02", "09:40", "IN");
        punch(person, "2026-09-02", "13:00", "OUT");
        punch(person, "2026-09-02", "14:00", "IN");
        punch(person, "2026-09-02", "18:00", "OUT");
        punch(person, "2026-09-03", "09:30", "IN");
        punch(person, "2026-09-03", "18:30", "OUT");
        for (String date : List.of("2026-09-04", "2026-09-07", "2026-09-08", "2026-09-09")) {
            punch(person, date, "10:00", "IN");
            punch(person, date, "18:30", "OUT");
        }
        punch(person, "2026-08-03", "10:00", "IN");
        punch(person, "2026-08-03", "18:00", "OUT");
    }

    // ───────────────────────── the day ─────────────────────────

    @Test
    void theDayDetailShowsShiftSessionsBreakShortfallAndEverySwipe() throws Exception {
        Person person = employee();
        seedStory(person);
        jdbc.update("UPDATE employees SET access_card_number = 'CARD-77' WHERE id = ?", person.id());

        JsonNode detail = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(detail.get("date").asText()).isEqualTo("2026-09-02");
        assertThat(detail.get("shiftName").asText()).isEqualTo("Info day");
        assertThat(detail.get("shiftStart").asText()).isEqualTo("09:30");
        assertThat(detail.get("shiftEnd").asText()).isEqualTo("18:30");
        assertThat(detail.get("processedAt").isNull()).isTrue(); // computed on the fly, never written by the daily job
        assertThat(detail.get("status").asText()).isEqualTo("HALF_DAY");
        assertThat(detail.get("label").asText()).isEqualTo("Half day");
        assertThat(detail.get("code").asText()).isEqualTo("P:A");
        assertThat(detail.get("firstIn").asText()).isEqualTo(iso("2026-09-02", "09:40"));
        assertThat(detail.get("lastOut").asText()).isEqualTo(iso("2026-09-02", "18:00"));
        assertThat(detail.get("lateMinutes").asInt()).isEqualTo(10);
        assertThat(detail.get("earlyMinutes").asInt()).isEqualTo(30);
        assertThat(detail.get("totalMinutes").asInt()).isEqualTo(500);
        assertThat(detail.get("breakMinutes").asInt()).isEqualTo(60);
        assertThat(detail.get("actualMinutes").asInt()).isEqualTo(440);
        assertThat(detail.get("shortfallMinutes").asInt()).isEqualTo(40);
        assertThat(detail.get("excessMinutes").asInt()).isZero();
        assertThat(detail.get("remarks").isNull()).isTrue();
        assertThat(detail.get("punchMode").asText()).isEqualTo("MANUAL");
        assertThat(detail.at("/employee/code").asText()).isEqualTo(person.code());
        assertThat(detail.at("/employee/name").asText()).isEqualTo("Person " + person.code());
        assertThat(detail.at("/employee/accessCardNumber").asText()).isEqualTo("CARD-77");

        JsonNode sessions = detail.get("sessions");
        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).get("number").asInt()).isEqualTo(1);
        assertThat(sessions.get(0).get("startAt").asText()).isEqualTo(iso("2026-09-02", "09:40"));
        assertThat(sessions.get(0).get("endAt").asText()).isEqualTo(iso("2026-09-02", "13:00"));
        assertThat(sessions.get(0).get("minutes").asInt()).isEqualTo(200);
        assertThat(sessions.get(1).get("number").asInt()).isEqualTo(2);
        assertThat(sessions.get(1).get("minutes").asInt()).isEqualTo(240);

        JsonNode swipes = detail.get("swipes");
        assertThat(swipes).hasSize(4);
        assertThat(swipes.get(0).get("kind").asText()).isEqualTo("IN");
        assertThat(swipes.get(0).get("time").asText()).isEqualTo("09:40:00");
        assertThat(swipes.get(0).get("date").asText()).isEqualTo("2026-09-02");
        assertThat(swipes.get(0).get("source").asText()).isEqualTo("WEB");
        assertThat(swipes.get(0).get("at").asText()).isEqualTo(iso("2026-09-02", "09:40"));
        assertThat(swipes.get(0).get("location").isNull()).isTrue();
        assertThat(UUID.fromString(swipes.get(0).get("id").asText())).isNotNull();
        assertThat(swipes.get(3).get("kind").asText()).isEqualTo("OUT");
        assertThat(swipes.get(3).get("time").asText()).isEqualTo("18:00:00");

        // a long day: excess, no shortfall; one session, no break
        JsonNode long_ = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-03").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(long_.get("code").asText()).isEqualTo("P");
        assertThat(long_.get("totalMinutes").asInt()).isEqualTo(540);
        assertThat(long_.get("actualMinutes").asInt()).isEqualTo(540);
        assertThat(long_.get("excessMinutes").asInt()).isEqualTo(60);
        assertThat(long_.get("shortfallMinutes").asInt()).isZero();
        assertThat(long_.get("breakMinutes").asInt()).isZero();
        assertThat(long_.get("sessions")).hasSize(1);

        // a weekly off (a Sunday): code O, nothing asked for, no swipes
        JsonNode sunday = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-06").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(sunday.get("code").asText()).isEqualTo("O");
        assertThat(sunday.get("shortfallMinutes").asInt()).isZero();
        assertThat(sunday.get("excessMinutes").asInt()).isZero();
        assertThat(sunday.get("sessions")).isEmpty();
        assertThat(sunday.get("swipes")).isEmpty();
    }

    @Test
    void theDayDetailShowsTheLastProcessingTimeAndTheCheckOutNoteOrTheRegularizationReason() throws Exception {
        Person person = employee();
        punch(person, "2026-09-03", "09:30", "IN");
        punch(person, "2026-09-03", "18:30", "OUT");
        jdbc.update("UPDATE punches SET note = 'closed the audit file' WHERE employee_id = ? AND kind = 'OUT'", person.id());
        jdbc.update("""
            INSERT INTO attendance_days (employee_id, work_date, status, label, first_in, last_out, worked_minutes, late, late_waived, regularized, missed_checkout, lop_days, leave_days, on_duty_days, locked, updated_at)
            VALUES (?, '2026-09-03', 'PRESENT', 'Present', ?, ?, 540, false, false, false, false, 0, 0, 0, false, '2026-09-04T01:00:00Z')""",
                person.id(), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-09-03"), "09:30", ZONE)), Timestamp.from(AttendanceEngine.atLocal(LocalDate.parse("2026-09-03"), "18:30", ZONE)));
        JsonNode detail = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-03").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(detail.get("processedAt").asText()).isEqualTo("2026-09-04T01:00:00.000Z");
        assertThat(detail.get("remarks").asText()).isEqualTo("closed the audit file");

        // no check-out note: the reason of the approved regularization is the remark
        jdbc.update("UPDATE punches SET note = NULL WHERE employee_id = ?", person.id());
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, reason, status, decided_by, decided_at) VALUES (?, ?, '2026-09-03', 'LATE_CHECKIN', 'bus broke down', 'APPROVED', ?, now())",
                UUID.randomUUID(), person.id(), hr.id());
        JsonNode withReason = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-03").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(withReason.get("remarks").asText()).isEqualTo("bus broke down");
    }

    @Test
    void aDayIsAlwaysTheSignedInPersonsOwn() throws Exception {
        Person owner = employee();
        Person colleague = employee();
        seedStory(owner);
        // the colleague asks for the same date and gets their own (empty) day, never the other person's swipes
        JsonNode theirs = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(colleague.cookie())).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(theirs.get("swipes")).isEmpty();
        assertThat(theirs.get("sessions")).isEmpty();
        assertThat(theirs.at("/employee/code").asText()).isEqualTo(colleague.code());
        assertThat(theirs.get("code").asText()).isEqualTo("A");
        // HR (same business) and someone of another business see only their own too
        assertThat(body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(hr.cookie())).andExpect(status().isOk()).andReturn()).at("/data/swipes")).isEmpty();
        TestData other = data.inBusiness("info-other-" + suffix);
        UUID otherRole = other.role("employee", "self", "dir");
        String otherEmail = "other." + suffix + "@example.com";
        UUID otherId = other.employee("OT" + suffix.toUpperCase(), otherEmail, otherRole);
        String set = SignIn.session(mockMvc, otherEmail).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        JsonNode foreign = body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(new Cookie("jo_at", matcher.group(1)))).andExpect(status().isOk()).andReturn()).get("data");
        assertThat(foreign.get("swipes")).isEmpty();
        assertThat(foreign.at("/employee/code").asText()).isEqualTo("OT" + suffix.toUpperCase());
        assertThat(otherId).isNotEqualTo(owner.id());
    }

    @Test
    void theDayDetailRefusesBadDatesHostileTextAndPeopleWithoutAttendance() throws Exception {
        Person person = employee();
        seedStory(person);
        long punchesBefore = jdbc.queryForObject("SELECT count(*) FROM punches", Long.class);
        for (String bad : List.of("2026-13-45", "2026-02-30", "abc", "2026-9-2", "20260902", "2026-09-02T10:00", "1999-12-31", "2101-01-01", "0001-01-01", "99999-01-01", "-2026-09-02",
                "'; DROP TABLE punches;--", "2026-09-02' OR '1'='1", "<script>alert(1)</script>", "%00", "..%2F..%2Fetc%2Fpasswd", "a".repeat(5000))) {
            mockMvc.perform(get("/api/v1/attendance/mine/day-details/{date}", bad).cookie(person.cookie())).andExpect(status().is4xxClientError());
        }
        for (String bad : List.of("2026-13-45", "abc", "1999-12-31", "2101-01-01")) {
            mockMvc.perform(get("/api/v1/attendance/mine/day-details/{date}", bad).cookie(person.cookie())).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches", Long.class)).isEqualTo(punchesBefore);
        // signed out: 401
        mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02")).andExpect(status().isUnauthorized());
        // a role without attendance tracking (no "self"): refused
        UUID noSelfRole = data.role("viewer", "dir");
        Person viewer = person(data, noSelfRole, "viewer");
        mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(viewer.cookie())).andExpect(status().isForbidden());
    }

    // ───────────────────────── the month ─────────────────────────

    @Test
    void theMonthCarriesCodesDayTypesAveragesPenaltyAndDeductionAlerts() throws Exception {
        Person person = employee();
        seedStory(person);
        JsonNode month = body(mockMvc.perform(get("/api/v1/attendance/mine?month=2026-09").cookie(person.cookie())).andExpect(status().isOk()).andReturn());
        JsonNode summary = month.at("/data/summary");
        // six days with a check-in and a check-out: total (500 + 540 + 4 x 510) / 6, actual (440 + 540 + 4 x 510) / 6
        assertThat(summary.get("avgWorkMinutes").asInt()).isEqualTo(513);
        assertThat(summary.get("avgActualMinutes").asInt()).isEqualTo(503);
        // four late marks at three per half day = half a day of penalty
        assertThat(summary.get("lateMarks").asInt()).isEqualTo(4);
        assertThat(summary.get("lateLopDays").asDouble()).isEqualTo(0.5);
        assertThat(summary.get("penaltyDays").asDouble()).isEqualTo(0.5);
        // the previous month (Aug 3 only): 480 / 480
        assertThat(summary.get("previousAvgWorkMinutes").asInt()).isEqualTo(480);
        assertThat(summary.get("previousAvgActualMinutes").asInt()).isEqualTo(480);
        assertThat(month.at("/data/punchMode").asText()).isEqualTo("MANUAL");

        JsonNode halfDay = dayOf(month, "2026-09-02");
        assertThat(halfDay.get("code").asText()).isEqualTo("P:A");
        assertThat(halfDay.get("dayType").asText()).isEqualTo("HALF_DAY");
        assertThat(halfDay.get("graceUsed").asBoolean()).isTrue();
        assertThat(halfDay.get("deductionAlert").asBoolean()).isFalse();
        assertThat(halfDay.get("sessionCount").asInt()).isEqualTo(2);
        JsonNode plain = dayOf(month, "2026-09-03");
        assertThat(plain.get("code").asText()).isEqualTo("P");
        assertThat(plain.get("dayType").asText()).isEqualTo("WORKING");
        assertThat(plain.get("graceUsed").asBoolean()).isFalse();
        assertThat(plain.get("sessionCount").asInt()).isEqualTo(1);
        // the first three late marks are charged, the fourth is part of the next, incomplete group
        assertThat(dayOf(month, "2026-09-04").get("deductionAlert").asBoolean()).isTrue();
        assertThat(dayOf(month, "2026-09-07").get("deductionAlert").asBoolean()).isTrue();
        assertThat(dayOf(month, "2026-09-08").get("deductionAlert").asBoolean()).isTrue();
        assertThat(dayOf(month, "2026-09-09").get("deductionAlert").asBoolean()).isFalse();
        assertThat(dayOf(month, "2026-09-09").get("late").asBoolean()).isTrue();
        // the Sunday is a weekly off, an untouched weekday is absent
        assertThat(dayOf(month, "2026-09-06").get("code").asText()).isEqualTo("O");
        assertThat(dayOf(month, "2026-09-06").get("dayType").asText()).isEqualTo("WEEKLY_OFF");
        assertThat(dayOf(month, "2026-09-10").get("code").asText()).isEqualTo("A");
        assertThat(dayOf(month, "2026-09-10").get("deduction").asBoolean()).isTrue();
        assertThat(plain.get("deduction").asBoolean()).isFalse();
        assertThat(halfDay.get("deduction").asBoolean()).isTrue(); // a half day carries half a day of LOP
        // the old fields are still there
        assertThat(halfDay.get("firstIn").asText()).isEqualTo("9:40 AM");
        assertThat(month.at("/data/days")).hasSize(30);

        // a month with no earlier month of data: previous averages are null
        JsonNode august = body(mockMvc.perform(get("/api/v1/attendance/mine?month=2026-08").cookie(person.cookie())).andExpect(status().isOk()).andReturn());
        assertThat(august.at("/data/summary/avgWorkMinutes").asInt()).isEqualTo(480);
        assertThat(august.at("/data/summary/previousAvgWorkMinutes").isNull()).isTrue();
        assertThat(august.at("/data/summary/previousAvgActualMinutes").isNull()).isTrue();
        // a month without any punches: zero averages
        JsonNode empty = body(mockMvc.perform(get("/api/v1/attendance/mine?month=2026-05").cookie(person.cookie())).andExpect(status().isOk()).andReturn());
        assertThat(empty.at("/data/summary/avgWorkMinutes").asInt()).isZero();
        assertThat(empty.at("/data/summary/avgActualMinutes").asInt()).isZero();
        assertThat(empty.at("/data/summary/penaltyDays").asDouble()).isZero();
    }

    // ───────────────────────── access card number and punch mode of an employee ─────────────────────────

    String personBody(Person target, String extraFields) {
        return """
            {"firstName":"Meera","middleName":"","lastName":"Joshi","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"Associate","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"%s}"""
                .formatted(target.email(), departmentId, hr.id(), employeeRole, extraFields);
    }

    @Test
    void theAccessCardNumberIsValidatedUniquePerBusinessAuditedAndShownOnTheProfile() throws Exception {
        Person first = employee();
        Person second = employee();

        mockMvc.perform(write(put("/api/v1/people/" + first.id())).cookie(hr.cookie()).content(personBody(first, ",\"accessCardNumber\":\" ab-123 \",\"punchMode\":\"MACHINE\"")))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, first.id())).isEqualTo("AB-123"); // trimmed and upper-cased
        assertThat(jdbc.queryForObject("SELECT punch_mode FROM employees WHERE id = ?", String.class, first.id())).isEqualTo("MACHINE");
        mockMvc.perform(get("/api/v1/people/" + first.id()).cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessCardNumber").value("AB-123")).andExpect(jsonPath("$.data.punchMode").value("MACHINE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.edit' AND entity_id = ? AND after::text LIKE '%AB-123%'", Integer.class, first.id().toString())).isEqualTo(1);

        // a request without the two fields leaves them as they are
        mockMvc.perform(write(put("/api/v1/people/" + first.id())).cookie(hr.cookie()).content(personBody(first, ""))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, first.id())).isEqualTo("AB-123");
        assertThat(jdbc.queryForObject("SELECT punch_mode FROM employees WHERE id = ?", String.class, first.id())).isEqualTo("MACHINE");
        // saving the same number for the same person is fine
        mockMvc.perform(write(put("/api/v1/people/" + first.id())).cookie(hr.cookie()).content(personBody(first, ",\"accessCardNumber\":\"AB-123\""))).andExpect(status().isOk());

        // the same number for another person of the business: 409, and nothing changes
        mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"accessCardNumber\":\"ab-123\"")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("That access card number is already used by another employee."));
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, second.id())).isNull();

        // wrong shapes and hostile text: 400, nothing stored
        for (String bad : List.of("a b", "ab_12", "ab.12", "A".repeat(33), "'; DROP TABLE employees;--", "<script>alert(1)</script>", "AB-123\\u0000", "ÅÅ", "12/34")) {
            mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"accessCardNumber\":" + json.writeValueAsString(bad))))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"punchMode\":\"ROBOT\""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"punchMode\":\"PER_EMPLOYEE\""))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, second.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT punch_mode FROM employees WHERE id = ?", String.class, second.id())).isNull();
        // a 32 character number is the longest allowed
        mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"accessCardNumber\":\"" + "9".repeat(32) + "\""))).andExpect(status().isOk());

        // an empty value clears both
        mockMvc.perform(write(put("/api/v1/people/" + first.id())).cookie(hr.cookie()).content(personBody(first, ",\"accessCardNumber\":\"\",\"punchMode\":\"\""))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, first.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT punch_mode FROM employees WHERE id = ?", String.class, first.id())).isNull();
        // the freed number can be given to the other person now
        mockMvc.perform(write(put("/api/v1/people/" + second.id())).cookie(hr.cookie()).content(personBody(second, ",\"accessCardNumber\":\"AB-123\""))).andExpect(status().isOk());
    }

    @Test
    void anotherBusinessMayUseTheSameNumberAndItsPeopleCannotBeEdited() throws Exception {
        Person mine = employee();
        mockMvc.perform(write(put("/api/v1/people/" + mine.id())).cookie(hr.cookie()).content(personBody(mine, ",\"accessCardNumber\":\"SHARED-1\""))).andExpect(status().isOk());

        TestData other = data.inBusiness("info-card-" + suffix);
        UUID otherRole = other.role("employee", "self", "dir");
        UUID otherId = other.employee("OC" + suffix.toUpperCase(), "oc." + suffix + "@example.com", otherRole);
        jdbc.update("UPDATE employees SET access_card_number = 'SHARED-1' WHERE id = ?", otherId); // unique per business, not across businesses
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE access_card_number = 'SHARED-1'", Integer.class)).isEqualTo(2);

        // HR of this business cannot reach that person (IDOR): refused, and the other business's number is untouched
        Person foreign = new Person(otherId, "OC" + suffix.toUpperCase(), "oc." + suffix + "@example.com", null);
        mockMvc.perform(write(put("/api/v1/people/" + otherId)).cookie(hr.cookie()).content(personBody(foreign, ",\"accessCardNumber\":\"STOLEN-9\"")))
                .andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, otherId)).isEqualTo("SHARED-1");
        mockMvc.perform(get("/api/v1/people/" + otherId).cookie(hr.cookie())).andExpect(status().is4xxClientError());
        // and the database itself refuses a second holder inside one business
        Person third = employee();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE employees SET access_card_number = 'SHARED-1' WHERE id = ?", third.id()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void anEmployeeWithoutEditRightsCannotSetTheCardNumber() throws Exception {
        Person worker = employee();
        Person colleague = employee();
        mockMvc.perform(write(put("/api/v1/people/" + colleague.id())).cookie(worker.cookie()).content(personBody(colleague, ",\"accessCardNumber\":\"HACK-1\"")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT access_card_number FROM employees WHERE id = ?", String.class, colleague.id())).isNull();
    }

    // ───────────────────────── punch mode of the business ─────────────────────────

    String policyBody(String punchModeField) {
        return "{\"fullDayHours\":8,\"halfDayHours\":4,\"graceMinutes\":15,\"lateMarksPerHalfDayLop\":3,\"weeklyOffs\":[0,6],\"absenceJobBufferMinutes\":120,\"lockDayOfNextMonth\":1,\"lockTime\":\"10:00\","
                + "\"trackingStartDate\":\"2026-04-01\"" + punchModeField + "}";
    }

    void setBusinessMode(String mode) {
        jdbc.update("UPDATE settings SET value = jsonb_set(value, '{punchMode}', to_jsonb(?::text)) WHERE business_id = ? AND key = 'attendance.policy'", mode, data.business());
    }

    int punchCount(Person person) {
        return jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ?", Integer.class, person.id());
    }

    @Test
    void anOldBusinessWithoutTheSettingRecordsManuallyAndAMachineBusinessRefusesTheWebCheckIn() throws Exception {
        Person person = employee();
        // the saved value has no punchMode at all (set up in business()): MANUAL, the check-in works
        mockMvc.perform(get("/api/v1/settings/attendance").cookie(admin.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.policy.punchMode").value("MANUAL"));
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isCreated());
        assertThat(punchCount(person)).isEqualTo(1);
        jdbc.update("DELETE FROM punches WHERE employee_id = ?", person.id());

        setBusinessMode("MACHINE");
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Your business records attendance from the punch machine")));
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"OUT\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(punchCount(person)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'attendance.punch_refused'", Integer.class, person.id())).isEqualTo(2);
        // the month and the day tell the page which mode applies
        assertThat(body(mockMvc.perform(get("/api/v1/attendance/mine").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).at("/data/punchMode").asText()).isEqualTo("MACHINE");
        assertThat(body(mockMvc.perform(get("/api/v1/attendance/mine/day-details/2026-09-02").cookie(person.cookie())).andExpect(status().isOk()).andReturn()).at("/data/punchMode").asText()).isEqualTo("MACHINE");

        // an employee's own mode is ignored unless the business is PER_EMPLOYEE
        jdbc.update("UPDATE employees SET punch_mode = 'MANUAL' WHERE id = ?", person.id());
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isUnprocessableEntity());
        setBusinessMode("MANUAL");
        jdbc.update("UPDATE employees SET punch_mode = 'MACHINE' WHERE id = ?", person.id());
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isCreated());
    }

    @Test
    void underPerEmployeeEachPersonsOwnModeDecidesAndNullMeansManual() throws Exception {
        Person machineUser = employee();
        Person manualUser = employee();
        Person unsetUser = employee();
        jdbc.update("UPDATE employees SET punch_mode = 'MACHINE' WHERE id = ?", machineUser.id());
        jdbc.update("UPDATE employees SET punch_mode = 'MANUAL' WHERE id = ?", manualUser.id());
        setBusinessMode("PER_EMPLOYEE");
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(machineUser.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(manualUser.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/v1/attendance/punches")).cookie(unsetUser.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isCreated());
        assertThat(punchCount(machineUser)).isZero();
        assertThat(body(mockMvc.perform(get("/api/v1/attendance/mine").cookie(machineUser.cookie())).andExpect(status().isOk()).andReturn()).at("/data/punchMode").asText()).isEqualTo("MACHINE");
        assertThat(body(mockMvc.perform(get("/api/v1/attendance/mine").cookie(unsetUser.cookie())).andExpect(status().isOk()).andReturn()).at("/data/punchMode").asText()).isEqualTo("MANUAL");
    }

    @Test
    void thePunchModeIsSavedWithThePolicyValidatedAuditedAndKeptWhenLeftOut() throws Exception {
        for (String bad : List.of(",\"punchMode\":\"ROBOT\"", ",\"punchMode\":\"manual\"", ",\"punchMode\":\"\"", ",\"punchMode\":\"'; DROP TABLE settings;--\"", ",\"punchMode\":\"<script>alert(1)</script>\"",
                ",\"punchMode\":\"" + "M".repeat(5000) + "\"", ",\"punchMode\":5", ",\"punchMode\":{\"a\":1}")) {
            mockMvc.perform(write(put("/api/v1/settings/attendance")).cookie(admin.cookie()).content(policyBody(bad))).andExpect(status().is4xxClientError());
        }
        mockMvc.perform(get("/api/v1/settings/attendance").cookie(admin.cookie())).andExpect(jsonPath("$.data.policy.punchMode").value("MANUAL"));

        mockMvc.perform(write(put("/api/v1/settings/attendance")).cookie(admin.cookie()).content(policyBody(",\"punchMode\":\"MACHINE\""))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/attendance").cookie(admin.cookie())).andExpect(jsonPath("$.data.policy.punchMode").value("MACHINE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'policy.attendance_change' AND after::text LIKE '%MACHINE%'", Integer.class, admin.id())).isEqualTo(1);

        // a request that leaves the punch mode out keeps what is saved
        mockMvc.perform(write(put("/api/v1/settings/attendance")).cookie(admin.cookie()).content(policyBody(""))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/attendance").cookie(admin.cookie())).andExpect(jsonPath("$.data.policy.punchMode").value("MACHINE"));
        mockMvc.perform(write(put("/api/v1/settings/attendance")).cookie(admin.cookie()).content(policyBody(",\"punchMode\":\"PER_EMPLOYEE\""))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/settings/attendance").cookie(admin.cookie())).andExpect(jsonPath("$.data.policy.punchMode").value("PER_EMPLOYEE"));

        // only the admin may change it
        mockMvc.perform(write(put("/api/v1/settings/attendance")).cookie(hr.cookie()).content(policyBody(",\"punchMode\":\"MANUAL\""))).andExpect(status().isForbidden());
    }
}
