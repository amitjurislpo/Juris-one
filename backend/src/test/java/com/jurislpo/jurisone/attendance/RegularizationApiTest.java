package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Regularization requests v2: the rules, Old/New values, decisions, attachments, and the refusals (hostile input, other people's rows, other businesses, Finance). */
class RegularizationApiTest extends IntegrationTest {

    static final String ZONE = "Asia/Kolkata";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole, hrRole, financeRole, forgotCheckOut, otherReason;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("regularization-test");
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        financeRole = data.role("fm", "self", "dir", "people.view", "pay.view");
        // the previous month stays open for a long time, so a recent working day can always be regularized
        jdbc.update("""
            INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{"trackingStartDate":"2026-04-01","lockDayOfNextMonth":28}', now())
            ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value""", data.business());
        jdbc.update("DELETE FROM settings WHERE business_id = ? AND key = 'attendance.regularization'", data.business());
        jdbc.update("DELETE FROM regularization_requests WHERE employee_id IN (SELECT id FROM employees WHERE business_id = ?)", data.business());
        jdbc.update("DELETE FROM regularization_reasons WHERE business_id = ?", data.business());
        forgotCheckOut = reason("Forgot to check-out", false);
        otherReason = reason("Other", true);
    }

    UUID reason(String label, boolean requiresDescription) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_reasons (id, business_id, label, requires_description, sort_order) VALUES (?, ?, ?, ?, 1)", id, data.business(), label, requiresDescription);
        return id;
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static MockHttpServletRequestBuilder upload(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    static int sequence = 7000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(TestData in, UUID role) throws Exception {
        String code = "JC-" + (++sequence) + (int) (Math.random() * 1000);
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

    void punchIn(Person person, LocalDate day, String time) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB')", UUID.randomUUID(), person.id(), Timestamp.from(AttendanceEngine.atLocal(day, time, ZONE)));
    }

    String missedCheckOut(LocalDate day, String checkOut, UUID reasonId, String description) {
        return "{\"date\":\"" + day + "\",\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T" + checkOut + "\",\"reasonId\":\"" + reasonId + "\",\"description\":\"" + description + "\"}";
    }

    String idOf(Person person, LocalDate day) {
        return jdbc.queryForObject("SELECT id::text FROM regularization_requests WHERE employee_id = ? AND work_date = ? ORDER BY created_at DESC LIMIT 1", String.class, person.id(), java.sql.Date.valueOf(day));
    }

    @Test
    void requestKeepsOldAndNewValuesAndAnApproverDecidesIt() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", forgotCheckOut, "left the building late")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Regularization request added successfully"));
        String id = idOf(employee, day);
        assertThat(jdbc.queryForObject("SELECT new_minutes FROM regularization_requests WHERE id = ?::uuid", Integer.class, id)).isEqualTo(570);
        assertThat(jdbc.queryForObject("SELECT new_status FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("PRESENT");
        assertThat(jdbc.queryForObject("SELECT old_check_in IS NOT NULL AND old_check_out IS NULL FROM regularization_requests WHERE id = ?::uuid", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/approvals?tab=regularizations'", Integer.class, hr.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'regularization.request' AND entity_id = ?", Integer.class, id)).isEqualTo(1);

        JsonNode mine = json.readTree(mockMvc.perform(get("/api/v1/attendance/regularizations?scope=mine").cookie(employee.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(mine.get("total").asInt()).isEqualTo(1);
        assertThat(mine.get("items").get(0).get("newMinutes").asInt()).isEqualTo(570);
        assertThat(mine.get("items").get(0).get("reason").asText()).isEqualTo("Forgot to check-out — left the building late");

        // a day with the request pending can't take a second one
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:30", forgotCheckOut, "x")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGULARIZATION_ALREADY_PENDING"));

        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(employee.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true,\"note\":\"fine\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status::text || decision_note FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("APPROVEDfine");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'REGULARIZATION' AND kind = 'OUT'", Integer.class, employee.id())).isEqualTo(1);
        // already decided: a second decision is a conflict, and so is withdrawing
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(delete("/api/v1/attendance/regularizations/" + id)).cookie(employee.cookie())).andExpect(jsonPath("$.detail").value("Only pending requests can be withdrawn."));
    }

    @Test
    void rejectingNeedsRemarksAndTheEmployeeCanWithdrawOnlyOwnPendingRequests() throws Exception {
        Person employee = person(employeeRole), other = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "18:45", forgotCheckOut, ""))).andExpect(status().isCreated());
        String id = idOf(employee, day);
        mockMvc.perform(write(delete("/api/v1/attendance/regularizations/" + id)).cookie(other.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"  \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"not credible\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("DECLINED");
        // nothing was written to the attendance
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'REGULARIZATION'", Integer.class, employee.id())).isZero();
        // after a rejection the day can be asked for again, and a pending one can be withdrawn
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "18:45", forgotCheckOut, ""))).andExpect(status().isCreated());
        String second = idOf(employee, day);
        mockMvc.perform(write(delete("/api/v1/attendance/regularizations/" + second)).cookie(employee.cookie())).andExpect(jsonPath("$.message").value("Request withdrawn"));
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, second)).isEqualTo("CANCELLED");
    }

    @Test
    void theServerRefusesBadTimesReasonsAndDays() throws Exception {
        Person employee = person(employeeRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        String base = "\"date\":\"" + day + "\",\"kind\":\"MISSED_CHECKOUT\"";
        // check-out before the existing check-in, more than 24 h later, in the future, wrong format, nonexistent date
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "08:00", forgotCheckOut, ""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie())
                .content("{" + base + ",\"checkOut\":\"" + day.plusDays(2) + "T10:00\",\"reasonId\":\"" + forgotCheckOut + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie())
                .content("{" + base + ",\"checkOut\":\"" + today().plusDays(1) + "T10:00\",\"reasonId\":\"" + forgotCheckOut + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie())
                .content("{" + base + ",\"checkOut\":\"tomorrow\",\"reasonId\":\"" + forgotCheckOut + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie())
                .content("{\"date\":\"2026-13-45\",\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"2026-13-45T10:00\",\"reasonId\":\"" + forgotCheckOut + "\"}")).andExpect(status().isBadRequest());
        // reason: missing, not a uuid, SQL text, unknown, "Other" without description, description too long
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content("{" + base + ",\"checkOut\":\"" + day + "T19:00\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie())
                .content("{" + base + ",\"checkOut\":\"" + day + "T19:00\",\"reasonId\":\"1' OR '1'='1\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", UUID.randomUUID(), ""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", otherReason, ""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", forgotCheckOut, "a".repeat(501)))).andExpect(status().isBadRequest());
        // unknown kind; a future day; a day older than the 30-day limit
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content("{\"date\":\"" + day + "\",\"kind\":\"DROP TABLE\",\"reasonId\":\"" + forgotCheckOut + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(today().plusDays(1), "19:00", forgotCheckOut, "")))
                .andExpect(jsonPath("$.detail").value("You can't regularize a future day."));
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.regularization', '{\"backDateLimitDays\":3}', now())", data.business());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(today().minusDays(5), "19:00", forgotCheckOut, "")))
                .andExpect(jsonPath("$.detail").value("Requests can be made up to 3 days back."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ?", Integer.class, employee.id())).isZero();
    }

    @Test
    void monthlyLimitAndTheMandatoryAttachmentAreEnforced() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate first = workday(2), second = workday(4);
        if (first.equals(second)) second = workday(5);
        punchIn(employee, first, "09:30");
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(employee.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":1,\"attachmentMandatory\":false}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":0,\"attachmentMandatory\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":1,\"attachmentMandatory\":true}")).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(first, "19:00", forgotCheckOut, ""))).andExpect(jsonPath("$.detail").value("Attach a supporting document."));

        byte[] pdf = "%PDF-1.4 test".getBytes();
        mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "proof.pdf", "application/pdf", pdf))
                        .param("request", missedCheckOut(first, "19:00", forgotCheckOut, ""))).cookie(employee.cookie()))
                .andExpect(status().isCreated());
        String id = idOf(employee, first);
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id + "/attachment").cookie(employee.cookie())).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id + "/attachment").cookie(hr.cookie())).andExpect(status().isOk()).andExpect(content().bytes(pdf));
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id + "/attachment").cookie(person(employeeRole).cookie())).andExpect(status().isNotFound());

        // limit of one a month is used up (a different day of the same month, or the same window, counts the same)
        if (second.getMonth() == first.getMonth()) {
            mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "proof.pdf", "application/pdf", pdf))
                            .param("request", "{\"date\":\"" + second + "\",\"kind\":\"ABSENT\",\"checkIn\":\"" + second + "T09:30\",\"checkOut\":\"" + second + "T18:30\",\"reasonId\":\"" + forgotCheckOut + "\"}")
                            ).cookie(employee.cookie()))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("You have used all 1 regularization requests")));
        }
    }

    @Test
    void attachmentsAreCheckedByTypeContentAndSize() throws Exception {
        Person employee = person(employeeRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        String request = missedCheckOut(day, "19:00", forgotCheckOut, "");
        mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "run.exe", "application/octet-stream", "MZ......".getBytes()))
                .param("request", request)).cookie(employee.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "<script>alert(1)</script>".getBytes()))
                .param("request", request)).cookie(employee.cookie())).andExpect(status().isBadRequest());
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(), 0, big, 0, 5);
        mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                .param("request", request)).cookie(employee.cookie())).andExpect(status().isPayloadTooLarge());
        // a path in the file name is cut to a safe name
        mockMvc.perform(upload(multipart("/api/v1/attendance/regularizations").file(new MockMultipartFile("file", "../../etc/passwd.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .param("request", request)).cookie(employee.cookie())).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT attachment_name FROM regularization_requests WHERE employee_id = ?", String.class, employee.id())).isEqualTo("passwd.pdf");
        assertThat(jdbc.queryForObject("SELECT attachment_path FROM regularization_requests WHERE employee_id = ?", String.class, employee.id())).startsWith("regularizations/").doesNotContain("..");
    }

    @Test
    void otherPeopleAndOtherBusinessesNeverSeeARequest() throws Exception {
        Person employee = person(employeeRole), colleague = person(employeeRole), hr = person(hrRole), finance = person(financeRole);
        TestData stranger = data.inBusiness("regularization-other");
        Person outsiderHr = person(stranger, stranger.role("hrm", "self", "dir", "att.all", "appr.reg"));
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", forgotCheckOut, ""))).andExpect(status().isCreated());
        String id = idOf(employee, day);

        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id).cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id).cookie(colleague.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id).cookie(finance.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attendance/regularizations/" + id).cookie(outsiderHr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(outsiderHr.cookie()).content("{\"approve\":true}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(outsiderHr.cookie()).content("{\"ids\":[\"" + id + "\"],\"approve\":true}"))
                .andExpect(jsonPath("$.data[0].ok").value(false));
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("PENDING");

        // the whole-business list, decisions, rules and export belong to Admin / HR only; Finance has no approval right any more
        for (Person denied : new Person[] {employee, finance}) {
            mockMvc.perform(get("/api/v1/attendance/regularizations?scope=all").cookie(denied.cookie())).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/attendance/regularizations/export").cookie(denied.cookie())).andExpect(status().isForbidden());
            mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(denied.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
            mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(denied.cookie()).content("{\"ids\":[\"" + id + "\"],\"approve\":true}")).andExpect(status().isForbidden());
            mockMvc.perform(write(post("/api/v1/attendance/regularization-reasons")).cookie(denied.cookie()).content("{\"label\":\"x\"}")).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/attendance/regularizations?scope=all").cookie(outsiderHr.cookie())).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/attendance/regularizations?scope=all").cookie(hr.cookie())).andExpect(jsonPath("$.data.total").value(1));
        // the CSV starts with the header and neutralises spreadsheet formulas
        mockMvc.perform(get("/api/v1/attendance/regularizations/export").cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Employee code,Employee,Department,Date,Type")));
        // hostile filter values are bound parameters, never SQL
        mockMvc.perform(get("/api/v1/attendance/regularizations").queryParam("scope", "all").queryParam("search", "x' OR '1'='1' --").cookie(hr.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/attendance/regularizations").queryParam("scope", "all").queryParam("employeeId", "1; DROP TABLE employees").cookie(hr.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/attendance/regularizations").queryParam("scope", "all").queryParam("status", "PENDING' --").cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/regularizations/not-a-uuid").cookie(hr.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/attendance/regularizations")).andExpect(status().isUnauthorized());
    }

    @Test
    void severalRequestsAreDecidedTogetherAndOwnRequestsCannotBeApproved() throws Exception {
        Person one = person(employeeRole), two = person(employeeRole), hr = person(hrRole), secondHr = person(hrRole);
        LocalDate day = workday(2);
        punchIn(one, day, "09:30");
        punchIn(two, day, "09:30");
        punchIn(hr, day, "09:30");
        for (Person person : new Person[] {one, two, hr}) {
            mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(person.cookie()).content(missedCheckOut(day, "19:00", forgotCheckOut, ""))).andExpect(status().isCreated());
        }
        String ids = "\"" + idOf(one, day) + "\",\"" + idOf(two, day) + "\",\"" + idOf(hr, day) + "\"";
        mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(hr.cookie()).content("{\"ids\":[" + ids + "],\"approve\":false}")).andExpect(jsonPath("$.data[0].ok").value(false));
        JsonNode results = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(hr.cookie()).content("{\"ids\":[" + ids + "],\"approve\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(results.get(0).get("ok").asBoolean()).isTrue();
        assertThat(results.get(1).get("ok").asBoolean()).isTrue();
        assertThat(results.get(2).get("ok").asBoolean()).isFalse(); // HR can't approve their own
        mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(hr.cookie()).content("{\"ids\":[],\"approve\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + idOf(hr, day) + "/decision")).cookie(secondHr.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
    }

    @Test
    void reasonsAreEditedByApproversAndOnlyActiveOnesCanBeUsed() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularization-reasons")).cookie(hr.cookie()).content("{\"label\":\"Power cut\",\"requiresDescription\":false}")).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/v1/attendance/regularization-reasons")).cookie(hr.cookie()).content("{\"label\":\"power CUT\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/attendance/regularization-reasons")).cookie(hr.cookie()).content("{\"label\":\"\"}")).andExpect(status().isBadRequest());
        UUID powerCut = jdbc.queryForObject("SELECT id FROM regularization_reasons WHERE label = 'Power cut' AND business_id = ?", UUID.class, data.business());
        mockMvc.perform(write(patch("/api/v1/attendance/regularization-reasons/" + powerCut)).cookie(hr.cookie()).content("{\"active\":false}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/regularization-rules").cookie(employee.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reasons[?(@.label == 'Power cut')]").isEmpty()).andExpect(jsonPath("$.data.monthlyLimit").value(30)).andExpect(jsonPath("$.data.backDateLimitDays").value(30));
        mockMvc.perform(get("/api/v1/attendance/regularization-rules").cookie(hr.cookie())).andExpect(jsonPath("$.data.reasons[?(@.label == 'Power cut')]").isNotEmpty());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", powerCut, ""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch("/api/v1/attendance/regularization-reasons/" + UUID.randomUUID())).cookie(hr.cookie()).content("{\"active\":false}")).andExpect(status().isNotFound());
    }

    @Test
    void theFormIsPrefilledFromTheRecordedDay() throws Exception {
        Person employee = person(employeeRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(get("/api/v1/attendance/mine/days/" + day).cookie(employee.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkInLocal").value(day + "T09:30")).andExpect(jsonPath("$.data.eligibleKinds").value(org.hamcrest.Matchers.hasItem("MISSED_CHECKOUT")));
        mockMvc.perform(get("/api/v1/attendance/mine/days/31-12-2026").cookie(employee.cookie())).andExpect(status().isBadRequest());
    }

    String absentBody(LocalDate day, LocalDate toDate, UUID employeeId) {
        return "{\"date\":\"" + day + "\"," + (toDate == null ? "" : "\"toDate\":\"" + toDate + "\",") + (employeeId == null ? "" : "\"employeeId\":\"" + employeeId + "\",")
                + "\"kind\":\"ABSENT\",\"checkIn\":\"" + day + "T09:30\",\"checkOut\":\"" + day + "T18:30\",\"reasonId\":\"" + forgotCheckOut + "\",\"description\":\"\"}";
    }

    static String withEmployee(String body, String employeeId) {
        return body.replace("{", "{\"employeeId\":\"" + employeeId + "\",");
    }

    @Test
    void adminOrHrCanRaiseARequestOnBehalfOfAnEmployeeOfTheirOwnBusinessOnly() throws Exception {
        Person employee = person(employeeRole), colleague = person(employeeRole), hr = person(hrRole), secondHr = person(hrRole);
        TestData stranger = data.inBusiness("regularization-other");
        Person outsider = person(stranger, stranger.role("employee", "self", "dir"));
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        String plain = missedCheckOut(day, "19:00", forgotCheckOut, "");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(colleague.cookie()).content(withEmployee(plain, employee.id().toString()))).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(hr.cookie()).content(withEmployee(plain, outsider.id().toString()))).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(hr.cookie()).content(withEmployee(plain, "1' OR '1'='1"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id IN (?, ?)", Integer.class, employee.id(), outsider.id())).isZero();

        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(hr.cookie()).content(withEmployee(plain, employee.id().toString()))).andExpect(status().isCreated());
        String id = idOf(employee, day);
        assertThat(jdbc.queryForObject("SELECT created_by FROM regularization_requests WHERE id = ?::uuid", UUID.class, id)).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT employee_id FROM regularization_requests WHERE id = ?::uuid", UUID.class, id)).isEqualTo(employee.id());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'regularization.request_on_behalf' AND entity_id = ?", UUID.class, id)).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT after->>'onBehalfOf' FROM audit_log WHERE action = 'regularization.request_on_behalf' AND entity_id = ?", String.class, id)).isEqualTo(employee.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/attendance?tab=regularization'", Integer.class, employee.id())).isEqualTo(1);
        // the employee sees it in their own list; the one who raised it cannot approve it, another approver can
        mockMvc.perform(get("/api/v1/attendance/regularizations?scope=mine").cookie(employee.cookie())).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + id + "/decision")).cookie(secondHr.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
        // the form's prefill for another employee: approvers only, same business only
        mockMvc.perform(get("/api/v1/attendance/mine/days/" + day).queryParam("employeeId", employee.id().toString()).cookie(colleague.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/attendance/mine/days/" + day).queryParam("employeeId", outsider.id().toString()).cookie(hr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/attendance/mine/days/" + day).queryParam("employeeId", employee.id().toString()).cookie(hr.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkInLocal").value(day + "T09:30"));
    }

    @Test
    void aRangeMakesOneRequestPerDayAndReportsEachDay() throws Exception {
        Person employee = person(employeeRole);
        LocalDate first = workday(12), last = first.plusDays(6); // always covers a weekend
        JsonNode days = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, last, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(days.size()).isEqualTo(7);
        int created = 0;
        for (JsonNode day : days) if (day.get("ok").asBoolean()) created++;
        assertThat(created).isBetween(4, 5); // weekdays only (a holiday could remove one more)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT batch_id) FROM regularization_requests WHERE employee_id = ? AND batch_id IS NOT NULL", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT to_char(proposed_out AT TIME ZONE 'Asia/Kolkata', 'HH24:MI') FROM regularization_requests WHERE employee_id = ? LIMIT 1", String.class, employee.id())).isEqualTo("18:30");
        // the same range again: every day is now pending, none is created
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, last, null))).andExpect(status().isUnprocessableEntity());
        // a range is limited in length and order
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, first.plusDays(40), null))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, first.minusDays(1), null))).andExpect(status().isBadRequest());
    }

    @Test
    void theMonthlyLimitIsCountedPerDayInARange() throws Exception {
        Person employee = person(employeeRole);
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.regularization', '{\"monthlyLimit\":2}', now()) ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value", data.business());
        LocalDate first = workday(12), last = first.plusDays(6);
        JsonNode days = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, last, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
        boolean limitReported = false;
        for (JsonNode day : days) if (!day.get("ok").asBoolean() && day.get("message").asText().startsWith("They have") == false && day.get("message").asText().startsWith("You have used all 2")) limitReported = true;
        // within one month at most two are made, and the days over the limit say so
        assertThat(jdbc.queryForObject("SELECT max(c) FROM (SELECT count(*) c FROM regularization_requests WHERE employee_id = ? GROUP BY date_trunc('month', work_date)) x", Integer.class, employee.id())).isLessThanOrEqualTo(2);
        assertThat(limitReported).isTrue();
    }

    @Test
    void aRangeOnBehalfNeedsApproverRightsAndCarriesTheSubject() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate first = workday(12), last = first.plusDays(2);
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, last, hr.id()))).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(hr.cookie()).content(absentBody(first, last, employee.id()))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ? AND created_by = ? AND batch_id IS NOT NULL", Integer.class, employee.id(), hr.id()))
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ?", Integer.class, hr.id())).isZero();
    }

    @Test
    void financeLostTheRegularizationApproval() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_templates WHERE key IN ('fm', 'fe') AND 'appr.reg' = ANY(permissions)", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_templates WHERE key IN ('owner', 'hrm', 'hre') AND 'appr.reg' = ANY(permissions)", Integer.class)).isEqualTo(3);
    }

    int mailsTo(Person person, String subject) {
        return jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject = ?", Integer.class, person.code().toLowerCase() + "@example.com", subject);
    }

    @Test
    void approversGetOneEmailForANewRequestAndTheEmployeeGetsTheDecisionEmail() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate day = workday(2);
        punchIn(employee, day, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:00", forgotCheckOut, ""))).andExpect(status().isCreated());
        assertThat(mailsTo(hr, "Regularization request waiting for your decision")).isEqualTo(1);
        assertThat(mailsTo(employee, "Regularization request waiting for your decision")).isZero(); // the employee is not an approver
        assertThat(jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Regularization request waiting%'", String.class, hr.code().toLowerCase() + "@example.com"))
                .contains("asked to regularize attendance");
        // a refused request sends no mail
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(day, "19:30", forgotCheckOut, ""))).andExpect(status().isConflict());
        assertThat(mailsTo(hr, "Regularization request waiting for your decision")).isEqualTo(1);
        mockMvc.perform(write(put("/api/v1/attendance/regularizations/" + idOf(employee, day) + "/decision")).cookie(hr.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE '%approved'", Integer.class, employee.code().toLowerCase() + "@example.com")).isEqualTo(1);
    }

    @Test
    void aRangeSendsTheApproversOneEmailNotOnePerDay() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate first = workday(12), last = first.plusDays(2);
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(absentBody(first, last, null))).andExpect(status().isCreated());
        assertThat(mailsTo(hr, "Regularization request waiting for your decision")).isEqualTo(1);
    }

    @Test
    void autoApproveIsOffByDefaultAndOnlyApprovesRequestsAnApproverRaisedForAnEmployee() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole), secondHr = person(hrRole);
        LocalDate day = workday(2), otherDay = workday(3);
        if (day.equals(otherDay)) otherDay = workday(4);
        punchIn(employee, day, "09:30");
        punchIn(employee, otherDay, "09:30");
        mockMvc.perform(get("/api/v1/attendance/regularization-rules").cookie(hr.cookie())).andExpect(jsonPath("$.data.autoApprove").value(false));
        // off: an on-behalf request waits for a decision
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(hr.cookie()).content(withEmployee(missedCheckOut(day, "19:00", forgotCheckOut, ""), employee.id().toString()))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, idOf(employee, day))).isEqualTo("PENDING");
        // only an approver can turn it on
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(employee.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'regularization.settings' AND after->>'autoApprove' = 'true'", Integer.class)).isGreaterThanOrEqualTo(1);
        // on: the employee's own request still waits (the approval must be a second person's decision)
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(employee.cookie()).content(missedCheckOut(otherDay, "19:00", forgotCheckOut, ""))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, idOf(employee, otherDay))).isEqualTo("PENDING");
        // on: HR raising one for the employee approves it at once, with the audit trail and the decision mail
        LocalDate thirdDay = workday(5);
        while (thirdDay.equals(day) || thirdDay.equals(otherDay)) thirdDay = thirdDay.minusDays(1);
        while (AttendanceEngine.dowOf(thirdDay) == 0 || AttendanceEngine.dowOf(thirdDay) == 6) thirdDay = thirdDay.minusDays(1);
        punchIn(employee, thirdDay, "09:30");
        mockMvc.perform(write(post("/api/v1/attendance/regularizations")).cookie(secondHr.cookie()).content(withEmployee(missedCheckOut(thirdDay, "19:00", forgotCheckOut, ""), employee.id().toString()))).andExpect(status().isCreated());
        String id = idOf(employee, thirdDay);
        assertThat(jdbc.queryForObject("SELECT status::text FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND source = 'REGULARIZATION' AND kind = 'OUT'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT after->>'autoApproved' FROM audit_log WHERE action = 'regularization.approve' AND entity_id = ?", String.class, id)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'regularization.approve' AND entity_id = ?", UUID.class, id)).isEqualTo(secondHr.id());
        // the approver gets no "waiting" mail for a request that was approved at once
        assertThat(mailsTo(hr, "Regularization request waiting for your decision")).isEqualTo(1); // only for the employee's own request; none for the auto-approved one
    }
}
