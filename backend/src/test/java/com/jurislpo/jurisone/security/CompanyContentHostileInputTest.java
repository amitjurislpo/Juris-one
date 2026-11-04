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

/** Announcements, policies, assets and HR letters: refusals, hostile filters / ids / bodies, and no read or change across businesses. */
class CompanyContentHostileInputTest extends HostileInputTestBase {

    static final String ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
    static final String ANNOUNCEMENT = "{\"title\":\"Office closed\",\"body\":\"Closed on Friday.\",\"audience\":\"ALL\"}";
    static final String POLICY = "{\"title\":\"Code of conduct\",\"body\":\"Be kind.\"}";
    static final String ASSET = "{\"tag\":\"LT-1\",\"type\":\"LAPTOP\",\"make\":\"Dell\",\"model\":\"Latitude\",\"serial\":\"SN1\",\"purchaseDate\":\"2025-04-01\"}";

    String betaAnnouncementId, betaPolicyId, betaAssetId, betaTemplateId;
    String alphaAnnouncementId, alphaPolicyId, alphaAssetId, alphaTemplateId;

    String created(Person person, Call call) throws Exception {
        MvcResult result = send(person, call);
        assertThat(result.getResponse().getStatus()).as(call + " " + result.getResponse().getContentAsString()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText();
    }

    @BeforeEach
    void content() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        betaAnnouncementId = created(betaWide, new Call("POST", "/api/v1/announcements", ANNOUNCEMENT.replace("Office closed", BETA_MARKER + " notice")));
        betaPolicyId = created(betaWide, new Call("POST", "/api/v1/policies", POLICY.replace("Code of conduct", BETA_MARKER + " policy " + tag)));
        betaAssetId = created(betaWide, new Call("POST", "/api/v1/assets", ASSET.replace("LT-1", BETA_MARKER + tag)));
        alphaAnnouncementId = created(alphaWide, new Call("POST", "/api/v1/announcements", ANNOUNCEMENT));
        alphaPolicyId = created(alphaWide, new Call("POST", "/api/v1/policies", POLICY.replace("Code of conduct", "Alpha policy " + tag)));
        alphaAssetId = created(alphaWide, new Call("POST", "/api/v1/assets", ASSET.replace("LT-1", "AL" + tag)));
        jdbc.query("SELECT jurisone_seed_letter_templates(?)", resultSet -> {}, alpha.business());
        jdbc.query("SELECT jurisone_seed_letter_templates(?)", resultSet -> {}, beta.business());
        betaTemplateId = jdbc.queryForObject("SELECT id::text FROM letter_templates WHERE business_id = ? AND type = 'EXPERIENCE' ORDER BY id LIMIT 1", String.class, beta.business());
        alphaTemplateId = jdbc.queryForObject("SELECT id::text FROM letter_templates WHERE business_id = ? AND type = 'EXPERIENCE' ORDER BY id LIMIT 1", String.class, alpha.business());
    }

    /** Endpoints any signed-in employee may call. */
    static Stream<Call> employeeEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/announcements"), new Call("GET", "/api/v1/policies/mine"), new Call("GET", "/api/v1/policies/pending"),
                new Call("POST", "/api/v1/policies/" + ID + "/acknowledgements", "{\"version\":1}"), new Call("GET", "/api/v1/policies/" + ID + "/versions/1/file"),
                new Call("GET", "/api/v1/assets/mine"), new Call("GET", "/api/v1/letters/mine"), new Call("GET", "/api/v1/letters/" + ID));
    }

    /** Endpoints that need a management permission. */
    static Stream<Call> managerEndpoints() {
        return Stream.of(new Call("GET", "/api/v1/announcements?scope=all"), new Call("POST", "/api/v1/announcements", ANNOUNCEMENT), new Call("PUT", "/api/v1/announcements/" + ID, ANNOUNCEMENT),
                new Call("PUT", "/api/v1/announcements/" + ID + "/status", "{\"status\":\"UNPUBLISHED\"}"),
                new Call("GET", "/api/v1/policies"), new Call("POST", "/api/v1/policies", POLICY), new Call("GET", "/api/v1/policies/" + ID), new Call("PUT", "/api/v1/policies/" + ID, POLICY),
                new Call("DELETE", "/api/v1/policies/" + ID), new Call("PUT", "/api/v1/policies/" + ID + "/status", "{\"status\":\"PUBLISHED\"}"), new Call("DELETE", "/api/v1/policies/" + ID + "/file"),
                new Call("GET", "/api/v1/policies/" + ID + "/file"), new Call("POST", "/api/v1/policies/" + ID + "/reminders"), new Call("GET", "/api/v1/policies/" + ID + "/acknowledgements/export"),
                new Call("GET", "/api/v1/assets"), new Call("GET", "/api/v1/assets/assignees"), new Call("POST", "/api/v1/assets", ASSET), new Call("GET", "/api/v1/assets/" + ID),
                new Call("PUT", "/api/v1/assets/" + ID, ASSET), new Call("DELETE", "/api/v1/assets/" + ID), new Call("POST", "/api/v1/assets/" + ID + "/assignments", "{\"employeeId\":\"" + ID + "\"}"),
                new Call("PATCH", "/api/v1/assets/" + ID + "/assignments/current", "{\"condition\":\"GOOD\"}"),
                new Call("GET", "/api/v1/letters/fields"), new Call("GET", "/api/v1/letters/templates"), new Call("GET", "/api/v1/letters/templates/" + ID),
                new Call("POST", "/api/v1/letters/templates", "{\"type\":\"EXPERIENCE\",\"name\":\"Mine\",\"subject\":\"Hello\",\"body\":\"Hello {{employeeName}}\"}"),
                new Call("PUT", "/api/v1/letters/templates/" + ID, "{\"name\":\"Mine\",\"subject\":\"Hello\",\"body\":\"Hello {{employeeName}}\"}"), new Call("GET", "/api/v1/letters/employees"),
                new Call("GET", "/api/v1/letters/previews?templateId=" + ID + "&employeeId=" + ID), new Call("POST", "/api/v1/letters", "{\"templateId\":\"" + ID + "\",\"employeeId\":\"" + ID + "\"}"),
                new Call("GET", "/api/v1/letters"));
    }

    static Stream<Call> everyEndpoint() {
        return Stream.concat(employeeEndpoints(), managerEndpoints());
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

    /** Every {id} endpoint, with the id in the first position of the path. */
    static Stream<Call> endpointsWithAnId() {
        return Stream.concat(employeeEndpoints(), managerEndpoints()).filter(call -> call.path().contains(ID));
    }

    @ParameterizedTest
    @MethodSource("endpointsWithAnId")
    void malformedIdsAreRefusedWithoutAnError(Call call) throws Exception {
        for (String bad : BAD_IDS) {
            Call hostile = new Call(call.method(), call.path().replace(ID, enc(bad)), call.body() == null ? null : call.body().replace(ID, bad));
            MvcResult result = send(alphaWide, hostile);
            if (result.getResponse().getStatus() == 400 && result.getResponse().getContentAsString().isEmpty()) continue; // URL firewall (encoded percent)
            assertThat(result.getResponse().getStatus()).as(hostile.toString()).isBetween(400, 499);
            assertRefused(alphaWide, hostile, result, result.getResponse().getStatus());
        }
    }

    /** The other business's ids, used by this business's most powerful user: every one must be "not there", and nothing may change. */
    @Test
    void anotherBusinessesObjectsCannotBeReadChangedOrDeleted() throws Exception {
        List<Call> attacks = new ArrayList<>(List.of(
                new Call("PUT", "/api/v1/announcements/" + betaAnnouncementId, ANNOUNCEMENT.replace("Office closed", "Hijacked")),
                new Call("PUT", "/api/v1/announcements/" + betaAnnouncementId + "/status", "{\"status\":\"UNPUBLISHED\"}"),
                new Call("GET", "/api/v1/policies/" + betaPolicyId), new Call("PUT", "/api/v1/policies/" + betaPolicyId, POLICY.replace("Code of conduct", "Hijacked")),
                new Call("DELETE", "/api/v1/policies/" + betaPolicyId), new Call("PUT", "/api/v1/policies/" + betaPolicyId + "/status", "{\"status\":\"PUBLISHED\"}"),
                new Call("DELETE", "/api/v1/policies/" + betaPolicyId + "/file"), new Call("GET", "/api/v1/policies/" + betaPolicyId + "/file"),
                new Call("POST", "/api/v1/policies/" + betaPolicyId + "/reminders"), new Call("GET", "/api/v1/policies/" + betaPolicyId + "/acknowledgements/export"),
                new Call("GET", "/api/v1/policies/" + betaPolicyId + "/versions/1/file"), new Call("POST", "/api/v1/policies/" + betaPolicyId + "/acknowledgements", "{\"version\":1}"),
                new Call("GET", "/api/v1/assets/" + betaAssetId), new Call("PUT", "/api/v1/assets/" + betaAssetId, ASSET.replace("LT-1", "Hijacked")), new Call("DELETE", "/api/v1/assets/" + betaAssetId),
                new Call("POST", "/api/v1/assets/" + betaAssetId + "/assignments", "{\"employeeId\":\"" + alphaPlain.id() + "\"}"),
                new Call("PATCH", "/api/v1/assets/" + betaAssetId + "/assignments/current", "{\"condition\":\"GOOD\"}"),
                new Call("GET", "/api/v1/letters/templates/" + betaTemplateId),
                new Call("PUT", "/api/v1/letters/templates/" + betaTemplateId, "{\"name\":\"Hijacked\",\"subject\":\"Hello\",\"body\":\"Hello {{employeeName}}\"}"),
                new Call("GET", "/api/v1/letters/previews?templateId=" + betaTemplateId + "&employeeId=" + alphaPlain.id()),
                new Call("GET", "/api/v1/letters/previews?templateId=" + alphaTemplateId + "&employeeId=" + betaPlain.id()),
                new Call("POST", "/api/v1/letters", "{\"templateId\":\"" + betaTemplateId + "\",\"employeeId\":\"" + alphaPlain.id() + "\",\"lastWorkingDate\":\"2031-05-15\"}"),
                new Call("POST", "/api/v1/letters", "{\"templateId\":\"" + alphaTemplateId + "\",\"employeeId\":\"" + betaPlain.id() + "\",\"lastWorkingDate\":\"2031-05-15\"}")
                ));
        for (Call attack : attacks) {
            MvcResult result = send(alphaWide, attack);
            assertThat(result.getResponse().getStatus()).as(attack + " -> " + result.getResponse().getContentAsString()).isBetween(400, 499);
            assertRefused(alphaWide, attack, result, result.getResponse().getStatus());
        }
        // another business's person has no letters to list here (an empty list, nothing of theirs)
        assertThat(send(alphaWide, new Call("GET", "/api/v1/letters?employeeId=" + betaPlain.id())).getResponse().getContentAsString()).doesNotContain(BETA_MARKER);
        // and beta's rows are exactly as they were
        assertThat(jdbc.queryForObject("SELECT title FROM announcements WHERE id = ?::uuid", String.class, betaAnnouncementId)).startsWith(BETA_MARKER);
        assertThat(jdbc.queryForObject("SELECT status::text FROM announcements WHERE id = ?::uuid", String.class, betaAnnouncementId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policies WHERE id = ?::uuid", Long.class, betaPolicyId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM policies WHERE id = ?::uuid", String.class, betaPolicyId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT tag FROM assets WHERE id = ?::uuid", String.class, betaAssetId)).startsWith(BETA_MARKER);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_assignments WHERE asset_id = ?::uuid", Long.class, betaAssetId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_letters WHERE business_id = ? OR employee_id IN (?::uuid, ?::uuid)", Long.class, beta.business(), alphaPlain.id(), betaPlain.id())).isZero(); // no letter for beta, none for these two people (other tests issue letters for other alpha people)
        assertThat(jdbc.queryForObject("SELECT name FROM letter_templates WHERE id = ?::uuid", String.class, betaTemplateId)).isNotEqualTo("Hijacked");
    }

    @Test
    void listsShowOnlyMyOwnBusiness() throws Exception {
        for (Call call : List.of(new Call("GET", "/api/v1/announcements"), new Call("GET", "/api/v1/announcements?scope=all"), new Call("GET", "/api/v1/policies"), new Call("GET", "/api/v1/policies/mine"),
                new Call("GET", "/api/v1/policies/pending"), new Call("GET", "/api/v1/assets"), new Call("GET", "/api/v1/assets/assignees"), new Call("GET", "/api/v1/letters"), new Call("GET", "/api/v1/letters/employees"),
                new Call("GET", "/api/v1/letters/templates"))) {
            MvcResult result = assertSurvives(alphaWide, call);
            assertThat(result.getResponse().getStatus()).as(call.toString()).isEqualTo(200);
        }
    }

    @Test
    void anotherPersonsThingsAreNotMine() throws Exception {
        String assetId = alphaAssetId;
        assertThat(send(alphaWide, new Call("POST", "/api/v1/assets/" + assetId + "/assignments", "{\"employeeId\":\"" + alphaPlainOther.id() + "\"}")).getResponse().getStatus()).isEqualTo(201);
        assertThat(send(alphaPlain, new Call("GET", "/api/v1/assets/mine")).getResponse().getContentAsString()).doesNotContain(assetId).doesNotContain(alphaPlainOther.id().toString());
        assertThat(send(alphaPlainOther, new Call("GET", "/api/v1/assets/mine")).getResponse().getContentAsString()).contains(assetId);
        // a letter issued to someone else cannot be opened by me, by id or as a PDF
        MvcResult issued = send(alphaWide, new Call("POST", "/api/v1/letters", "{\"templateId\":\"" + alphaTemplateId + "\",\"employeeId\":\"" + alphaPlainOther.id() + "\",\"lastWorkingDate\":\"2031-05-15\"}"));
        if (issued.getResponse().getStatus() == 201) {
            String letterId = json.readTree(issued.getResponse().getContentAsString()).at("/data/id").asText();
            assertRefused(alphaPlain, new Call("GET", "/api/v1/letters/" + letterId), 403, 404);
            assertRefused(alphaPlain, new Call("GET", "/api/v1/letters/" + letterId + "/pdf"), 403, 404);
            assertThat(send(alphaPlain, new Call("GET", "/api/v1/letters/mine")).getResponse().getContentAsString()).doesNotContain(letterId);
        }
        // accepting a policy is for the policy of my own business, as me
        assertRefused(alphaPlain, new Call("POST", "/api/v1/policies/" + betaPolicyId + "/acknowledgements", "{\"version\":1}"), 404, 403, 422);
    }

    static Stream<Call> listsWithFilters() {
        return Stream.of(new Call("GET", "/api/v1/announcements?scope="), new Call("GET", "/api/v1/announcements?limit="), new Call("GET", "/api/v1/policies?status="), new Call("GET", "/api/v1/policies?search="),
                new Call("GET", "/api/v1/assets?search="), new Call("GET", "/api/v1/assets?status="), new Call("GET", "/api/v1/assets?type="), new Call("GET", "/api/v1/assets?sort="),
                new Call("GET", "/api/v1/assets?sortDirection="), new Call("GET", "/api/v1/assets?page="), new Call("GET", "/api/v1/letters?employeeId="),
                new Call("GET", "/api/v1/letters/previews?templateId=" + ID + "&employeeId="), new Call("GET", "/api/v1/letters/previews?employeeId=" + ID + "&templateId="),
                new Call("GET", "/api/v1/letters/previews?templateId=" + ID + "&employeeId=" + ID + "&lastWorkingDate="), new Call("GET", "/api/v1/policies/" + ID + "/acknowledgements/export?format="));
    }

    @ParameterizedTest
    @MethodSource("listsWithFilters")
    void hostileFiltersNeverBreakOrLeak(Call call) throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.add("x".repeat(100_000));
        values.add("99999999999999999999");
        values.add("-1");
        for (String hostile : values) {
            MvcResult result = assertSurvives(alphaWide, new Call("GET", call.path() + enc(hostile)));
            if (result.getResponse().getStatus() >= 400) assertThat(result.getResponse().getStatus()).as(call.path() + hostile).isBetween(400, 499);
        }
        assertDatabaseIntact();
    }

    /** One text field of a valid body, replaced by hostile values: refused (400/422) or stored as plain text, never a 5xx; an over-long value is always refused. */
    void fuzzField(Person person, String method, String path, String validBody, String field) throws Exception {
        List<String> values = new ArrayList<>(HOSTILE_TEXT);
        values.addAll(List.of("", " ", "x".repeat(100_001), "2041-13-45", "9999-99-99", "-1", "99999999999999999999"));
        for (String hostile : values) {
            ObjectNode body = (ObjectNode) json.readTree(validBody);
            body.put(field, hostile);
            Call call = new Call(method, path, body.toString());
            MvcResult result = assertSurvives(person, call);
            int status = result.getResponse().getStatus();
            if (hostile.length() > 10_000) assertThat(status).as("an over-long " + field + " is refused: " + call).isBetween(400, 499);
            if (status >= 400) assertAudited(person, call, result, status);
        }
    }

    static Stream<String> announcementFields() {
        return Stream.of("title", "body", "audience", "expiresOn", "pinned");
    }

    @ParameterizedTest
    @MethodSource("announcementFields")
    void hostileAnnouncementBodies(String field) throws Exception {
        fuzzField(alphaWide, "POST", "/api/v1/announcements", ANNOUNCEMENT, field);
        fuzzField(alphaWide, "PUT", "/api/v1/announcements/" + alphaAnnouncementId, ANNOUNCEMENT, field);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements WHERE length(title) > 10000 OR length(body) > 10000", Long.class)).isZero();
        assertDatabaseIntact();
    }

    @Test
    void hostileAnnouncementStatus() throws Exception {
        for (String hostile : HOSTILE_TEXT) assertRefused(alphaWide, new Call("PUT", "/api/v1/announcements/" + alphaAnnouncementId + "/status", "{\"status\":" + quote(hostile) + "}"), 400);
    }

    static Stream<String> policyFields() {
        return Stream.of("title", "body");
    }

    @ParameterizedTest
    @MethodSource("policyFields")
    void hostilePolicyBodies(String field) throws Exception {
        fuzzField(alphaWide, "POST", "/api/v1/policies", POLICY, field);
        fuzzField(alphaWide, "PUT", "/api/v1/policies/" + alphaPolicyId, POLICY, field);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policies WHERE length(title) > 10000", Long.class)).isZero();
    }

    @Test
    void hostilePolicyStatusAndAcknowledgement() throws Exception {
        for (String hostile : HOSTILE_TEXT) assertRefused(alphaWide, new Call("PUT", "/api/v1/policies/" + alphaPolicyId + "/status", "{\"status\":" + quote(hostile) + "}"), 400, 422);
        for (String version : List.of("0", "-1", "99999999999", "\"x\"", "1.5", "null", "[]")) {
            MvcResult result = assertSurvives(alphaPlain, new Call("POST", "/api/v1/policies/" + alphaPolicyId + "/acknowledgements", "{\"version\":" + version + "}"));
            assertThat(result.getResponse().getStatus()).as("version " + version).isBetween(400, 499);
        }
        for (String version : List.of("0", "-1", "99999999999", "x", "1.5", "1%27")) {
            MvcResult result = assertSurvives(alphaPlain, new Call("GET", "/api/v1/policies/" + alphaPolicyId + "/versions/" + version + "/file"));
            assertThat(result.getResponse().getStatus()).as("version " + version).isBetween(400, 499);
        }
    }

    @Test
    void policyFilesMustBePdfsWhateverTheyAreCalled() throws Exception {
        for (String[] upload : new String[][] {{"../../etc/passwd.pdf", "text/html", "<script>alert(1)</script>"}, {"x.pdf", "application/pdf", "not a pdf"}, 
                {"x.html", "text/html", "<html></html>"}, {"x.pdf", "application/pdf", ""}, {"'; DROP TABLE policies; --.pdf", "application/pdf", "hello"}}) {
            MockMultipartFile file = new MockMultipartFile("file", upload[0], upload[1], upload[2].getBytes());
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, "/api/v1/policies/{id}/file", alphaPolicyId).file(file).cookie(alphaWide.cookie())
                    .header("origin", "http://localhost:3005").header("host", "localhost:3005")).andReturn();
            assertThat(result.getResponse().getStatus()).as(upload[0] + " " + result.getResponse().getContentAsString()).isBetween(400, 499);
            assertNoLeak(result.getResponse().getContentAsString());
            assertAudited(alphaWide, new Call("PUT", "/api/v1/policies/" + alphaPolicyId + "/file"), result, result.getResponse().getStatus());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM policies WHERE id = ?::uuid AND file_name IS NOT NULL", Long.class, alphaPolicyId)).isZero();
        // and a request that is not multipart at all
        MvcResult notMultipart = send(alphaWide, new Call("PUT", "/api/v1/policies/" + alphaPolicyId + "/file", "{}"));
        assertThat(notMultipart.getResponse().getStatus()).isBetween(400, 499);
    }

    static Stream<String> assetFields() {
        return Stream.of("tag", "type", "make", "model", "serial", "purchaseDate", "status", "notes");
    }

    @ParameterizedTest
    @MethodSource("assetFields")
    void hostileAssetBodies(String field) throws Exception {
        fuzzField(alphaWide, "POST", "/api/v1/assets", ASSET.replace("LT-1", "H" + UUID.randomUUID().toString().substring(0, 6)), field);
        fuzzField(alphaWide, "PUT", "/api/v1/assets/" + alphaAssetId, ASSET.replace("LT-1", "AL" + UUID.randomUUID().toString().substring(0, 6)), field);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assets WHERE length(tag) > 10000 OR length(coalesce(make, '')) > 10000 OR length(coalesce(serial, '')) > 10000", Long.class)).isZero();
        assertDatabaseIntact();
    }

    @Test
    void hostileAssetAssignmentAndReturn() throws Exception {
        String asset = created(alphaWide, new Call("POST", "/api/v1/assets", ASSET.replace("LT-1", "AS" + UUID.randomUUID().toString().substring(0, 6))));
        for (String field : List.of("employeeId", "assignedOn", "notes")) {
            fuzzField(alphaWide, "POST", "/api/v1/assets/" + asset + "/assignments", "{\"employeeId\":\"" + alphaPlain.id() + "\",\"assignedOn\":\"2026-01-05\",\"notes\":\"ok\"}", field);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset_assignments WHERE asset_id = ?::uuid", Long.class, asset)).isLessThanOrEqualTo(1);
        for (String field : List.of("condition", "returnedOn", "notes")) {
            fuzzField(alphaWide, "PATCH", "/api/v1/assets/" + asset + "/assignments/current", "{\"condition\":\"GOOD\",\"returnedOn\":\"2026-03-01\",\"notes\":\"ok\"}", field);
        }
    }

    static Stream<String> templateFields() {
        return Stream.of("type", "name", "subject", "body", "active");
    }

    @ParameterizedTest
    @MethodSource("templateFields")
    void hostileLetterTemplateBodies(String field) throws Exception {
        String valid = "{\"type\":\"EXPERIENCE\",\"name\":\"Template\",\"subject\":\"Subject line\",\"body\":\"Hello {{employeeName}}, thanks.\",\"active\":true}";
        fuzzField(alphaWide, "POST", "/api/v1/letters/templates", valid, field);
        fuzzField(alphaWide, "PUT", "/api/v1/letters/templates/" + alphaTemplateId, valid, field);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM letter_templates WHERE length(body) > 10000 OR length(name) > 10000 OR length(subject) > 10000", Long.class)).isZero();
    }

    @Test
    void hostileLetterIssuingNeverIssuesAnything() throws Exception {
        for (String field : List.of("templateId", "employeeId", "lastWorkingDate")) {
            fuzzField(alphaWide, "POST", "/api/v1/letters", "{\"templateId\":\"" + alphaTemplateId + "\",\"employeeId\":\"" + alphaPlain.id() + "\",\"lastWorkingDate\":\"2026-02-30\"}", field);
        }
        // a letter text built from hostile merge values is escaped, never raw markup: the hostile names are never put in a letter's HTML as-is
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_letters WHERE business_id = ? AND body LIKE '%<script>%'", Long.class, alpha.business())).isZero();
    }
}
