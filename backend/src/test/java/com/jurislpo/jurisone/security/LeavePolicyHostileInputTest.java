package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MvcResult;

/** The leave year and year-end endpoints: sign-in and permission refusals, hostile bodies and codes, and nothing crossing from one business to another. */
class LeavePolicyHostileInputTest extends HostileInputTestBase {

    static final String POLICY = "/api/v1/settings/leave-policy";

    static Stream<Call> everyEndpoint() {
        return Stream.of(new Call("GET", POLICY), new Call("PUT", POLICY + "/year", "{\"startMonth\":4,\"startDay\":1}"),
                new Call("PATCH", POLICY + "/types/CL", "{\"yearEndAction\":\"COLLAPSE\"}"));
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
        assertThat(setting(alpha.business())).isNull();
    }

    @BeforeEach
    void clearLeavePolicies() {
        jdbc.update("DELETE FROM settings WHERE key = 'leave.policy' AND business_id IN (?, ?)", alpha.business(), beta.business());
    }

    String setting(java.util.UUID business) {
        return jdbc.query("SELECT value::text FROM settings WHERE key = 'leave.policy' AND business_id = ?", resultSet -> resultSet.next() ? resultSet.getString(1) : null, business);
    }

    @Test
    void hostileCodesNeverBreakOrWriteAnything() throws Exception {
        for (String hostile : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaWide, new Call("PATCH", POLICY + "/types/" + enc(hostile), "{\"yearEndAction\":\"COLLAPSE\"}"));
            assertThat(result.getResponse().getStatus()).as(hostile).isBetween(400, 404);
        }
        assertThat(setting(alpha.business())).isNull();
        assertDatabaseIntact();
    }

    @Test
    void hostileBodiesAreRefusedWithoutAnError() throws Exception {
        List<String> bodies = List.of("{", "[]", "null", "\"text\"", "{\"yearEndAction\":123}", "{\"yearEndAction\":[],\"carryForwardCap\":{}}", "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":1e999}",
                "{\"yearEndAction\":\"CARRY_CAPPED\",\"carryForwardCap\":\"NaN\"}", "{\"yearEndAction\":" + quote("x".repeat(100_000)) + "}");
        for (String hostile : HOSTILE_TEXT) {
            bodies = Stream.concat(bodies.stream(), Stream.of("{\"yearEndAction\":" + quote(hostile) + "}")).toList();
        }
        for (String body : bodies) {
            MvcResult result = assertSurvives(alphaWide, new Call("PATCH", POLICY + "/types/CL", body));
            assertThat(result.getResponse().getStatus()).as(body.length() > 80 ? "long body" : body).isBetween(400, 499);
        }
        for (String body : List.of("{", "[]", "null", "{\"startMonth\":\"1; DROP TABLE settings\",\"startDay\":1}", "{\"startMonth\":[1],\"startDay\":{}}", "{\"startMonth\":1,\"startDay\":" + "9".repeat(5000) + "}")) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", POLICY + "/year", body));
            assertThat(result.getResponse().getStatus()).as(body.length() > 80 ? "long body" : body).isBetween(400, 499);
        }
        assertThat(setting(alpha.business())).isNull();
        assertDatabaseIntact();
    }

    @Test
    void oneBusinessCannotChangeOrSeeAnothersLeavePolicy() throws Exception {
        assertThat(send(betaWide, new Call("PUT", POLICY + "/year", "{\"startMonth\":4,\"startDay\":1}")).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(betaWide, new Call("PATCH", POLICY + "/types/CL", "{\"yearEndAction\":\"CARRY_ALL\"}")).getResponse().getStatus()).isEqualTo(200);
        // alpha is untouched and sees its own defaults
        assertThat(setting(alpha.business())).isNull();
        String alphaView = send(alphaWide, new Call("GET", POLICY)).getResponse().getContentAsString();
        assertThat(alphaView).contains("\"startMonth\":1").contains("\"startDay\":1").doesNotContain("CARRY_ALL");
        assertThat(setting(beta.business())).contains("\"yearStartMonth\": 4");
        // an id of the other business in the request changes nothing: the business comes from the signed-in person
        MvcResult tampered = send(alphaWide, new Call("PUT", POLICY + "/year?businessId=" + beta.business(), "{\"startMonth\":6,\"startDay\":1,\"businessId\":\"" + beta.business() + "\"}"));
        assertThat(tampered.getResponse().getStatus()).isEqualTo(200);
        assertThat(setting(beta.business())).contains("\"yearStartMonth\": 4");
        assertThat(setting(alpha.business())).contains("\"yearStartMonth\": 6"); // it changed the signed-in person's own business only
    }
}
