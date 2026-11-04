package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

/** Leave and approvals: sign-in and permission refusals, hostile filters / ids / bodies, and no decision or cancellation across businesses or persons. */
class LeaveHostileInputTest extends HostileInputTestBase {

    static final String ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
    static final String VALID_APPLY = "{\"type\":\"CL\",\"from\":\"2031-03-03\",\"to\":\"2031-03-03\",\"part\":\"FULL\",\"reason\":\"family\"}";

    @BeforeEach
    void leaveTypes() {
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, paid, needs_balance, sort_order, active)
            VALUES ('CL', 'Casual leave', 12, 1, 0, true, true, 1, true) ON CONFLICT (code) DO NOTHING""");
    }

    static Stream<Call> everyEndpoint() {
        return Stream.of(new Call("GET", "/api/v1/leave"), new Call("GET", "/api/v1/leave/previews?type=CL&from=2031-03-03&to=2031-03-03&part=FULL"),
                new Call("POST", "/api/v1/leave/requests", VALID_APPLY), new Call("DELETE", "/api/v1/leave/requests/" + ID),
                new Call("GET", "/api/v1/approvals"), new Call("PUT", "/api/v1/leave/requests/" + ID + "/decision", "{\"approve\":true}"),
                new Call("PUT", "/api/v1/attendance/regularizations/" + ID + "/decision", "{\"approve\":true}"));
    }

    static Stream<Call> approverEndpoints() {
        return everyEndpoint().filter(call -> call.path().contains("approvals") || call.path().contains("decision"));
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void signedOutIsRefused(Call call) throws Exception {
        assertRefused(null, call, 401);
    }

    @ParameterizedTest
    @MethodSource("approverEndpoints")
    void aPlainEmployeeCannotUseTheApproverEndpoints(Call call) throws Exception {
        assertRefused(alphaPlain, call, 403);
    }

    static Stream<String> filterNames() {
        return Stream.of("tab", "status", "sort", "sortDirection", "page", "search");
    }

    @ParameterizedTest
    @MethodSource("filterNames")
    void hostileFiltersNeverBreakOrLeak(String name) throws Exception {
        for (String path : List.of("/api/v1/leave", "/api/v1/approvals")) {
            for (String hostile : HOSTILE_TEXT) {
                assertSurvives(alphaWide, new Call("GET", path + "?" + name + "=" + enc(hostile)));
            }
            assertSurvives(alphaWide, new Call("GET", path + "?" + name + "=" + enc("x".repeat(100_000))));
            assertSurvives(alphaWide, new Call("GET", path + "?" + name + "=&" + name + "=" + name));
        }
        assertDatabaseIntact();
    }

    @Test
    void hostileSearchDoesNotWidenTheApprovalsList() throws Exception {
        UUID betaPerson = betaPlain.id();
        jdbc.update("INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days) VALUES (?, ?, 'CL', ?, ?, 1)", UUID.randomUUID(), betaPerson,
                Date.valueOf(LocalDate.now().plusDays(30)), Date.valueOf(LocalDate.now().plusDays(30)));
        for (String hostile : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaWide, new Call("GET", "/api/v1/approvals?search=" + enc(hostile)));
            assertThat(result.getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
        }
        MvcResult plain = assertSurvives(alphaWide, new Call("GET", "/api/v1/approvals"));
        assertThat(plain.getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
    }

    @Test
    void malformedIdsAreRefusedWithoutAnError() throws Exception {
        for (String bad : BAD_IDS) {
            for (Call call : List.of(new Call("DELETE", "/api/v1/leave/requests/" + enc(bad)), new Call("PUT", "/api/v1/leave/requests/" + enc(bad) + "/decision", "{\"approve\":true}"),
                    new Call("PUT", "/api/v1/attendance/regularizations/" + enc(bad) + "/decision", "{\"approve\":true,\"note\":\"x\"}"))) {
                MvcResult result = send(alphaWide, call);
                if (result.getResponse().getStatus() == 400 && result.getResponse().getContentAsString().isEmpty()) {
                    // turned away by the URL firewall (encoded percent) before anyone is identified: no body and no actor, but on record
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE request_id = ? AND status = 400", Long.class, result.getResponse().getHeader(AuditTrail.REQUEST_ID_HEADER))).isEqualTo(1);
                } else {
                    assertRefused(alphaWide, call, result, 400, 404, 422);
                }
            }
        }
    }

    static Stream<String> applyFields() {
        return Stream.of("type", "from", "to", "part", "reason");
    }

    @ParameterizedTest
    @MethodSource("applyFields")
    void hostileApplyBodiesAreRefusedOrStoredHarmlesslyNeverAnError(String field) throws Exception {
        for (String hostile : Stream.concat(HOSTILE_TEXT.stream(), Stream.of("", " ", "x".repeat(100_000))).toList()) {
            String body = VALID_APPLY.replaceAll("\"" + field + "\":\"[^\"]*\"", "\"" + field + "\":" + quote(hostile).replace("$", "\\$"));
            Call call = new Call("POST", "/api/v1/leave/requests", body);
            MvcResult result = assertSurvives(alphaPlain, call);
            if (!field.equals("reason") && !"CL".equals(hostile)) assertThat(result.getResponse().getStatus()).as(call.toString()).isBetween(400, 499);
            if (result.getResponse().getStatus() >= 400) assertAudited(alphaPlain, call, result, result.getResponse().getStatus());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE reason LIKE '%xxxxxxxxxxxxxxxxxxxx%'", Long.class)).as("an over-long reason is never stored").isZero();
        assertDatabaseIntact();
    }

    @Test
    void malformedJsonAndWrongTypesAreA400() throws Exception {
        for (String body : List.of("{", "[]", "null", "\"text\"", "{\"type\":123,\"from\":[],\"to\":{},\"part\":true}", "{\"type\":\"CL\",\"unknownField\":1}")) {
            MvcResult result = send(alphaPlain, new Call("POST", "/api/v1/leave/requests", body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
            assertNoLeak(result.getResponse().getContentAsString());
        }
        MvcResult wrongType = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/leave/requests").cookie(alphaPlain.cookie())
                .header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType("text/plain").content(VALID_APPLY)).andReturn();
        assertRefused(alphaPlain, new Call("POST", "/api/v1/leave/requests", VALID_APPLY), wrongType, 415);
    }

    UUID pendingLeave(UUID employeeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days) VALUES (?, ?, 'CL', ?, ?, 1)", id, employeeId,
                Date.valueOf(LocalDate.now().plusDays(40)), Date.valueOf(LocalDate.now().plusDays(40)));
        return id;
    }

    String status(UUID requestId) {
        return jdbc.queryForObject("SELECT status::text FROM leave_requests WHERE id = ?", String.class, requestId);
    }

    @Test
    void anotherBusinessCannotDecideOrCancelMyRequest() throws Exception {
        UUID request = pendingLeave(betaPlain.id());
        for (boolean approve : new boolean[] {true, false}) {
            Call call = new Call("PUT", "/api/v1/leave/requests/" + request + "/decision", "{\"approve\":" + approve + ",\"note\":\"no\"}");
            assertRefused(alphaWide, call, 400, 403, 404, 422);
        }
        assertRefused(alphaPlain, new Call("DELETE", "/api/v1/leave/requests/" + request), 400, 403, 404, 422);
        assertRefused(alphaWide, new Call("DELETE", "/api/v1/leave/requests/" + request), 400, 403, 404, 422);
        assertThat(status(request)).isEqualTo("PENDING");
    }

    @Test
    void anotherPersonCannotCancelMyRequestAndNobodyDecidesTheirOwn() throws Exception {
        UUID mine = pendingLeave(alphaPlain.id());
        assertRefused(alphaPlainOther, new Call("DELETE", "/api/v1/leave/requests/" + mine), 400, 403, 404, 422);
        assertThat(status(mine)).isEqualTo("PENDING");

        UUID approversOwn = pendingLeave(alphaWide.id());
        assertRefused(alphaWide, new Call("PUT", "/api/v1/leave/requests/" + approversOwn + "/decision", "{\"approve\":true}"), 400, 403, 404, 422);
        assertThat(status(approversOwn)).isEqualTo("PENDING");

        // the approvals list of the other business never shows up here, and mine never lists the approver's own request
        MvcResult list = assertSurvives(alphaWide, new Call("GET", "/api/v1/approvals"));
        assertThat(list.getResponse().getContentAsString()).doesNotContain(approversOwn.toString());
    }

    @Test
    void aRegularizationOfAnotherBusinessCannotBeDecided() throws Exception {
        Call call = new Call("PUT", "/api/v1/attendance/regularizations/" + UUID.randomUUID() + "/decision", "{\"approve\":false,\"note\":\"nope\"}");
        assertRefused(alphaWide, call, 400, 404, 422);
    }
}
