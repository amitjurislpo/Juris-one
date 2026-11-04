package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

/** Departments, holidays and settings: refusals for signed-out and plain users, hostile filters / ids / bodies, and nothing reaches the other business. */
class OrganisationHostileInputTest extends HostileInputTestBase {

    static final String ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
    String betaDepartmentId, betaHolidayId;

    @BeforeEach
    void betaData() throws Exception {
        String marker = BETA_MARKER + UUID.randomUUID().toString().substring(0, 6);
        MvcResult department = send(betaWide, new Call("POST", "/api/v1/departments", "{\"name\":\"" + marker + " dept\"}"));
        assertThat(department.getResponse().getStatus()).isEqualTo(201);
        betaDepartmentId = jdbc.queryForObject("SELECT id::text FROM departments WHERE business_id = ? AND name = ?", String.class, beta.business(), marker + " dept");
        int year = java.time.LocalDate.now().getYear() + 5;
        String holidayName = marker + " day";
        MvcResult holiday = send(betaWide, new Call("POST", "/api/v1/holidays", "{\"date\":\"" + year + "-0" + (1 + (int) (Math.random() * 8)) + "-1" + (int) (Math.random() * 9) + "\",\"name\":\"" + holidayName + "\",\"region\":\"ALL\"}"));
        assertThat(holiday.getResponse().getStatus()).isIn(201, 422);
        if (holiday.getResponse().getStatus() == 201) betaHolidayId = jdbc.queryForObject("SELECT id::text FROM holidays WHERE business_id = ? AND name = ?", String.class, beta.business(), holidayName);
    }

    static Stream<Call> everyEndpoint() {
        return Stream.of(new Call("GET", "/api/v1/departments"), new Call("POST", "/api/v1/departments", "{\"name\":\"Research\"}"), new Call("DELETE", "/api/v1/departments/" + ID),
                new Call("GET", "/api/v1/holidays"), new Call("POST", "/api/v1/holidays", "{\"date\":\"2041-12-25\",\"name\":\"Christmas\",\"region\":\"ALL\"}"), new Call("DELETE", "/api/v1/holidays/" + ID),
                new Call("GET", "/api/v1/settings/access"), new Call("GET", "/api/v1/settings/leave"), new Call("GET", "/api/v1/settings/holidays?year=2041"),
                new Call("GET", "/api/v1/settings/attendance"), new Call("GET", "/api/v1/settings/employee-code-series"),
                new Call("PUT", "/api/v1/settings/employee-code-series", "{\"mode\":\"AUTO\"}"), new Call("PUT", "/api/v1/settings/attendance", "{}"));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void signedOutIsRefused(Call call) throws Exception {
        assertRefused(null, call, 401);
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void aPlainEmployeeIsRefused(Call call) throws Exception {
        assertRefused(alphaPlain, call, 403);
    }

    static Stream<Call> listsWithFilters() {
        return Stream.of("departments", "holidays").flatMap(list -> Stream.of("search", "sort", "sortDirection", "page", "region").map(name -> new Call("GET", "/api/v1/" + list + "?" + name + "=")));
    }

    @ParameterizedTest
    @MethodSource("listsWithFilters")
    void hostileFiltersNeverBreakOrLeak(Call call) throws Exception {
        for (String hostile : HOSTILE_TEXT) assertSurvives(alphaWide, new Call("GET", call.path() + enc(hostile)));
        assertSurvives(alphaWide, new Call("GET", call.path() + enc("x".repeat(100_000))));
        assertDatabaseIntact();
    }

    @Test
    void theYearOfTheHolidaySettingsIsValidated() throws Exception {
        for (String year : List.of("abc", "", "1' OR '1'='1", "99999999999", "-5", "0", "2e3", "<script>", "%00", "9999999")) {
            MvcResult result = assertSurvives(alphaWide, new Call("GET", "/api/v1/settings/holidays?year=" + enc(year)));
            assertThat(result.getResponse().getStatus()).as("year=" + year).isIn(200, 400, 422);
        }
        assertSurvives(alphaWide, new Call("GET", "/api/v1/settings/holidays"));
    }

    @Test
    void malformedIdsAreRefusedWithoutAnError() throws Exception {
        for (String bad : BAD_IDS) {
            for (String path : List.of("/api/v1/departments/", "/api/v1/holidays/")) {
                Call call = new Call("DELETE", path + enc(bad));
                MvcResult result = send(alphaWide, call);
                if (result.getResponse().getStatus() == 400 && result.getResponse().getContentAsString().isEmpty()) continue; // the URL firewall (encoded percent)
                assertRefused(alphaWide, call, result, 400, 404, 422);
            }
        }
    }

    @Test
    void anotherBusinessCannotDeleteMyDepartmentOrHoliday() throws Exception {
        assertRefused(alphaWide, new Call("DELETE", "/api/v1/departments/" + betaDepartmentId), 400, 404, 422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE id = ?::uuid", Long.class, betaDepartmentId)).isEqualTo(1);
        if (betaHolidayId != null) {
            assertRefused(alphaWide, new Call("DELETE", "/api/v1/holidays/" + betaHolidayId), 400, 404, 422);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE id = ?::uuid", Long.class, betaHolidayId)).isEqualTo(1);
        }
        for (String path : List.of("/api/v1/departments", "/api/v1/holidays", "/api/v1/settings/leave", "/api/v1/settings/attendance", "/api/v1/settings/access")) {
            assertThat(assertSurvives(alphaWide, new Call("GET", path)).getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
        }
    }

    @Test
    void aDepartmentHeadFromAnotherBusinessIsRefused() throws Exception {
        String name = "Heads " + UUID.randomUUID().toString().substring(0, 6);
        Call call = new Call("POST", "/api/v1/departments", "{\"name\":\"" + name + "\",\"headId\":\"" + betaWide.id() + "\"}");
        MvcResult result = send(alphaWide, call);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE name = ?", Long.class, name)).isZero();
    }

    @Test
    void settingsOfOneBusinessNeverChangeTheOthers() throws Exception {
        String before = send(betaWide, new Call("GET", "/api/v1/settings/employee-code-series")).getResponse().getContentAsString();
        String betaMode = json.readTree(before).at("/data/mode").asText();
        String flipped = "AUTO".equals(betaMode) ? "MANUAL" : "AUTO";
        assertThat(send(alphaWide, new Call("PUT", "/api/v1/settings/employee-code-series", "{\"mode\":\"" + flipped + "\"}")).getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(send(betaWide, new Call("GET", "/api/v1/settings/employee-code-series")).getResponse().getContentAsString()).at("/data/mode").asText()).isEqualTo(betaMode);
        send(alphaWide, new Call("PUT", "/api/v1/settings/employee-code-series", "{\"mode\":\"AUTO\"}"));
    }

    static Stream<String> departmentFields() {
        return Stream.of("name", "description", "headId");
    }

    @ParameterizedTest
    @MethodSource("departmentFields")
    void hostileDepartmentBodiesAreRefusedOrStoredHarmlesslyNeverAnError(String field) throws Exception {
        for (String hostile : Stream.concat(HOSTILE_TEXT.stream(), Stream.of("", " ", "x".repeat(100_000))).toList()) {
            String name = "name".equals(field) ? quote(hostile) : "\"Dept " + UUID.randomUUID().toString().substring(0, 8) + "\"";
            String body = "{\"name\":" + name + ("name".equals(field) ? "" : ",\"" + field + "\":" + quote(hostile)) + "}";
            Call call = new Call("POST", "/api/v1/departments", body);
            MvcResult result = assertSurvives(alphaWide, call);
            int status = result.getResponse().getStatus();
            if (hostile.length() > 1000) assertThat(status).as("an over-long " + field + " is refused: " + call).isBetween(400, 499);
            if (status >= 400) assertAudited(alphaWide, call, result, status);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM departments WHERE length(name) > 1000 OR length(coalesce(description, '')) > 1000", Long.class)).isZero();
        assertDatabaseIntact();
    }

    static Stream<String> holidayFields() {
        return Stream.of("date", "name", "region");
    }

    @ParameterizedTest
    @MethodSource("holidayFields")
    void hostileHolidayBodiesAreRefusedNeverAnError(String field) throws Exception {
        List<String> values = Stream.concat(HOSTILE_TEXT.stream(), Stream.of("", " ", "x".repeat(100_000), "2041-13-45", "2041-02-30", "0000-00-00", "9999-99-99", "2041-1-1", "99999-01-01")).toList();
        for (String hostile : values) {
            String date = "date".equals(field) ? quote(hostile) : "\"2041-0" + (1 + (int) (Math.random() * 8)) + "-2" + (int) (Math.random() * 8) + "\"";
            String name = "name".equals(field) ? quote(hostile) : "\"Fest " + UUID.randomUUID().toString().substring(0, 8) + "\"";
            String region = "region".equals(field) ? quote(hostile) : "\"ALL\"";
            Call call = new Call("POST", "/api/v1/holidays", "{\"date\":" + date + ",\"name\":" + name + ",\"region\":" + region + "}");
            MvcResult result = assertSurvives(alphaWide, call);
            int status = result.getResponse().getStatus();
            if (!"name".equals(field)) assertThat(status).as("a bad " + field + " is refused: " + call).isBetween(400, 499);
            if (status >= 400) assertAudited(alphaWide, call, result, status);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holidays WHERE length(name) > 1000", Long.class)).isZero();
    }

    @Test
    void hostileAttendancePolicyValuesAreRefusedAndChangeNothing() throws Exception {
        String before = send(alphaWide, new Call("GET", "/api/v1/settings/attendance")).getResponse().getContentAsString();
        List<String> bodies = List.of("{\"fullDayHours\":-1}", "{\"fullDayHours\":999999999999}", "{\"fullDayHours\":0,\"halfDayHours\":0}", "{\"graceMinutes\":-5}", "{\"graceMinutes\":2147483647}",
                "{\"weeklyOffs\":[9,-1,100]}", "{\"weeklyOffs\":[0,1,2,3,4,5,6]}", "{\"lockDayOfNextMonth\":0}", "{\"lockDayOfNextMonth\":99}", "{\"lockTime\":\"25:99\"}", "{\"lockTime\":\"<script>\"}",
                "{\"trackingStartDate\":\"' OR 1=1\"}", "{\"trackingStartDate\":\"2041-13-45\"}", "{\"lateMarksPerHalfDayLop\":-3}", "{\"absenceJobBufferMinutes\":-1}", "{\"fullDayHours\":\"abc\"}", "{\"weeklyOffs\":\"x\"}",
                "{\"lockTime\":\"" + "9".repeat(100_000) + "\"}");
        for (String body : bodies) {
            Call call = new Call("PUT", "/api/v1/settings/attendance", body);
            MvcResult result = assertSurvives(alphaWide, call);
            if (result.getResponse().getStatus() >= 400) assertAudited(alphaWide, call, result, result.getResponse().getStatus());
            else org.junit.jupiter.api.Assertions.fail("accepted a hostile policy: " + body.substring(0, Math.min(80, body.length())));
        }
        assertThat(send(alphaWide, new Call("GET", "/api/v1/settings/attendance")).getResponse().getContentAsString()).isEqualTo(before);
    }

    @Test
    void theEmployeeCodeSeriesTakesOnlyItsTwoModes() throws Exception {
        for (String hostile : HOSTILE_TEXT) {
            assertRefused(alphaWide, new Call("PUT", "/api/v1/settings/employee-code-series", "{\"mode\":" + quote(hostile) + "}"), 400);
        }
        for (String body : List.of("{}", "{\"mode\":null}", "{\"mode\":[\"AUTO\"]}", "[]", "{")) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", "/api/v1/settings/employee-code-series", body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
        }
        JsonNode mode = json.readTree(send(alphaWide, new Call("GET", "/api/v1/settings/employee-code-series")).getResponse().getContentAsString());
        assertThat(mode.at("/data/mode").asText()).isIn("AUTO", "MANUAL");
    }
}
