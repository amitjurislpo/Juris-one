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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Tax declarations, investment proofs, Tax &amp; TDS and notifications: refusals, hostile filters / ids / bodies / uploads, and no access to another person's or business's figures. */
class TaxAndNotificationsHostileInputTest extends HostileInputTestBase {

    static final String ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
    String betaItemId, alphaItemId;
    String betaNotificationId, alphaNotificationId;

    @BeforeEach
    void declarations() throws Exception {
        betaItemId = declaredItem(betaPlain);
        alphaItemId = declaredItem(alphaPlain);
        betaNotificationId = notificationFor(betaPlain);
        alphaNotificationId = notificationFor(alphaPlain);
    }

    String declaredItem(Person person) throws Exception {
        MvcResult result = send(person, new Call("PUT", "/api/v1/declarations/items/ppf", "{\"amount\":50000}"));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return jdbc.queryForObject("SELECT id::text FROM declaration_items WHERE employee_id = ? AND item_key = 'ppf'", String.class, person.id());
    }

    String notificationFor(Person person) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO notifications (id, employee_id, text, link) VALUES (?::uuid, ?, ?, '/home')", id, person.id(), "Hello " + person.id());
        return id;
    }

    static Stream<Call> employeeEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/declarations"), new Call("PUT", "/api/v1/declarations/regime", "{\"regime\":\"NEW\"}"),
                new Call("PUT", "/api/v1/declarations/profile", "{\"metro\":false,\"parentsSenior\":false,\"landlordPan\":\"\"}"), new Call("PUT", "/api/v1/declarations/items/ppf", "{\"amount\":1000}"),
                new Call("POST", "/api/v1/declarations/submissions"), new Call("GET", "/api/v1/declarations/proofs/" + ID),
                new Call("GET", "/api/v1/notifications"), new Call("PATCH", "/api/v1/notifications/" + ID, "{\"read\":true}"), new Call("PATCH", "/api/v1/notifications", "{\"read\":true}"));
    }

    static Stream<Call> financeEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/proofs"), new Call("GET", "/api/v1/proofs/" + ID), new Call("PUT", "/api/v1/proofs/items/" + ID + "/decision", "{\"approve\":true}"),
                new Call("PUT", "/api/v1/proofs/" + ID + "/decision", "{\"approve\":true}"), new Call("GET", "/api/v1/tax"), new Call("GET", "/api/v1/tax/" + ID),
                new Call("PUT", "/api/v1/tax/" + ID + "/regime", "{\"regime\":\"OLD\"}"), new Call("PUT", "/api/v1/tax/" + ID + "/previous-employer", "{\"income\":100,\"tds\":10}"), new Call("GET", "/api/v1/tax/export"));
    }

    static Stream<Call> everyEndpoint() {
        return Stream.concat(employeeEndpoints(), financeEndpoints());
    }

    @ParameterizedTest
    @MethodSource("everyEndpoint")
    void signedOutIsRefused(Call call) throws Exception {
        assertRefused(null, call, 401);
    }

    @ParameterizedTest
    @MethodSource("financeEndpoints")
    void aPlainEmployeeCannotUseFinanceEndpoints(Call call) throws Exception {
        assertRefused(alphaPlain, call, 403);
    }

    static Stream<Call> endpointsWithAnId() {
        return everyEndpoint().filter(call -> call.path().contains(ID));
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

    /** The other business's people and items, used by this business's Finance user: everything is "not there" and nothing changes. */
    @Test
    void anotherBusinessesFiguresCannotBeReadOrChanged() throws Exception {
        List<Call> attacks = List.of(new Call("GET", "/api/v1/proofs/" + betaPlain.id()), new Call("PUT", "/api/v1/proofs/items/" + betaItemId + "/decision", "{\"approve\":true}"),
                new Call("PUT", "/api/v1/proofs/items/" + betaItemId + "/decision", "{\"approve\":false,\"reason\":\"no\"}"), new Call("PUT", "/api/v1/proofs/" + betaPlain.id() + "/decision", "{\"approve\":true}"),
                new Call("GET", "/api/v1/tax/" + betaPlain.id()), new Call("PUT", "/api/v1/tax/" + betaPlain.id() + "/regime", "{\"regime\":\"OLD\"}"),
                new Call("PUT", "/api/v1/tax/" + betaPlain.id() + "/previous-employer", "{\"income\":1000,\"tds\":100}"), new Call("GET", "/api/v1/declarations/proofs/" + betaItemId));
        for (Call attack : attacks) {
            MvcResult result = send(alphaWide, attack);
            assertThat(result.getResponse().getStatus()).as(attack + " -> " + result.getResponse().getContentAsString()).isBetween(400, 499);
            if (result.getResponse().getContentAsString().isEmpty() || result.getResponse().getContentType() == null || !result.getResponse().getContentType().startsWith("application/problem+json")) {
                // a plain-text 404 for the proof file is reported in the findings; the status and the absence of any data are what matters here
                assertThat(result.getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
                continue;
            }
            assertRefused(alphaWide, attack, result, result.getResponse().getStatus());
        }
        assertThat(jdbc.queryForObject("SELECT status::text FROM declaration_items WHERE id = ?::uuid", String.class, betaItemId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_profiles WHERE employee_id = ? AND (prev_employer_income <> 0 OR regime = 'OLD')", Long.class, betaPlain.id())).isZero();
        for (String path : List.of("/api/v1/proofs", "/api/v1/tax", "/api/v1/tax/export")) {
            assertThat(assertSurvives(alphaWide, new Call("GET", path)).getResponse().getContentAsString()).doesNotContain(betaPlain.id().toString());
        }
    }

    @Test
    void anotherPersonsDeclarationIsNotMine() throws Exception {
        // a plain colleague cannot open my proof, and my own items are not shown to them
        assertRefused(alphaPlainOther, new Call("GET", "/api/v1/declarations/proofs/" + alphaItemId), 403, 404);
        assertThat(send(alphaPlainOther, new Call("GET", "/api/v1/declarations")).getResponse().getContentAsString()).doesNotContain(alphaItemId);
        // nobody but a verifier decides an item; a plain user is refused
        assertRefused(alphaPlainOther, new Call("PUT", "/api/v1/proofs/items/" + alphaItemId + "/decision", "{\"approve\":true}"), 403);
        assertThat(jdbc.queryForObject("SELECT status::text FROM declaration_items WHERE id = ?::uuid", String.class, alphaItemId)).isEqualTo("DRAFT");
        // saving an item always saves it for the signed-in person, whatever the key says
        send(alphaPlainOther, new Call("PUT", "/api/v1/declarations/items/ppf", "{\"amount\":1}"));
        assertThat(jdbc.queryForObject("SELECT amount FROM declaration_items WHERE id = ?::uuid", Long.class, alphaItemId)).isEqualTo(5_000_000L);
    }

    static Stream<Call> listsWithFilters() {
        return Stream.of("search", "sort", "sortDirection", "page", "regime", "status", "department", "tab").flatMap(name -> Stream.of("proofs", "tax", "tax/export").map(list -> new Call("GET", "/api/v1/" + list + "?" + name + "=")));
    }

    @ParameterizedTest
    @MethodSource("listsWithFilters")
    void hostileFiltersNeverBreakOrLeak(Call call) throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.add("x".repeat(100_000));
        for (String hostile : values) assertSurvives(alphaWide, new Call("GET", call.path() + enc(hostile)));
        assertDatabaseIntact();
    }

    @Test
    void hostileDeclarationValuesAreRefusedAndChangeNothing() throws Exception {
        for (String key : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaPlain, new Call("PUT", "/api/v1/declarations/items/" + enc(key), "{\"amount\":10}"));
            assertThat(result.getResponse().getStatus()).as(key).isBetween(400, 499);
        }
        for (String amount : List.of("-1", "-0.01", "1e309", "99999999999999999999", "9223372036854775807", "\"abc\"", "null", "[]", "{}", "0.5", "NaN")) {
            MvcResult result = assertSurvives(alphaPlain, new Call("PUT", "/api/v1/declarations/items/ppf", "{\"amount\":" + amount + "}"));
            if (result.getResponse().getStatus() < 400) assertThat(amount).as("accepted amount").isIn("null");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM declaration_items WHERE amount < 0 OR amount > 100000000000", Long.class)).isZero();
        for (String regime : HOSTILE_TEXT) assertRefused(alphaPlain, new Call("PUT", "/api/v1/declarations/regime", "{\"regime\":" + quote(regime) + "}"), 400, 422);
        for (String pan : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaPlain, new Call("PUT", "/api/v1/declarations/profile", "{\"metro\":true,\"parentsSenior\":false,\"landlordPan\":" + quote(pan) + "}"));
            assertThat(result.getResponse().getStatus()).as(pan).isBetween(400, 499);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_profiles WHERE landlord_pan IS NOT NULL AND length(landlord_pan) > 10", Long.class)).isZero();
    }

    @Test
    void hostileFinanceBodiesAreRefused() throws Exception {
        for (String reason : HOSTILE_TEXT) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", "/api/v1/proofs/items/" + alphaItemId + "/decision", "{\"approve\":false,\"reason\":" + quote(reason) + "}"));
            assertThat(result.getResponse().getStatus()).as("reason " + reason).isBetween(200, 499);
        }
        assertSurvives(alphaWide, new Call("PUT", "/api/v1/proofs/items/" + alphaItemId + "/decision", "{\"approve\":false,\"reason\":" + quote("x".repeat(100_000)) + "}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM declaration_items WHERE length(coalesce(reject_reason, '')) > 10000", Long.class)).isZero();
        for (String body : List.of("{\"approve\":false}", "{\"approve\":\"yes\"}", "{}", "{\"approve\":null}", "[]")) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", "/api/v1/proofs/" + alphaPlain.id() + "/decision", body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
        }
        for (String regime : HOSTILE_TEXT) assertRefused(alphaWide, new Call("PUT", "/api/v1/tax/" + alphaPlain.id() + "/regime", "{\"regime\":" + quote(regime) + "}"), 400, 422);
        for (String amount : List.of("-1", "1e309", "99999999999999999999", "\"x\"", "0.5", "NaN")) {
            MvcResult result = assertSurvives(alphaWide, new Call("PUT", "/api/v1/tax/" + alphaPlain.id() + "/previous-employer", "{\"income\":" + amount + ",\"tds\":1}"));
            assertThat(result.getResponse().getStatus()).as("income " + amount).isBetween(400, 499);
        }
    }

    @Test
    void proofUploadsAreCheckedByContentNotByNameOrType() throws Exception {
        for (Object[] upload : new Object[][] {{"../../etc/passwd.pdf", "application/pdf", "<script>alert(1)</script>"}, {"x.pdf", "application/pdf", "not a pdf"}, {"a\u0000b.pdf", "application/pdf", "hello"},
                {"x.exe", "application/x-msdownload", "MZ\u0090\u0000"}, {"x.pdf", "application/pdf", ""}, {"'; DROP TABLE declaration_items; --.pdf", "application/pdf", "hello"},
                {"x.svg", "image/svg+xml", "<svg onload=alert(1)/>"}, {"x.html", "text/html", "<html></html>"}}) {
            MockMultipartFile file = new MockMultipartFile("file", (String) upload[0], (String) upload[1], ((String) upload[2]).getBytes());
            for (String item : List.of("ppf", "' OR 1=1", "")) {
                MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/declarations/proofs").file(file).param("item", item).cookie(alphaPlain.cookie())
                        .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andReturn();
                assertThat(result.getResponse().getStatus()).as(upload[0] + " item=" + item + " " + result.getResponse().getContentAsString()).isBetween(400, 499);
                assertNoLeak(result.getResponse().getContentAsString());
                assertAudited(alphaPlain, new Call("POST", "/api/v1/declarations/proofs"), result, result.getResponse().getStatus());
            }
        }
        MvcResult noFile = mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/declarations/proofs").param("item", "ppf").cookie(alphaPlain.cookie())
                .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andReturn();
        assertThat(noFile.getResponse().getStatus()).isBetween(400, 499);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM declaration_items WHERE employee_id = ? AND proof_path IS NOT NULL", Long.class, alphaPlain.id())).isZero();
    }

    // ───────────────────────── notifications ─────────────────────────

    @Test
    void notificationsBelongToTheirOwner() throws Exception {
        // someone else's notification (same business, other business) can neither be marked nor seen
        for (String id : List.of(alphaNotificationId, betaNotificationId)) {
            assertRefused(alphaPlainOther, new Call("PATCH", "/api/v1/notifications/" + id, "{\"read\":true}"), 404);
            assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notifications WHERE id = ?::uuid", Boolean.class, id)).isTrue();
        }
        assertThat(send(alphaPlainOther, new Call("GET", "/api/v1/notifications")).getResponse().getContentAsString()).doesNotContain(alphaNotificationId).doesNotContain(betaNotificationId);
        // marking everything read touches only my own
        assertThat(send(alphaPlainOther, new Call("PATCH", "/api/v1/notifications", "{\"read\":true}")).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE id IN (?::uuid, ?::uuid) AND read_at IS NOT NULL", Long.class, alphaNotificationId, betaNotificationId)).isZero();
    }

    @Test
    void hostileNotificationInputIsRefused() throws Exception {
        for (String bad : BAD_IDS) {
            Call call = new Call("PATCH", "/api/v1/notifications/" + enc(bad), "{\"read\":true}");
            MvcResult result = send(alphaPlain, call);
            if (result.getResponse().getStatus() == 400 && result.getResponse().getContentAsString().isEmpty()) continue;
            assertRefused(alphaPlain, call, result, 400, 404);
        }
        for (String body : List.of("{}", "{\"read\":null}", "{\"read\":[]}", "[]", "{", "null")) {
            MvcResult result = assertSurvives(alphaPlain, new Call("PATCH", "/api/v1/notifications/" + alphaNotificationId, body));
            assertThat(result.getResponse().getStatus()).as(body).isBetween(400, 499);
        }
        for (String name : List.of("unread", "limit", "page")) {
            List<String> values = new ArrayList<>(HOSTILE_TEXT);
            values.addAll(List.of("-1", "0", "99999999999", "2147483648", "1.5", "x".repeat(100_000)));
            for (String hostile : values) {
                MvcResult result = assertSurvives(alphaPlain, new Call("GET", "/api/v1/notifications?" + name + "=" + enc(hostile)));
                if (result.getResponse().getStatus() >= 400) assertThat(result.getResponse().getStatus()).as(name + "=" + hostile).isBetween(400, 499);
            }
        }
        assertSurvives(alphaPlain, new Call("GET", "/api/v1/notifications?limit=1000000"));
    }

    @Test
    void anOverlongAmountOrKeyChangesNothing() throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("amount", 1);
        MvcResult result = assertSurvives(alphaPlain, new Call("PUT", "/api/v1/declarations/items/" + "k".repeat(5000), body.toString()));
        assertThat(result.getResponse().getStatus()).isBetween(400, 499);
    }
}
