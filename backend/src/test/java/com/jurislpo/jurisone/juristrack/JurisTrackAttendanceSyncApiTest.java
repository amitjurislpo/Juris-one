package com.jurislpo.jurisone.juristrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.JurisTrackStub;
import com.jurislpo.jurisone.JurisTrackStub.Received;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
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

/** Home check-in/out and the JurisTrack work timer follow each other, for a business that has the Juris Track module. */
class JurisTrackAttendanceSyncApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID role;
    static int sequence = 100;

    record Person(UUID id, Cookie cookie) {}

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("jt-attendance-sync");
        role = data.role("jt-sync-employee", "self", "dir");
        if (jdbc.queryForObject("SELECT count(*) FROM business_modules WHERE business_id = ?", Integer.class, data.business()) == 0) {
            jdbc.update("INSERT INTO business_modules (id, business_id, module, source, starts_on) VALUES (?, ?, 'JURIS_TRACK', 'COMPLIMENTARY', ?)",
                    UUID.randomUUID(), data.business(), Date.valueOf(LocalDate.now().minusDays(1)));
        }
        JurisTrackStub.reset();
    }

    Person person(TestData inBusiness, UUID personRole) throws Exception {
        String code = "JS-" + (++sequence) + (int) (Math.random() * 1000);
        String email = code.toLowerCase() + "@example.com";
        UUID id = inBusiness.employee(code, email, personRole);
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    void punch(Person person, String expect, int expectedStatus) throws Exception {
        mockMvc.perform(write(post("/api/attendance/punches")).cookie(person.cookie()).content("{\"expect\":\"" + expect + "\"}")).andExpect(status().is(expectedStatus));
    }

    void trackEvent(Person person, String type) throws Exception {
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(person.cookie()).content("{\"type\":\"" + type + "\"}")).andExpect(status().isOk());
    }

    List<String> sentBodies() {
        return JurisTrackStub.received().stream().map(Received::body).toList();
    }

    int punchCount(Person person, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ? AND kind = ?::\"PunchKind\"", Integer.class, person.id(), kind);
    }

    int totalPunchCount(Person person) {
        return jdbc.queryForObject("SELECT count(*) FROM punches WHERE employee_id = ?", Integer.class, person.id());
    }

    void makeLastPunchOld(Person person) {
        jdbc.update("UPDATE punches SET at = at - interval '2 hours' WHERE employee_id = ?", person.id());
    }

    @Test
    void checkInOnHomeStartsTheJurisTrackDayWithTheEmployeesToken() throws Exception {
        Person employee = person(data, role);
        punch(employee, "IN", 201);
        assertThat(sentBodies()).containsExactly("{\"type\":\"START\"}");
        assertThat(jdbc.queryForObject("SELECT source::text FROM punches WHERE employee_id = ?", String.class, employee.id())).isEqualTo("WEB");
        Received received = JurisTrackStub.received().get(0);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.pathAndQuery()).isEqualTo("/api/me/time-events");
        assertThat(received.headers().get("authorization")).startsWith("Bearer ");
    }

    @Test
    void checkOutOnHomeEndsTheJurisTrackDay() throws Exception {
        Person employee = person(data, role);
        punch(employee, "IN", 201);
        makeLastPunchOld(employee);
        JurisTrackStub.reset();
        punch(employee, "OUT", 201);
        assertThat(sentBodies()).containsExactly("{\"type\":\"END\"}");
    }

    @Test
    void checkOutWhileOnABreakResumesAndThenEnds() throws Exception {
        Person employee = person(data, role);
        punch(employee, "IN", 201);
        makeLastPunchOld(employee);
        JurisTrackStub.reset();
        JurisTrackStub.answerNextWith(409, "{\"status\":409,\"code\":\"BREAK_IN_PROGRESS\"}");
        punch(employee, "OUT", 201);
        assertThat(sentBodies()).containsExactly("{\"type\":\"END\"}", "{\"type\":\"RESUME\"}", "{\"type\":\"END\"}");
    }

    @Test
    void withoutTheModuleTheHomePunchWorksAsBeforeAndJurisTrackHearsNothing() throws Exception {
        TestData other = new TestData(jdbc).inBusiness("jt-sync-no-module");
        Person employee = person(other, other.role("jt-sync-plain", "self", "dir"));
        punch(employee, "IN", 201);
        makeLastPunchOld(employee);
        punch(employee, "OUT", 201);
        assertThat(JurisTrackStub.received()).isEmpty();
    }

    @Test
    void aJurisTrackThatIsDownNeverFailsThePunch() throws Exception {
        Person employee = person(data, role);
        JurisTrackStub.hangUp(true);
        punch(employee, "IN", 201);
        assertThat(punchCount(employee, "IN")).isEqualTo(1);
        JurisTrackStub.reset();
        JurisTrackStub.answerWith(500, "application/json", "{}");
        makeLastPunchOld(employee);
        punch(employee, "OUT", 201);
        assertThat(punchCount(employee, "OUT")).isEqualTo(1);
    }

    @Test
    void startingWorkInJurisTrackChecksTheEmployeeInOnHome() throws Exception {
        Person employee = person(data, role);
        trackEvent(employee, "START");
        assertThat(punchCount(employee, "IN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source::text FROM punches WHERE employee_id = ?", String.class, employee.id())).isEqualTo("JURISTRACK"); // told apart from a web punch
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'attendance.punch' AND \"after\"::text LIKE '%JURISTRACK%'", Integer.class, employee.id())).isEqualTo(1);
        assertThat(sentBodies()).containsExactly("{\"type\":\"START\"}"); // only the browser's own call: no echo back to JurisTrack
    }

    @Test
    void endingTheDayInJurisTrackChecksTheEmployeeOutOnHome() throws Exception {
        Person employee = person(data, role);
        trackEvent(employee, "START");
        makeLastPunchOld(employee);
        trackEvent(employee, "END");
        assertThat(punchCount(employee, "OUT")).isEqualTo(1);
        assertThat(sentBodies()).containsExactly("{\"type\":\"START\"}", "{\"type\":\"END\"}");
    }

    @Test
    void aRepeatedStartOrAnEndWithoutCheckInRecordsNothingAndFailsNothing() throws Exception {
        Person employee = person(data, role);
        trackEvent(employee, "END"); // not checked in
        assertThat(punchCount(employee, "OUT")).isZero();
        trackEvent(employee, "START");
        trackEvent(employee, "START");
        assertThat(punchCount(employee, "IN")).isEqualTo(1);
    }

    @Test
    void aStartAfterACheckInOnHomeAddsNoSecondPunch() throws Exception {
        Person employee = person(data, role);
        punch(employee, "IN", 201);
        trackEvent(employee, "START");
        assertThat(punchCount(employee, "IN")).isEqualTo(1);
    }

    @Test
    void breaksAndResumesHaveNoPunch() throws Exception {
        Person employee = person(data, role);
        trackEvent(employee, "BREAK");
        trackEvent(employee, "RESUME");
        assertThat(totalPunchCount(employee)).isZero();
    }

    @Test
    void anEmployeeWithoutAnAttendanceRecordIsLeftAlone() throws Exception {
        Person employee = person(data, data.role("jt-sync-untracked", "dir")); // attendance is tracked for roles with "self" only
        trackEvent(employee, "START");
        assertThat(totalPunchCount(employee)).isZero();
    }

    @Test
    void aFailedJurisTrackAnswerRecordsNoPunch() throws Exception {
        Person employee = person(data, role);
        JurisTrackStub.answerWith(409, "application/problem+json", "{\"code\":\"WORKDAY_NOT_STARTED\"}");
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(employee.cookie()).content("{\"type\":\"START\"}")).andExpect(status().isConflict());
        assertThat(totalPunchCount(employee)).isZero();
    }
}
