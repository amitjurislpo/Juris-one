package com.jurislpo.jurisone.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** Remote work requests: create, list (mine / team), decide, withdraw, the attendance effect, and the refusals (hostile input, other people's rows, other businesses, locked month). */
class RemoteWorkApiTest extends IntegrationTest {

    static final String ZONE = "Asia/Kolkata";
    static final String URL = "/api/v1/attendance/remote-work-requests";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID employeeRole, hrRole, financeRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("remote-work-test");
        employeeRole = data.role("employee", "self", "dir");
        hrRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view");
        financeRole = data.role("fm", "self", "dir", "people.view", "pay.view");
        setLockDay(28);
        jdbc.update("DELETE FROM settings WHERE business_id = ? AND key = 'attendance.regularization'", data.business());
        jdbc.update("DELETE FROM remote_work_requests WHERE employee_id IN (SELECT id FROM employees WHERE business_id = ?)", data.business());
    }

    void setLockDay(int lockDayOfNextMonth) {
        jdbc.update("""
            INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.policy', ?::jsonb, now())
            ON CONFLICT (business_id, key) DO UPDATE SET value = EXCLUDED.value""", data.business(), "{\"trackingStartDate\":\"2026-04-01\",\"lockDayOfNextMonth\":" + lockDayOfNextMonth + "}");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 9000;

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

    static String body(LocalDate from, LocalDate to, String reason) {
        return "{\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"reason\":\"" + reason + "\"}";
    }

    String create(Person person, LocalDate from, LocalDate to, String reason) throws Exception {
        String response = mockMvc.perform(write(post(URL)).cookie(person.cookie()).content(body(from, to, reason))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Remote work request added successfully")).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data").get("id").asText();
    }

    JsonNode day(Person person, LocalDate date) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/attendance/summary?from=" + date + "&to=" + date + "&view=tabular").cookie(person.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("days").get(0);
    }

    JsonNode list(Person person, String query) throws Exception {
        return json.readTree(mockMvc.perform(get(URL + query).cookie(person.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    String statusOf(String id) {
        return jdbc.queryForObject("SELECT status::text FROM remote_work_requests WHERE id = ?::uuid", String.class, id);
    }

    @Test
    void anApprovedRemoteDayIsAWorkingDayEvenWithoutAPunch() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate first = workday(4), last = workday(3);
        if (first.isAfter(last)) { LocalDate swap = first; first = last; last = swap; }
        String id = create(employee, first, last, "Internet repair at home");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link LIKE '/attendance%tab=remote-work'", Integer.class, hr.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.request' AND entity_id = ?", Integer.class, id)).isEqualTo(1);

        JsonNode mine = list(employee, "?scope=mine");
        assertThat(mine.get("total").asInt()).isEqualTo(1);
        assertThat(mine.get("canDecide").asBoolean()).isFalse();
        assertThat(mine.get("items").get(0).get("reason").asText()).isEqualTo("Internet repair at home");
        assertThat(mine.get("items").get(0).get("days").asInt()).isEqualTo((int) (last.toEpochDay() - first.toEpochDay()) + 1);
        // pending: the day with no punch is still Absent
        assertThat(day(employee, last).get("status").asText()).isEqualTo("ABSENT");

        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(employee.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true,\"note\":\"ok\"}")).andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT decision_note FROM remote_work_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("ok");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.approve' AND entity_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Remote work request%approved%'", Integer.class, employee.id())).isEqualTo(1);

        JsonNode approvedDay = day(employee, last);
        assertThat(approvedDay.get("status").asText()).isEqualTo("PRESENT");
        assertThat(approvedDay.get("label").asText()).isEqualTo("Remote");
        // already decided: a second decision and a withdrawal are refused
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"x\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(employee.cookie())).andExpect(jsonPath("$.detail").value("Only pending requests can be withdrawn."));
    }

    @Test
    void rejectingNeedsRemarksAndChangesNothing() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate date = workday(3);
        String id = create(employee, date, date, "Plumber visit");
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"   \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"" + "n".repeat(301) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"note\":\"x\"}")).andExpect(status().isBadRequest());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"Need you in office\"}")).andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("DECLINED");
        assertThat(day(employee, date).get("status").asText()).isEqualTo("ABSENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.decline' AND entity_id = ?", Integer.class, id)).isEqualTo(1);
        // after a rejection the same days can be asked for again
        create(employee, date, date, "Plumber visit again");
    }

    @Test
    void onlyTheOwnerWithdrawsAPendingRequestAndDaysCanBeAskedForAgain() throws Exception {
        Person employee = person(employeeRole), other = person(employeeRole);
        LocalDate date = workday(3);
        String id = create(employee, date, date, "Cold");
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(other.cookie())).andExpect(status().isNotFound());
        assertThat(statusOf(id)).isEqualTo("PENDING");
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(employee.cookie())).andExpect(jsonPath("$.message").value("Request withdrawn"));
        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.cancel' AND entity_id = ?", Integer.class, id)).isEqualTo(1);
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(employee.cookie())).andExpect(jsonPath("$.detail").value("Only pending requests can be withdrawn."));
        create(employee, date, date, "Cold again");
    }

    @Test
    void overlappingAndDuplicateRequestsAreRefused() throws Exception {
        Person employee = person(employeeRole), colleague = person(employeeRole);
        LocalDate date = workday(10);
        create(employee, date, date.plusDays(2), "Home renovation");
        // the same range, an overlapping start, a range containing it, a single day inside it
        for (LocalDate[] range : new LocalDate[][] {{date, date.plusDays(2)}, {date.plusDays(2), date.plusDays(4)}, {date.minusDays(3), date.plusDays(5)}, {date.plusDays(1), date.plusDays(1)}}) {
            mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(range[0], range[1], "again"))).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("REMOTE_WORK_ALREADY_REQUESTED"));
        }
        // the day after is free, and so are the same days for somebody else
        create(employee, date.plusDays(3), date.plusDays(3), "next day");
        create(colleague, date, date.plusDays(2), "same days, other person");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_work_requests WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(2);
    }

    @Test
    void theServerRefusesBadDatesReasonsAndRanges() throws Exception {
        Person employee = person(employeeRole);
        LocalDate date = workday(3);
        String valid = "\"reason\":\"Home\"";
        // wrong format, nonexistent day, reversed range, too long a range, missing dates
        for (String dates : new String[] {"\"fromDate\":\"tomorrow\",\"toDate\":\"" + date + "\"", "\"fromDate\":\"2026-13-45\",\"toDate\":\"2026-13-45\"",
                "\"fromDate\":\"" + date + "\",\"toDate\":\"" + date.minusDays(1) + "\"", "\"fromDate\":\"" + date + "\",\"toDate\":\"" + date.plusDays(31) + "\"",
                "\"fromDate\":\"" + date + "\"", "\"toDate\":\"" + date + "\"", "\"fromDate\":\"1' OR '1'='1\",\"toDate\":\"" + date + "\""}) {
            mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("{" + dates + "," + valid + "}")).andExpect(status().isBadRequest());
        }
        // reason: missing, blank, too long
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("{\"fromDate\":\"" + date + "\",\"toDate\":\"" + date + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(date, date, "   "))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(date, date, "r".repeat(501)))).andExpect(status().isBadRequest());
        // not a JSON object at all
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content("[1,2,3]")).andExpect(status().is4xxClientError());
        // 31 days is the limit and is fine; too old and too far ahead are refused
        LocalDate ahead = today().plusDays(20);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(ahead, ahead.plusDays(30), "A month away"))).andExpect(status().isCreated());
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(today().minusDays(33), today().minusDays(33), "Too old")))
                .andExpect(jsonPath("$.detail").value("Requests can be made up to 30 days back."));
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(today().plusDays(500), today().plusDays(500), "Far away")))
                .andExpect(jsonPath("$.detail").value("Requests can be made up to 365 days ahead."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_work_requests WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(1);
        // hostile text in the reason is stored as plain text (bound parameter), never run
        String id = create(employee, date, date, "x'); DROP TABLE remote_work_requests; --<script>alert(1)</script>");
        assertThat(jdbc.queryForObject("SELECT reason FROM remote_work_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("x'); DROP TABLE remote_work_requests; --<script>alert(1)</script>");
        // nobody signed in
        mockMvc.perform(write(post(URL)).content(body(date, date, "x"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aLockedMonthRefusesRequestsAndApprovals() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate date = workday(3);
        String id = create(employee, date, date, "Before the lock");
        // lock every month that ended before this one, and let requests go back a year
        setLockDay(1);
        jdbc.update("INSERT INTO settings (business_id, key, value, updated_at) VALUES (?, 'attendance.regularization', '{\"backDateLimitDays\":365}', now())", data.business());
        LocalDate old = today().minusMonths(2).withDayOfMonth(15);
        while (AttendanceEngine.dowOf(old) == 0 || AttendanceEngine.dowOf(old) == 6) old = old.plusDays(1);
        mockMvc.perform(write(post(URL)).cookie(employee.cookie()).content(body(old, old, "Too late"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Attendance for this month is locked. Ask Finance to adjust LOP in payroll."));
        // a pending request whose month gets locked cannot be approved, but can be rejected
        jdbc.update("UPDATE remote_work_requests SET from_date = ?, to_date = ? WHERE id = ?::uuid", java.sql.Date.valueOf(old), java.sql.Date.valueOf(old), id);
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("That month's attendance is locked — reject this and adjust LOP in payroll instead."));
        assertThat(statusOf(id)).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(hr.cookie()).content("{\"approve\":false,\"note\":\"locked\"}")).andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("DECLINED");
    }

    @Test
    void teamScopeIsEveryoneForHrDirectReportsForAManagerAndRefusedForOthers() throws Exception {
        Person manager = person(employeeRole), report = person(employeeRole), stranger = person(employeeRole), hr = person(hrRole), finance = person(financeRole);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), report.id());
        LocalDate date = workday(3);
        String reportRequest = create(report, date, date, "Report at home");
        create(stranger, date, date, "Stranger at home");
        create(manager, date, date, "Manager at home");

        JsonNode hrView = list(hr, "?scope=team");
        assertThat(hrView.get("total").asInt()).isEqualTo(3);
        assertThat(hrView.get("canDecide").asBoolean()).isTrue();
        JsonNode managerView = list(manager, "?scope=team");
        assertThat(managerView.get("total").asInt()).isEqualTo(1);
        assertThat(managerView.get("items").get(0).get("employeeCode").asText()).isEqualTo(report.code());
        assertThat(managerView.get("canDecide").asBoolean()).isFalse();
        // the scope comes from the session: client-sent manager / employee ids never widen it
        assertThat(list(manager, "?scope=team&managerId=" + stranger.id() + "&employeeId=" + stranger.id()).get("total").asInt()).isZero();
        assertThat(list(manager, "?scope=team&search=Person").get("total").asInt()).isEqualTo(1);
        mockMvc.perform(get(URL + "?scope=team").cookie(stranger.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "?scope=team").cookie(finance.cookie())).andExpect(status().isForbidden());
        // mine is mine, whatever the client sends
        assertThat(list(stranger, "?scope=mine&employeeId=" + report.id()).get("total").asInt()).isEqualTo(1);
        // details: owner, approver and the owner's manager only
        mockMvc.perform(get(URL + "/" + reportRequest).cookie(report.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + reportRequest).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + reportRequest).cookie(manager.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/" + reportRequest).cookie(stranger.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get(URL + "/" + reportRequest).cookie(finance.cookie())).andExpect(status().isNotFound());
        // a manager can look but not decide
        mockMvc.perform(write(put(URL + "/" + reportRequest + "/decision")).cookie(manager.cookie()).content("{\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(manager.cookie()).content("{\"ids\":[\"" + reportRequest + "\"],\"approve\":true}")).andExpect(status().isForbidden());
        assertThat(statusOf(reportRequest)).isEqualTo("PENDING");
        mockMvc.perform(get(URL + "?scope=team")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherBusinessesNeverSeeOrDecideARequest() throws Exception {
        Person employee = person(employeeRole);
        TestData otherBusiness = data.inBusiness("remote-work-other");
        Person outsiderHr = person(otherBusiness, otherBusiness.role("hrm", "self", "dir", "att.all", "appr.reg"));
        LocalDate date = workday(3);
        String id = create(employee, date, date, "Home");
        mockMvc.perform(get(URL + "/" + id).cookie(outsiderHr.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(URL + "/" + id + "/decision")).cookie(outsiderHr.cookie()).content("{\"approve\":true}")).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(outsiderHr.cookie()).content("{\"ids\":[\"" + id + "\"],\"approve\":true}"))
                .andExpect(jsonPath("$.data[0].ok").value(false));
        mockMvc.perform(write(delete(URL + "/" + id)).cookie(outsiderHr.cookie())).andExpect(status().isNotFound());
        assertThat(list(outsiderHr, "?scope=team").get("total").asInt()).isZero();
        assertThat(statusOf(id)).isEqualTo("PENDING");
        // an approver of the business gets no bell for a request of another business
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ?", Integer.class, outsiderHr.id())).isZero();
    }

    @Test
    void severalRequestsAreDecidedTogetherAndOwnRequestsCannotBeApproved() throws Exception {
        Person one = person(employeeRole), two = person(employeeRole), hr = person(hrRole), secondHr = person(hrRole);
        LocalDate date = workday(3);
        String first = create(one, date, date, "a"), second = create(two, date, date, "b"), own = create(hr, date, date, "c");
        String ids = "\"" + first + "\",\"" + second + "\",\"" + own + "\"";
        // a rejection in bulk needs remarks too
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[" + ids + "],\"approve\":false}")).andExpect(jsonPath("$.data[0].ok").value(false));
        JsonNode results = json.readTree(mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[" + ids + ",\"" + first + "\"],\"approve\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(results).hasSize(3); // a repeated id is decided once
        assertThat(results.get(0).get("ok").asBoolean()).isTrue();
        assertThat(results.get(1).get("ok").asBoolean()).isTrue();
        assertThat(results.get(2).get("ok").asBoolean()).isFalse(); // HR can't approve their own
        assertThat(statusOf(own)).isEqualTo("PENDING");
        mockMvc.perform(write(put(URL + "/" + own + "/decision")).cookie(secondHr.cookie()).content("{\"approve\":true}")).andExpect(status().isOk());
        // empty, too many, missing flag, not a uuid
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[],\"approve\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[\"" + first + "\"]}")).andExpect(status().isBadRequest());
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 51; i++) many.append(i == 0 ? "" : ",").append("\"").append(UUID.randomUUID()).append("\"");
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[" + many + "],\"approve\":true}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/attendance/remote-work-decisions")).cookie(hr.cookie()).content("{\"ids\":[\"1; DROP TABLE employees\"],\"approve\":true}")).andExpect(jsonPath("$.data[0].ok").value(false));
    }

    @Test
    void hostileFiltersAndIdsAreBoundParametersOrRefused() throws Exception {
        Person employee = person(employeeRole), hr = person(hrRole);
        LocalDate date = workday(3);
        create(employee, date, date, "Home");
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("search", "x' OR '1'='1' --").cookie(hr.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("employeeId", "1; DROP TABLE employees").cookie(hr.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("departmentId", "' OR 1=1").cookie(hr.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("status", "PENDING' --").cookie(hr.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("month", "2026-99' OR 1=1").cookie(hr.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get(URL).queryParam("scope", "team").queryParam("page", "-1; DROP").cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get(URL + "/not-a-uuid").cookie(hr.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(write(delete(URL + "/1'--")).cookie(employee.cookie())).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(URL + "/not-a-uuid/decision")).cookie(hr.cookie()).content("{\"approve\":true}")).andExpect(status().isBadRequest());
        // the month filter finds requests that touch the month, the status filter narrows
        String month = date.toString().substring(0, 7);
        assertThat(list(hr, "?scope=team&month=" + month).get("total").asInt()).isEqualTo(1);
        assertThat(list(hr, "?scope=team&month=" + date.minusMonths(3).toString().substring(0, 7)).get("total").asInt()).isZero();
        assertThat(list(hr, "?scope=team&status=APPROVED").get("total").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_work_requests", Integer.class)).isGreaterThan(0);
    }

    @Test
    void adminAndHrRaiseRemoteWorkForAnEmployeeAndItIsApprovedAtOnce() throws Exception {
        Person employee = person(employeeRole), colleague = person(employeeRole), hr = person(hrRole), secondHr = person(hrRole);
        TestData stranger = data.inBusiness("remote-work-other");
        Person outsider = person(stranger, stranger.role("employee", "self", "dir"));
        LocalDate date = workday(3);
        String plain = body(date, date, "Internet repair at home");
        mockMvc.perform(write(post(URL)).cookie(colleague.cookie()).content(withEmployee(plain, employee.id().toString()))).andExpect(status().isForbidden());
        mockMvc.perform(write(post(URL)).cookie(hr.cookie()).content(withEmployee(plain, outsider.id().toString()))).andExpect(status().isNotFound());
        mockMvc.perform(write(post(URL)).cookie(hr.cookie()).content(withEmployee(plain, "1' OR '1'='1"))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM remote_work_requests WHERE employee_id IN (?, ?)", Integer.class, employee.id(), outsider.id())).isZero();

        mockMvc.perform(write(post(URL)).cookie(hr.cookie()).content(withEmployee(plain, employee.id().toString()))).andExpect(status().isCreated());
        String id = jdbc.queryForObject("SELECT id::text FROM remote_work_requests WHERE employee_id = ?", String.class, employee.id());
        assertThat(jdbc.queryForObject("SELECT status::text FROM remote_work_requests WHERE id = ?::uuid", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT created_by FROM remote_work_requests WHERE id = ?::uuid", UUID.class, id)).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT decided_by FROM remote_work_requests WHERE id = ?::uuid", UUID.class, id)).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'remote_work.request_on_behalf' AND entity_id = ?", UUID.class, id)).isEqualTo(hr.id());
        assertThat(jdbc.queryForObject("SELECT after->>'onBehalfOf' FROM audit_log WHERE action = 'remote_work.request_on_behalf' AND entity_id = ?", String.class, id)).isEqualTo(employee.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'remote_work.approve' AND entity_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link LIKE '/attendance?tab=remote-work%'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/attendance?scope=team&tab=remote-work'", Integer.class, secondHr.id())).isZero();
        // an overlapping request for the same employee is refused
        mockMvc.perform(write(post(URL)).cookie(secondHr.cookie()).content(withEmployee(plain, employee.id().toString()))).andExpect(status().isConflict());
        // the employee sees it as approved in their own list
        mockMvc.perform(get(URL).queryParam("scope", "mine").cookie(employee.cookie())).andExpect(jsonPath("$.data.items[0].status").value("APPROVED"));
    }

    static String withEmployee(String body, String employeeId) {
        return body.replace("{", "{\"employeeId\":\"" + employeeId + "\",");
    }
}
