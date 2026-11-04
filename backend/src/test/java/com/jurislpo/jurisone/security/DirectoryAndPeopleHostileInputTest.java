package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Home, directory, team, overview, audit log, people, attendance and roles: refusals, every filter and id given hostile values, and no reading or changing of
 * another business's or another person's records.
 */
class DirectoryAndPeopleHostileInputTest extends HostileInputTestBase {

    static final String ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";

    UUID alphaDepartment, betaDepartment;

    @BeforeEach
    void departments() {
        alphaDepartment = UUID.randomUUID();
        betaDepartment = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", alphaDepartment, "Ops " + alphaDepartment.toString().substring(0, 6), alpha.business());
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", betaDepartment, BETA_MARKER + " Ops " + betaDepartment.toString().substring(0, 6), beta.business());
        jdbc.update("UPDATE employees SET department_id = ? WHERE id = ?", betaDepartment, betaPlain.id());
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_DAY'", Long.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region) VALUES (?, 'IN_DAY', 'India Day', '09:30', '18:30', 15, 'IN')", UUID.randomUUID());
        }
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", alphaWideRole, alphaPlainRole);
    }

    static Stream<Call> employeeEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/home"), new Call("GET", "/api/v1/directory"), new Call("GET", "/api/v1/attendance/mine"), new Call("GET", "/api/v1/profile"),
                new Call("GET", "/api/v1/me"), new Call("GET", "/api/v1/shell"), new Call("POST", "/api/v1/audit/page-views", "{\"path\":\"/home\"}"),
                new Call("POST", "/api/v1/attendance/punches", "{\"expect\":\"IN\"}"), new Call("GET", "/api/v1/people/" + ID + "/photo"));
    }

    static Stream<Call> managerEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/overview"), new Call("GET", "/api/v1/team"), new Call("GET", "/api/v1/audit"), new Call("GET", "/api/v1/audit/export"),
                new Call("GET", "/api/v1/people"), new Call("GET", "/api/v1/people/" + ID), new Call("GET", "/api/v1/people/" + ID + "/contact"),
                new Call("PUT", "/api/v1/people/" + ID + "/access", "{\"enabled\":false}"), new Call("PUT", "/api/v1/people/" + ID + "/status", "{\"status\":\"ACTIVE\"}"),
                new Call("PUT", "/api/v1/people/" + ID + "/photo"), new Call("DELETE", "/api/v1/people/" + ID + "/photo"), new Call("GET", "/api/v1/people/form-options"),
                new Call("GET", "/api/v1/people/export"), new Call("GET", "/api/v1/attendance/register"), new Call("GET", "/api/v1/attendance/register/export"), new Call("GET", "/api/v1/roles"),
                new Call("PUT", "/api/v1/roles/" + ID + "/permissions/self"), new Call("DELETE", "/api/v1/roles/" + ID + "/permissions/self"), new Call("PUT", "/api/v1/roles/" + ID + "/assignable-roles/" + ID),
                new Call("DELETE", "/api/v1/roles/" + ID + "/assignable-roles/" + ID), new Call("DELETE", "/api/v1/roles/" + ID));
    }

    static Stream<Call> platformOnlyEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/platform/audit"), new Call("GET", "/api/v1/platform/audit/export"), new Call("PUT", "/api/v1/roles/" + ID + "/permissions/self"),
                new Call("DELETE", "/api/v1/roles/" + ID + "/permissions/self"), new Call("DELETE", "/api/v1/roles/" + ID));
    }

    static Stream<Call> everyEndpoint() {
        return Stream.of(employeeEndpoints(), managerEndpoints(), platformOnlyEndpoints()).flatMap(s -> s).filter(call -> !call.path().equals("/api/v1/me") && !call.path().equals("/api/v1/shell"));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void signedOutIsRefused(Call call) throws Exception {
        assertRefused(null, call, 401);
    }

    @ParameterizedTest
    @MethodSource("managerEndpoints")
    void aPlainEmployeeIsRefused(Call call) throws Exception {
        assertRefused(alphaPlain, call, 403);
    }

    @ParameterizedTest
    @MethodSource("platformOnlyEndpoints")
    void aBusinessAdministratorIsRefusedPlatformEndpoints(Call call) throws Exception {
        assertRefused(alphaWide, call, 403);
    }

    static final List<String> FILTERS = List.of("search", "sort", "sortDirection", "page", "pageSize", "status", "departmentId", "month", "date", "timeZone", "view", "relation", "access", "filter",
            "regime", "actorId", "periodDays", "tab", "region");

    static Stream<String> listPaths() {
        return Stream.of("/api/v1/directory", "/api/v1/team", "/api/v1/people", "/api/v1/people/export", "/api/v1/audit", "/api/v1/audit/export", "/api/v1/attendance/mine", "/api/v1/attendance/register",
                "/api/v1/attendance/register/export", "/api/v1/overview", "/api/v1/home", "/api/v1/people/form-options");
    }

    @ParameterizedTest
    @MethodSource("listPaths")
    void everyFilterGivenHostileValuesNeverBreaksOrLeaks(String path) throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.addAll(List.of("99999999999999999999", "0", "2026-13", "2026-02-30", "2026-1-1", "0000-13", "Asia/Nowhere", ID, betaDepartment.toString(), betaPlain.id().toString()));
        for (String filter : FILTERS) {
            for (String hostile : values) {
                MvcResult result = assertSurvives(alphaWide, new Call("GET", path + "?" + filter + "=" + enc(hostile)));
                if (result.getResponse().getStatus() >= 400) assertThat(result.getResponse().getStatus()).as(path + " " + filter + "=" + hostile).isBetween(400, 499);
            }
        }
        assertSurvives(alphaWide, new Call("GET", path + "?search=" + enc("x".repeat(20_000))));
        assertDatabaseIntact();
    }

    @Test
    void searchingForTheOtherBusinessFindsNothing() throws Exception {
        for (String path : List.of("/api/v1/directory", "/api/v1/people", "/api/v1/team", "/api/v1/audit", "/api/v1/attendance/register")) {
            for (String term : List.of(BETA_MARKER, "' OR '1'='1", "%", "_", "%' OR '%'='", BETA_MARKER.toLowerCase())) {
                MvcResult result = assertSurvives(alphaWide, new Call("GET", path + "?search=" + enc(term)));
                assertThat(result.getResponse().getContentAsString()).as(path + " search " + term).doesNotContain(BETA_MARKER).doesNotContain(betaPlain.id().toString());
            }
            for (String filter : List.of("departmentId", "actorId", "employeeId")) {
                MvcResult result = assertSurvives(alphaWide, new Call("GET", path + "?" + filter + "=" + betaDepartment));
                assertThat(result.getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
                result = assertSurvives(alphaWide, new Call("GET", path + "?" + filter + "=" + betaPlain.id()));
                assertThat(result.getResponse().getContentAsString()).doesNotContain(BETA_MARKER).doesNotContain(betaPlain.id().toString());
            }
        }
    }

    static Stream<Call> endpointsWithAnId() {
        return Stream.of(employeeEndpoints(), managerEndpoints()).flatMap(s -> s).filter(call -> call.path().contains(ID));
    }

    @ParameterizedTest
    @MethodSource("endpointsWithAnId")
    void malformedIdsAreRefusedWithoutAnError(Call call) throws Exception {
        for (String bad : BAD_IDS) {
            Call hostile = new Call(call.method(), call.path().replace(ID, enc(bad)), call.body());
            MvcResult result = send(alphaWide, hostile);
            if (result.getResponse().getStatus() == 400 && result.getResponse().getContentAsString().isEmpty()) continue; // URL firewall (encoded percent)
            assertThat(result.getResponse().getStatus()).as(hostile + " -> " + result.getResponse().getContentAsString()).isBetween(400, 499);
            assertRefused(alphaWide, hostile, result, result.getResponse().getStatus());
        }
    }

    @Test
    void anotherBusinessesPeopleCannotBeReadOrChanged() throws Exception {
        String before = jdbc.queryForObject("SELECT status::text || coalesce(designation, '') || portal_access FROM employees WHERE id = ?", String.class, betaPlain.id());
        for (Call attack : List.of(new Call("GET", "/api/v1/people/" + betaPlain.id()), new Call("GET", "/api/v1/people/" + betaPlain.id() + "/contact"),
                new Call("GET", "/api/v1/people/" + betaPlain.id() + "/photo"), new Call("DELETE", "/api/v1/people/" + betaPlain.id() + "/photo"),
                new Call("PUT", "/api/v1/people/" + betaPlain.id() + "/access", "{\"enabled\":false}"), new Call("PUT", "/api/v1/people/" + betaPlain.id() + "/status", "{\"status\":\"ACTIVE\"}"),
                new Call("PUT", "/api/v1/people/" + betaPlain.id() + "/status", "{\"status\":\"EXITED\",\"exitDate\":\"2031-01-01\"}"),
                new Call("PUT", "/api/v1/people/" + betaPlain.id(), "{\"firstName\":\"Hijacked\",\"lastName\":\"Person\"}"),
                new Call("GET", "/api/v1/people/form-options?id=" + betaPlain.id()))) {
            MvcResult result = send(alphaWide, attack);
            assertThat(result.getResponse().getStatus()).as(attack + " -> " + result.getResponse().getContentAsString()).isBetween(400, 499);
            assertThat(result.getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
            if (result.getResponse().getContentType() != null && result.getResponse().getContentType().startsWith("application/problem+json")) {
                assertRefused(alphaWide, attack, result, result.getResponse().getStatus());
            }
        }
        assertThat(jdbc.queryForObject("SELECT status::text || coalesce(designation, '') || portal_access FROM employees WHERE id = ?", String.class, betaPlain.id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT full_name FROM employees WHERE id = ?", String.class, betaPlain.id())).doesNotContain("Hijacked");
    }

    @Test
    void anotherPersonsRecordsAreNotAvailableToAColleague() throws Exception {
        // a plain colleague (directory only) cannot open a person's full record, contact details or change them
        for (Call attack : List.of(new Call("GET", "/api/v1/people/" + alphaWide.id()), new Call("GET", "/api/v1/people/" + alphaWide.id() + "/contact"),
                new Call("PUT", "/api/v1/people/" + alphaWide.id() + "/access", "{\"enabled\":false}"), new Call("PUT", "/api/v1/people/" + alphaPlainOther.id() + "/status", "{\"status\":\"ACTIVE\"}"),
                new Call("PUT", "/api/v1/people/" + alphaPlainOther.id() + "/photo"), new Call("DELETE", "/api/v1/people/" + alphaPlainOther.id() + "/photo"))) {
            assertRefused(alphaPlain, attack, 403);
        }
        // the profile is always the signed-in person's; an id cannot be passed in
        MvcResult mine = assertSurvives(alphaPlain, new Call("GET", "/api/v1/profile?id=" + alphaWide.id() + "&employeeId=" + alphaWide.id()));
        assertThat(mine.getResponse().getContentAsString()).contains(alphaPlain.email()).doesNotContain(alphaWide.email());
        // and another person's document cannot be fetched through the profile route
        for (String docId : List.of(ID, "not-a-uuid")) {
            MvcResult result = send(alphaPlain, new Call("GET", "/api/v1/profile/documents/" + docId + "/file"));
            assertThat(result.getResponse().getStatus()).isBetween(400, 499);
            assertNoLeak(result.getResponse().getContentAsString());
        }
    }

    @Test
    void roleEditingIsRefusedWhateverTheIds() throws Exception {
        for (String perm : List.of("self", "platform.manage", "' OR 1=1", "<script>", "roles.manage")) {
            assertRefused(alphaWide, new Call("PUT", "/api/v1/roles/" + alphaPlainRole + "/permissions/" + enc(perm)), 403);
            assertRefused(alphaWide, new Call("DELETE", "/api/v1/roles/" + alphaPlainRole + "/permissions/" + enc(perm)), 403);
        }
        assertRefused(alphaWide, new Call("PUT", "/api/v1/roles/" + betaWideRole + "/permissions/platform.manage"), 403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_id IN (?, ?) AND permission_key = 'platform.manage'", Long.class, alphaWideRole, betaWideRole)).isZero();
        assertThat(assertSurvives(alphaWide, new Call("GET", "/api/v1/roles")).getResponse().getContentAsString()).doesNotContain(betaWideRole.toString());
    }

    @Test
    void hostilePageViewPathsAreRefusedOrHarmless() throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.addAll(List.of("", "/", "/leave?apply=1#x", "/" + "a".repeat(100_000), "//evil.example/x", "javascript:alert(1)", "/home\r\nX-Injected: 1"));
        for (String path : values) {
            MvcResult result = assertSurvives(alphaPlain, new Call("POST", "/api/v1/audit/page-views", "{\"path\":" + quote(path) + "}"));
            assertThat(result.getResponse().getHeader("X-Injected")).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE length(coalesce(path, '')) > 2000 OR length(coalesce(entity_id, '')) > 2000", Long.class)).isZero();
        for (String body : List.of("{}", "[]", "null", "{\"path\":123}", "{\"path\":[\"/home\"]}", "{")) {
            MvcResult result = assertSurvives(alphaPlain, new Call("POST", "/api/v1/audit/page-views", body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
        }
    }

    @Test
    void hostileStatusAccessAndPunchBodiesAreRefused() throws Exception {
        for (String status : HOSTILE_TEXT) assertRefused(alphaWide, new Call("PUT", "/api/v1/people/" + alphaPlainOther.id() + "/status", "{\"status\":" + quote(status) + "}"), 400);
        for (String exitDate : List.of("2041-13-45", "2041-02-30", "yesterday", "' OR 1=1", "9999-99-99", "", "0000-00-00", "2041-1-1")) {
            Call call = new Call("PUT", "/api/v1/people/" + alphaPlainOther.id() + "/status", "{\"status\":\"EXITED\",\"exitDate\":" + quote(exitDate) + "}");
            MvcResult result = assertSurvives(alphaWide, call);
            assertThat(result.getResponse().getStatus()).as(call.toString()).isBetween(400, 499);
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, alphaPlainOther.id())).isNotEqualTo("EXITED");
        for (String body : List.of("{}", "{\"enabled\":null}", "{\"enabled\":\"yes\"}", "[]", "{")) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", "/api/v1/people/" + alphaPlainOther.id() + "/access", body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
        }
        for (String expect : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaPlain, new Call("POST", "/api/v1/attendance/punches", "{\"expect\":" + quote(expect) + "}"));
            assertThat(result.getResponse().getStatus()).as(expect).isBetween(400, 499);
        }
    }

    static Stream<String> employeeFields() {
        return Stream.of("firstName", "lastName", "fatherName", "email", "phone", "phoneCountry", "location", "paymentType", "pan", "aadhaar", "uan", "address", "designation", "departmentId", "managerId",
                "dateOfJoining", "shift", "roleId");
    }

    @ParameterizedTest
    @MethodSource("employeeFields")
    void hostileEmployeeFormsAreRefusedOrStoredHarmlesslyNeverAnError(String field) throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.addAll(List.of("", " ", "x".repeat(100_000), "2041-13-45", betaDepartment.toString(), betaPlain.id().toString(), betaWideRole.toString(), ID));
        for (String hostile : values) {
            ObjectNode body = validEmployee();
            if (!"email".equals(field)) body.put("email", "fuzz." + UUID.randomUUID().toString().substring(0, 10) + "@sweep.test");
            body.put(field, hostile);
            Call call = new Call("POST", "/api/v1/people", body.toString());
            MvcResult result = assertSurvives(alphaWide, call);
            int status = result.getResponse().getStatus();
            boolean isIdField = List.of("departmentId", "managerId", "roleId").contains(field);
            if (hostile.length() > 10_000 || (isIdField && (hostile.equals(betaDepartment.toString()) || hostile.equals(betaPlain.id().toString()) || hostile.equals(betaWideRole.toString())))) {
                assertThat(status).as("refused: " + call.path() + " " + field + "=" + (hostile.length() > 40 ? hostile.substring(0, 40) : hostile)).isBetween(400, 499);
            }
            if (status >= 400) assertAudited(alphaWide, call, result, status);
        }
        // nothing from the other business was attached to a person of this one
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ? AND (department_id = ? OR reporting_manager_id = ? OR role_id = ?)", Long.class, alpha.business(), betaDepartment, betaPlain.id(), betaWideRole)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE length(full_name) > 1000 OR length(coalesce(designation, '')) > 1000", Long.class)).isZero();
        assertDatabaseIntact();
    }

    ObjectNode validEmployee() throws Exception {
        return (ObjectNode) json.readTree("""
            {"firstName":"Fuzz","lastName":"Person","fatherName":"Someone","email":"fuzz@sweep.test","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST",
             "pan":"abcde1234f","aadhaar":"2345 6789 0123","uan":"","address":"12 Lake Road, Pune","designation":"Associate","departmentId":"%s","managerId":"%s",
             "dateOfJoining":"2031-10-12","shift":"IN_DAY","roleId":"%s"}""".formatted(alphaDepartment, alphaWide.id(), alphaPlainRole));
    }

    @Test
    void theBaselineEmployeeFormIsAccepted() throws Exception {
        ObjectNode body = validEmployee();
        body.put("email", "baseline." + UUID.randomUUID().toString().substring(0, 10) + "@sweep.test");
        MvcResult result = send(alphaWide, new Call("POST", "/api/v1/people", body.toString()));
        assertThat(result.getResponse().getStatus()).as("the form the fuzzing starts from must be valid: " + result.getResponse().getContentAsString()).isEqualTo(201);
    }
}
