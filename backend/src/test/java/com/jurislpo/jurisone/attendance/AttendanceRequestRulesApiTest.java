package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * The rules the three attendance requests (regularization, On Duty, Remote Work) share: who may decide (Admin own, HR via manager / Admin), auto-approve
 * following the business setting, the monthly limits, half-day remote work, editing a pending request, and the pending counts.
 */
class AttendanceRequestRulesApiTest extends IntegrationTest {

    static final String ZONE = "Asia/Kolkata";
    static final String ON_DUTY = "/api/v1/attendance/on-duty-requests";
    static final String REMOTE = "/api/v1/attendance/remote-work-requests";
    static final String REGULARIZATION = "/api/v1/attendance/regularizations";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole, hrRole, adminRole, reason;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("request-rules-test");
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        adminRole = data.role("owner", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "roles.manage");
        jdbc.update("""
            INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', '{"trackingStartDate":"2026-04-01","lockDayOfNextMonth":28}', now())
            ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value""", data.business());
        jdbc.update("DELETE FROM settings WHERE business_id = ? AND key = 'attendance.regularization'", data.business());
        jdbc.update("DELETE FROM on_duty_requests WHERE business_id = ?", data.business());
        jdbc.update("DELETE FROM holidays WHERE business_id = ?", data.business());
        jdbc.update("DELETE FROM remote_work_requests WHERE employee_id IN (SELECT id FROM employees WHERE business_id = ?)", data.business());
        jdbc.update("DELETE FROM regularization_requests WHERE employee_id IN (SELECT id FROM employees WHERE business_id = ?)", data.business());
        jdbc.update("DELETE FROM regularization_reasons WHERE business_id = ?", data.business());
        reason = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_reasons (id, business_id, label, requires_description, sort_order) VALUES (?, ?, 'Forgot to check-out', false, 1)", reason, data.business());
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 6000;

    record Person(UUID id, String code, Cookie cookie) {}

    Person person(TestData in, UUID role) throws Exception {
        String code = "JR-" + (++sequence) + (int) (Math.random() * 1000);
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

    static boolean isWorkday(LocalDate day) {
        return AttendanceEngine.dowOf(day) != 0 && AttendanceEngine.dowOf(day) != 6;
    }

    /** Distinct working days at least two days back, newest first (they may cross a month). */
    static List<LocalDate> workdays(int count) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate day = today().minusDays(2); days.size() < count; day = day.minusDays(1)) if (isWorkday(day)) days.add(day);
        return days;
    }

    /** Working days of ONE calendar month (the month of the newest), at most {@code count}. */
    static List<LocalDate> workdaysOfOneMonth(int count) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate newest = workdays(1).get(0);
        for (LocalDate day = newest; day.getMonth() == newest.getMonth() && days.size() < count; day = day.minusDays(1)) if (isWorkday(day)) days.add(day);
        return days;
    }

    String onDutyBody(LocalDate day, String extra) {
        return "{\"date\":\"" + day + "\",\"part\":\"FULL\",\"location\":\"Client\",\"reason\":\"Client visit\"" + extra + "}";
    }

    String remoteBody(LocalDate from, LocalDate to, String extra) {
        return "{\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"reason\":\"Internet repair\"" + extra + "}";
    }

    String regularizationBody(LocalDate day, String checkOut) {
        return "{\"date\":\"" + day + "\",\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T" + checkOut + "\",\"reasonId\":\"" + reason + "\",\"description\":\"\"}";
    }

    void punchIn(Person person, LocalDate day) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB')", UUID.randomUUID(), person.id(), Timestamp.from(AttendanceEngine.atLocal(day, "09:30", ZONE)));
    }

    String idOf(String table, Person person) {
        return jdbc.queryForObject("SELECT id::text FROM " + table + " WHERE employee_id = ? ORDER BY created_at DESC LIMIT 1", String.class, person.id());
    }

    String statusOf(String table, String id) {
        return jdbc.queryForObject("SELECT status::text FROM " + table + " WHERE id = ?::uuid", String.class, id);
    }

    void setRules(Person approver, boolean autoApprove, int onDutyLimit, int remoteLimit) throws Exception {
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(approver.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":"
                + autoApprove + ",\"onDutyMonthlyLimit\":" + onDutyLimit + ",\"remoteWorkMonthlyLimit\":" + remoteLimit + "}")).andExpect(status().isOk());
    }

    JsonNode fetch(String url, Person person) throws Exception {
        return json.readTree(mockMvc.perform(get(url).cookie(person.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    String decision(boolean approve) {
        return "{\"approve\":" + approve + (approve ? "" : ",\"note\":\"no\"") + "}";
    }

    // ───────────────────────── who may decide ─────────────────────────

    @Test
    void anHrPersonsRequestGoesToTheirManagerOrTheAdminAndTheAdminMayDecideTheirOwn() throws Exception {
        Person admin = person(adminRole), hr = person(hrRole), otherHr = person(hrRole), hrWithoutManager = person(hrRole), manager = person(employeeRole), employee = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id IN (?, ?)", manager.id(), hr.id(), employee.id());
        List<LocalDate> days = workdays(1);
        LocalDate day = days.get(0);
        for (Person person : new Person[] {hr, hrWithoutManager, employee, admin}) {
            mockMvc.perform(write(post(ON_DUTY)).cookie(person.cookie()).content(onDutyBody(day, ""))).andExpect(status().isCreated());
        }
        String hrId = idOf("on_duty_requests", hr), noManagerId = idOf("on_duty_requests", hrWithoutManager), employeeId = idOf("on_duty_requests", employee), adminId = idOf("on_duty_requests", admin);

        // HR cannot decide their own; another HR cannot; a person with no approval right cannot
        mockMvc.perform(write(put(ON_DUTY + "/" + hrId + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(ON_DUTY + "/" + hrId + "/decision")).cookie(otherHr.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(ON_DUTY + "/" + hrId + "/decision")).cookie(employee.cookie()).content(decision(true))).andExpect(status().isForbidden());
        assertThat(statusOf("on_duty_requests", hrId)).isEqualTo("PENDING");
        // the Admin may decide the request of an HR person who has a manager, at any time
        Person hrWithManager = person(hrRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), hrWithManager.id());
        mockMvc.perform(write(post(ON_DUTY)).cookie(hrWithManager.cookie()).content(onDutyBody(day, ""))).andExpect(status().isCreated());
        String withManagerId = idOf("on_duty_requests", hrWithManager);
        mockMvc.perform(write(put(ON_DUTY + "/" + withManagerId + "/decision")).cookie(admin.cookie()).content(decision(true))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT decided_by FROM on_duty_requests WHERE id = ?::uuid", UUID.class, withManagerId)).isEqualTo(admin.id());
        // the manager decides HR's request, and ONLY that kind of request: an ordinary employee's request is not theirs to decide
        mockMvc.perform(write(put(ON_DUTY + "/" + employeeId + "/decision")).cookie(manager.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(ON_DUTY + "/" + hrId + "/decision")).cookie(manager.cookie()).content(decision(true))).andExpect(status().isOk());
        assertThat(statusOf("on_duty_requests", hrId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM on_duty_requests WHERE id = ?::uuid", UUID.class, hrId)).isEqualTo(manager.id());
        // an ordinary employee's request: any approver except the employee; the manager is not one
        mockMvc.perform(write(put(ON_DUTY + "/" + employeeId + "/decision")).cookie(otherHr.cookie()).content(decision(true))).andExpect(status().isOk());
        // HR without a manager: the Admin decides, other HR cannot
        mockMvc.perform(write(put(ON_DUTY + "/" + noManagerId + "/decision")).cookie(otherHr.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(ON_DUTY + "/" + noManagerId + "/decision")).cookie(admin.cookie()).content(decision(true))).andExpect(status().isOk());
        // the Admin may decide their own request
        mockMvc.perform(write(put(ON_DUTY + "/" + adminId + "/decision")).cookie(admin.cookie()).content(decision(true))).andExpect(status().isOk());
        assertThat(statusOf("on_duty_requests", adminId)).isEqualTo("APPROVED");
    }

    @Test
    void bulkDecisionsFollowTheSameRulePerRequest() throws Exception {
        Person hr = person(hrRole), otherHr = person(hrRole), manager = person(employeeRole), employee = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id IN (?, ?)", manager.id(), hr.id(), employee.id());
        LocalDate day = workdays(1).get(0);
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(day, ""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(day, ""))).andExpect(status().isCreated());
        String ids = "\"" + idOf("on_duty_requests", hr) + "\",\"" + idOf("on_duty_requests", employee) + "\"";
        JsonNode byManager = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(manager.cookie()).content("{\"ids\":[" + ids + "],\"approve\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(byManager.get(0).get("ok").asBoolean()).isTrue();
        assertThat(byManager.get(1).get("ok").asBoolean()).isFalse();
        // a person with no approval right and no reports is refused outright
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(employee.cookie()).content("{\"ids\":[" + ids + "],\"approve\":true}")).andExpect(status().isForbidden());
        assertThat(statusOf("on_duty_requests", idOf("on_duty_requests", employee))).isEqualTo("PENDING");
        mockMvc.perform(write(post("/api/v1/attendance/on-duty-decisions")).cookie(otherHr.cookie()).content("{\"ids\":[" + ids + "],\"approve\":true}")).andExpect(status().isOk());
        assertThat(statusOf("on_duty_requests", idOf("on_duty_requests", employee))).isEqualTo("APPROVED");
    }

    @Test
    void remoteWorkAndRegularizationFollowTheSameRule() throws Exception {
        Person admin = person(adminRole), hr = person(hrRole), otherHr = person(hrRole), manager = person(employeeRole), employee = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id IN (?, ?)", manager.id(), hr.id(), employee.id());
        LocalDate day = workdays(1).get(0);
        // remote work
        mockMvc.perform(write(post(REMOTE)).cookie(hr.cookie()).content(remoteBody(day, day, ""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(admin.cookie()).content(remoteBody(day, day, ""))).andExpect(status().isCreated());
        String hrRemote = idOf("remote_work_requests", hr), adminRemote = idOf("remote_work_requests", admin);
        mockMvc.perform(write(put(REMOTE + "/" + hrRemote + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(REMOTE + "/" + hrRemote + "/decision")).cookie(otherHr.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REMOTE + "/" + hrRemote + "/decision")).cookie(manager.cookie()).content(decision(true))).andExpect(status().isOk());
        mockMvc.perform(write(put(REMOTE + "/" + adminRemote + "/decision")).cookie(admin.cookie()).content(decision(true))).andExpect(status().isOk());
        // regularization
        punchIn(hr, day);
        punchIn(admin, day);
        mockMvc.perform(write(post(REGULARIZATION)).cookie(hr.cookie()).content(regularizationBody(day, "19:00"))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REGULARIZATION)).cookie(admin.cookie()).content(regularizationBody(day, "19:00"))).andExpect(status().isCreated());
        String hrReg = idOf("regularization_requests", hr), adminReg = idOf("regularization_requests", admin);
        mockMvc.perform(write(put(REGULARIZATION + "/" + hrReg + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(REGULARIZATION + "/" + hrReg + "/decision")).cookie(otherHr.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REGULARIZATION + "/" + hrReg + "/decision")).cookie(employee.cookie()).content(decision(true))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(REGULARIZATION + "/" + hrReg + "/decision")).cookie(manager.cookie()).content(decision(true))).andExpect(status().isOk());
        assertThat(statusOf("regularization_requests", hrReg)).isEqualTo("APPROVED");
        mockMvc.perform(write(put(REGULARIZATION + "/" + adminReg + "/decision")).cookie(admin.cookie()).content(decision(true))).andExpect(status().isOk());
        // bulk regularization decisions follow the rule too
        mockMvc.perform(write(post("/api/v1/attendance/regularization-decisions")).cookie(employee.cookie()).content("{\"ids\":[\"" + hrReg + "\"],\"approve\":true}")).andExpect(status().isForbidden());
    }

    @Test
    void theManagerOfAnHrPersonSeesAndCountsOnlyThatPersonsRequests() throws Exception {
        Person hr = person(hrRole), otherHr = person(hrRole), manager = person(employeeRole), plainManager = person(employeeRole), employee = person(employeeRole), report = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id IN (?, ?)", manager.id(), hr.id(), employee.id());
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", plainManager.id(), report.id());
        LocalDate day = workdays(1).get(0);
        for (Person person : new Person[] {hr, employee, report}) {
            mockMvc.perform(write(post(ON_DUTY)).cookie(person.cookie()).content(onDutyBody(day, ""))).andExpect(status().isCreated());
        }
        String hrId = idOf("on_duty_requests", hr), employeeId = idOf("on_duty_requests", employee);
        JsonNode managerView = fetch(ON_DUTY + "?scope=all", manager);
        assertThat(managerView.get("total").asInt()).isEqualTo(1);
        assertThat(managerView.get("items").get(0).get("id").asText()).isEqualTo(hrId);
        assertThat(managerView.get("decidable").toString()).contains(hrId);
        JsonNode hrView = fetch(ON_DUTY + "?scope=all", otherHr);
        assertThat(hrView.get("total").asInt()).isEqualTo(3);
        assertThat(hrView.get("decidable").toString()).contains(employeeId).doesNotContain(hrId);
        // a manager of ordinary people, and anybody without reports, get 403 for the whole-business scope
        mockMvc.perform(get(ON_DUTY + "?scope=all").cookie(plainManager.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get(ON_DUTY + "?scope=all").cookie(employee.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get(REGULARIZATION + "?scope=all").cookie(plainManager.cookie())).andExpect(status().isForbidden());
        // the details of HR's request: the manager sees it, the manager of someone else does not
        mockMvc.perform(get(ON_DUTY + "/" + hrId).cookie(manager.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(ON_DUTY + "/" + hrId).cookie(plainManager.cookie())).andExpect(status().isNotFound());
        // pending counts: from the server's rule
        assertThat(fetch("/api/v1/attendance/pending-counts", manager).get("total").asInt()).isEqualTo(1);
        assertThat(fetch("/api/v1/attendance/pending-counts", manager).get("onDuty").asInt()).isEqualTo(1);
        assertThat(fetch("/api/v1/attendance/pending-counts", otherHr).get("onDuty").asInt()).isEqualTo(2); // the employee's and the report's, not the HR person's
        assertThat(fetch("/api/v1/attendance/pending-counts", employee).get("total").asInt()).isZero();
        assertThat(fetch("/api/v1/attendance/pending-counts", plainManager).get("total").asInt()).isZero();
        mockMvc.perform(get("/api/v1/attendance/pending-counts")).andExpect(status().isUnauthorized());
        // the sidebar badge carries the same number
        assertThat(fetch("/api/v1/shell", otherHr).get("badges").get("attendanceRequests").asInt()).isEqualTo(2);
        assertThat(fetch("/api/v1/shell", employee).get("badges").get("attendanceRequests").asInt()).isZero();
    }

    // ───────────────────────── auto-approve follows the business setting ─────────────────────────

    @Test
    void raisingOnBehalfIsApprovedAtOnceOnlyWhenTheBusinessSettingIsOn() throws Exception {
        Person hr = person(hrRole), otherHr = person(hrRole), manager = person(employeeRole), employee = person(employeeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), otherHr.id());
        List<LocalDate> days = workdays(4);
        // off (the default): both wait for a normal decision
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(0), ",\"employeeId\":\"" + employee.id() + "\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(hr.cookie()).content(remoteBody(days.get(1), days.get(1), ",\"employeeId\":\"" + employee.id() + "\""))).andExpect(status().isCreated());
        assertThat(statusOf("on_duty_requests", idOf("on_duty_requests", employee))).isEqualTo("PENDING");
        assertThat(statusOf("remote_work_requests", idOf("remote_work_requests", employee))).isEqualTo("PENDING");
        // the person who raised it is not the one to decide it (the employee's pending request is for the other approvers)
        assertThat(jdbc.queryForObject("SELECT created_by FROM on_duty_requests WHERE employee_id = ?", UUID.class, employee.id())).isEqualTo(hr.id());
        // on: approved as the approver
        setRules(hr, true, 31, 31);
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(2), ",\"employeeId\":\"" + employee.id() + "\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(hr.cookie()).content(remoteBody(days.get(3), days.get(3), ",\"employeeId\":\"" + employee.id() + "\""))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM on_duty_requests WHERE employee_id = ? AND status = 'APPROVED'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_work_requests WHERE employee_id = ? AND status = 'APPROVED'", Integer.class, employee.id())).isEqualTo(1);
        // on, but the person it is raised for is an HR person with a manager: HR is not that person's approver, so it stays pending for the manager
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(0), ",\"employeeId\":\"" + otherHr.id() + "\""))).andExpect(status().isCreated());
        assertThat(statusOf("on_duty_requests", idOf("on_duty_requests", otherHr))).isEqualTo("PENDING");
        mockMvc.perform(write(put(ON_DUTY + "/" + idOf("on_duty_requests", otherHr) + "/decision")).cookie(manager.cookie()).content(decision(true))).andExpect(status().isOk());
        // HR raising their own request is never auto-approved
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(1), ""))).andExpect(status().isCreated());
        assertThat(statusOf("on_duty_requests", idOf("on_duty_requests", hr))).isEqualTo("PENDING");
    }

    // ───────────────────────── monthly limits ─────────────────────────

    @Test
    void theMonthlyOnDutyAndRemoteWorkLimitsAreEnforcedAndConfigured() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole);
        List<LocalDate> days = workdaysOfOneMonth(3);
        assumeTrue(days.size() >= 3, "needs three working days in the month so far");
        // only an approver saves the limits; they must be 1-31; an older client that does not send them keeps what is saved
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(employee.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":false,\"onDutyMonthlyLimit\":1}"))
                .andExpect(status().isForbidden());
        for (String bad : new String[] {"0", "32", "-3"}) {
            mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":false,\"onDutyMonthlyLimit\":" + bad + "}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":false,\"onDutyMonthlyLimit\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/attendance/regularization-rules").cookie(employee.cookie())).andExpect(jsonPath("$.data.onDutyMonthlyLimit").value(31)).andExpect(jsonPath("$.data.remoteWorkMonthlyLimit").value(31));
        setRules(hr, false, 2, 1);
        mockMvc.perform(write(put("/api/v1/attendance/regularization-rules")).cookie(hr.cookie()).content("{\"backDateLimitDays\":30,\"monthlyLimit\":30,\"attachmentMandatory\":false,\"autoApprove\":false}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/regularization-rules").cookie(employee.cookie())).andExpect(jsonPath("$.data.onDutyMonthlyLimit").value(2)).andExpect(jsonPath("$.data.remoteWorkMonthlyLimit").value(1));

        // On Duty: two days fit, the third is refused; a withdrawn one frees its day
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(0), ""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(1), ""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(2), ""))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("You have used all 2 On Duty days for " + com.jurislpo.jurisone.engine.Format.monthLabel(days.get(0).toString().substring(0, 7)) + "."));
        mockMvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(ON_DUTY + "/" + idOf("on_duty_requests", employee))).cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(2), ""))).andExpect(status().isCreated());
        // On Duty raised for someone else says "They have used all"
        Person other = person(employeeRole);
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(0), ",\"employeeId\":\"" + other.id() + "\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(1), ",\"employeeId\":\"" + other.id() + "\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(2), ",\"employeeId\":\"" + other.id() + "\""))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("They have used all 2 On Duty days")));

        // Remote work: one day a month; a half day counts 0.5
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(days.get(0), days.get(0), ""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(days.get(1), days.get(1), ""))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("You have used all 1 remote work days")));
        // nothing used yet but the request alone is longer than the limit: it says so (it used to read "used all 1" with nothing used)
        Person longRequest = person(employeeRole);
        long asked = java.time.temporal.ChronoUnit.DAYS.between(days.get(2), days.get(0)) + 1;
        mockMvc.perform(write(post(REMOTE)).cookie(longRequest.cookie()).content(remoteBody(days.get(2), days.get(0), ""))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("The limit is 1 remote work days for " + com.jurislpo.jurisone.engine.Format.monthLabel(days.get(0).toString().substring(0, 7)) + ", but this request is for " + asked + "."));
        Person halfPerson = person(employeeRole);
        mockMvc.perform(write(post(REMOTE)).cookie(halfPerson.cookie()).content(remoteBody(days.get(0), days.get(0), ",\"part\":\"FIRST_HALF\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(halfPerson.cookie()).content(remoteBody(days.get(1), days.get(1), ",\"part\":\"FIRST_HALF\""))).andExpect(status().isCreated());
        mockMvc.perform(write(post(REMOTE)).cookie(halfPerson.cookie()).content(remoteBody(days.get(2), days.get(2), ",\"part\":\"FIRST_HALF\""))).andExpect(status().isUnprocessableEntity());
        // another business's requests do not count against this one (isolation): a stranger in another business has their own count
        TestData stranger = data.inBusiness("request-rules-other");
        Person outsider = person(stranger, stranger.role("employee", "self", "dir"));
        mockMvc.perform(write(post(REMOTE)).cookie(outsider.cookie()).content(remoteBody(days.get(0), days.get(0), ""))).andExpect(status().isCreated());
    }

    // ───────────────────────── half-day remote work ─────────────────────────

    @Test
    void halfDayRemoteWorkIsOneDayAndTheOtherHalfStillNeedsWork() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole);
        LocalDate day = workdays(2).get(1);
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(day, day.plusDays(1), ",\"part\":\"FIRST_HALF\""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(day, day, ",\"part\":\"MIDDLE\""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(day, day, ",\"part\":\"FIRST_HALF' OR '1'='1\""))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(day, day, ",\"part\":\"SECOND_HALF\""))).andExpect(status().isCreated());
        String id = idOf("remote_work_requests", employee);
        assertThat(jdbc.queryForObject("SELECT part FROM remote_work_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("SECOND_HALF");
        mockMvc.perform(get(REMOTE).queryParam("scope", "mine").cookie(employee.cookie())).andExpect(jsonPath("$.data.items[0].part").value("SECOND_HALF"));
        // pending changes nothing; approved: the day is a half day (no punches, so the other half was not worked) labelled as half-day remote
        JsonNode pending = summary(employee, day).get("days").get(0);
        assertThat(pending.get("label").asText()).doesNotContain("Remote");
        mockMvc.perform(write(put(REMOTE + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isOk());
        JsonNode approved = summary(employee, day).get("days").get(0);
        assertThat(approved.get("label").asText()).isEqualTo("Remote · half day");
        assertThat(approved.get("status").asText()).isEqualTo("HALF_DAY");
        // with half a day of real work the day is present
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')", UUID.randomUUID(), employee.id(),
                Timestamp.from(AttendanceEngine.atLocal(day, "09:30", ZONE)), UUID.randomUUID(), employee.id(), Timestamp.from(AttendanceEngine.atLocal(day, "14:00", ZONE)));
        assertThat(summary(employee, day).get("days").get(0).get("status").asText()).isEqualTo("PRESENT");
        // a full-day request still shows plain "Remote"
        Person fullPerson = person(employeeRole);
        mockMvc.perform(write(post(REMOTE)).cookie(fullPerson.cookie()).content(remoteBody(day, day, ""))).andExpect(status().isCreated());
        mockMvc.perform(write(put(REMOTE + "/" + idOf("remote_work_requests", fullPerson) + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isOk());
        assertThat(summary(fullPerson, day).get("days").get(0).get("label").asText()).isEqualTo("Remote");
    }

    JsonNode summary(Person person, LocalDate day) throws Exception {
        return fetch("/api/v1/attendance/summary?view=tabular&from=" + day + "&to=" + day, person);
    }

    // ───────────────────────── edit a pending request ─────────────────────────

    @Test
    void onlyTheRequesterChangesAPendingOnDutyRequestAndEveryRuleAppliesAgain() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole), colleague = person(employeeRole);
        TestData stranger = data.inBusiness("request-rules-other");
        Person outsider = person(stranger, stranger.role("employee", "self", "dir"));
        List<LocalDate> days = workdays(3);
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(0), ""))).andExpect(status().isCreated());
        String id = idOf("on_duty_requests", employee);
        String change = "{\"date\":\"" + days.get(1) + "\",\"part\":\"FIRST_HALF\",\"location\":\"Pune site\",\"reason\":\"Changed plan\"}";
        // not the requester: not found (another person, an approver, another business)
        for (Person other : new Person[] {colleague, hr, outsider}) {
            mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(other.cookie()).content(change)).andExpect(status().isNotFound());
        }
        // hostile and invalid input
        mockMvc.perform(write(patch(ON_DUTY + "/1'--")).cookie(employee.cookie()).content(change)).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + days.get(1) + "\",\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + days.get(1) + "\",\"reason\":\"" + "x".repeat(501) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"2026-13-45\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + days.get(1) + "\",\"part\":\"HALF' --\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + today().minusDays(90) + "\",\"reason\":\"x\"}")).andExpect(status().isUnprocessableEntity());
        LocalDate weekend = days.get(0);
        while (isWorkday(weekend)) weekend = weekend.minusDays(1);
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + weekend + "\",\"reason\":\"x\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT reason FROM on_duty_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("Client visit");
        // the day already has another open request: refused; the request itself does not block its own day
        mockMvc.perform(write(post(ON_DUTY)).cookie(employee.cookie()).content(onDutyBody(days.get(2), ""))).andExpect(status().isCreated());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + days.get(2) + "\",\"reason\":\"x\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content("{\"date\":\"" + days.get(0) + "\",\"reason\":\"Same day, new words\"}")).andExpect(status().isOk());
        // a real change
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT part || '|' || location || '|' || reason || '|' || work_date FROM on_duty_requests WHERE id = ?::uuid", String.class, id))
                .isEqualTo("FIRST_HALF|Pune site|Changed plan|" + days.get(1));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'attendance.on_duty_edit' AND entity_id = ? LIMIT 1", UUID.class, id)).isEqualTo(employee.id());
        // a decided request cannot be changed
        mockMvc.perform(write(put(ON_DUTY + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isOk());
        mockMvc.perform(write(patch(ON_DUTY + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isUnprocessableEntity());
        // what Admin / HR raised for someone is changed by the one who raised it, not by the employee
        mockMvc.perform(write(post(ON_DUTY)).cookie(hr.cookie()).content(onDutyBody(days.get(0), ",\"employeeId\":\"" + colleague.id() + "\""))).andExpect(status().isCreated());
        String raised = idOf("on_duty_requests", colleague);
        assertThat(statusOf("on_duty_requests", raised)).isEqualTo("PENDING");
        mockMvc.perform(write(patch(ON_DUTY + "/" + raised)).cookie(colleague.cookie()).content(change)).andExpect(status().isNotFound());
        mockMvc.perform(write(patch(ON_DUTY + "/" + raised)).cookie(hr.cookie()).content(change)).andExpect(status().isOk());
    }

    @Test
    void onlyTheRequesterChangesAPendingRemoteWorkRequest() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole), colleague = person(employeeRole);
        List<LocalDate> days = workdays(4);
        LocalDate first = days.get(3), second = days.get(2);
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(first, first, ""))).andExpect(status().isCreated());
        String id = idOf("remote_work_requests", employee);
        String change = "{\"fromDate\":\"" + first + "\",\"toDate\":\"" + second + "\",\"reason\":\"Longer repair\",\"part\":\"FULL\"}";
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(colleague.cookie()).content(change)).andExpect(status().isNotFound());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(hr.cookie()).content(change)).andExpect(status().isNotFound());
        mockMvc.perform(write(patch(REMOTE + "/not-a-uuid")).cookie(employee.cookie()).content(change)).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content("{\"fromDate\":\"" + first + "\",\"toDate\":\"" + second + "\",\"reason\":\"x\",\"part\":\"FIRST_HALF\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content("{\"fromDate\":\"" + second + "\",\"toDate\":\"" + first + "\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content("{\"fromDate\":\"" + first + "\",\"toDate\":\"" + second + "\",\"reason\":\"\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content("{\"fromDate\":\"' OR 1=1 --\",\"toDate\":\"" + second + "\",\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        // an overlap with another open request of the same person is refused; the request does not overlap itself
        LocalDate later = days.get(0);
        mockMvc.perform(write(post(REMOTE)).cookie(employee.cookie()).content(remoteBody(later, later, ""))).andExpect(status().isCreated());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content("{\"fromDate\":\"" + first + "\",\"toDate\":\"" + later + "\",\"reason\":\"x\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT to_date::text || '|' || reason FROM remote_work_requests WHERE id = ?::uuid", String.class, id)).isEqualTo(second + "|Longer repair");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.edit' AND entity_id = ? AND actor_id = ?", Integer.class, id, employee.id())).isEqualTo(1);
        mockMvc.perform(write(put(REMOTE + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isOk());
        mockMvc.perform(write(patch(REMOTE + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void onlyTheRequesterChangesAPendingRegularizationRequest() throws Exception {
        Person hr = person(hrRole), employee = person(employeeRole), colleague = person(employeeRole);
        TestData stranger = data.inBusiness("request-rules-other");
        Person outsider = person(stranger, stranger.role("employee", "self", "dir"));
        LocalDate day = workdays(1).get(0);
        punchIn(employee, day);
        mockMvc.perform(write(post(REGULARIZATION)).cookie(employee.cookie()).content(regularizationBody(day, "18:00"))).andExpect(status().isCreated());
        String id = idOf("regularization_requests", employee);
        Integer before = jdbc.queryForObject("SELECT new_minutes FROM regularization_requests WHERE id = ?::uuid", Integer.class, id);
        String change = "{\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T20:00\",\"reasonId\":\"" + reason + "\",\"description\":\"Client call ran late\"}";
        for (Person other : new Person[] {colleague, hr, outsider}) {
            mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(other.cookie()).content(change)).andExpect(status().isNotFound());
        }
        mockMvc.perform(write(patch(REGULARIZATION + "/' OR 1=1")).cookie(employee.cookie()).content(change)).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content("{\"kind\":\"BOGUS\",\"checkOut\":\"" + day + "T20:00\",\"reasonId\":\"" + reason + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content("{\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T20:00\",\"reasonId\":\"1;DROP TABLE x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content("{\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T20:00\",\"reasonId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content("{\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T08:00\",\"reasonId\":\"" + reason + "\"}")).andExpect(status().is4xxClientError());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content("{\"kind\":\"MISSED_CHECKOUT\",\"checkOut\":\"" + day + "T20:00\",\"reasonId\":\"" + reason + "\",\"description\":\"" + "d".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT new_minutes FROM regularization_requests WHERE id = ?::uuid", Integer.class, id)).isEqualTo(before);
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT new_minutes FROM regularization_requests WHERE id = ?::uuid", Integer.class, id)).isEqualTo(before + 120);
        assertThat(jdbc.queryForObject("SELECT description FROM regularization_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("Client call ran late");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'regularization.edit' AND entity_id = ? AND actor_id = ?", Integer.class, id, employee.id())).isEqualTo(1);
        mockMvc.perform(write(put(REGULARIZATION + "/" + id + "/decision")).cookie(hr.cookie()).content(decision(true))).andExpect(status().isOk());
        mockMvc.perform(write(patch(REGULARIZATION + "/" + id)).cookie(employee.cookie()).content(change)).andExpect(status().isUnprocessableEntity());
        // the list says who raised it, so the app can offer the edit
        mockMvc.perform(get(REGULARIZATION + "?scope=mine").cookie(employee.cookie())).andExpect(jsonPath("$.data.items[0].createdBy").value(employee.id().toString()));
    }
}
