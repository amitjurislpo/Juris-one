package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.EmployeeNameReader;
import com.jurislpo.jurisone.repository.PayrollOperationsRepository;
import com.jurislpo.jurisone.repository.PayrollRepository;
import com.jurislpo.jurisone.service.EmployeeNameDirectory;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Stage 2 of encrypting employee names and designations ({@code read-encrypted=true}, the tests' default): every read comes from the encrypted columns,
 * a missing copy falls back to the plaintext column and is counted, the in-memory search follows every edit, hostile search text is harmless,
 * the audit log holds no plain names and nothing is logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class EncryptedNamesReadTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired EmployeeNameReader nameReader;
    @Autowired EmployeeNameDirectory nameDirectory;
    @Autowired PayrollRepository payrollRepository;
    @Autowired PayrollOperationsRepository payrollOperations;

    TestData data;
    String suffix;
    UUID hrId, departmentId, employeeRoleId, hrRoleId;
    Cookie hr, owner;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("read-" + suffix);
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        employeeRoleId = data.role("employee", "self", "dir");
        hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add", "letters.manage");
        UUID ownerRoleId = data.role("owner", "self", "dir", "roles.manage", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Ops " + suffix, data.business());
        hrId = data.employee("HR" + suffix, "hr." + suffix + "@example.com", hrRoleId, "Hema Rao", "HR Head");
        data.employee("OW" + suffix, "owner." + suffix + "@example.com", ownerRoleId, "Oscar Owner", "Owner");
        hr = signIn("hr." + suffix + "@example.com");
        owner = signIn("owner." + suffix + "@example.com");
    }

    String personBody(String firstName, String lastName, String designation, String email) {
        return """
            {"firstName":"%s","middleName":"","lastName":"%s","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"%s","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}"""
                .formatted(firstName, lastName, email, designation, departmentId, hrId, employeeRoleId);
    }

    String body(String path, Cookie cookie) throws Exception {
        return mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ───────────────────────── reads come from the encrypted columns ─────────────────────────

    @Test
    void everyReadComesFromTheEncryptedColumnsNotThePlaintextOnes() throws Exception {
        UUID manager = data.employee("MG" + suffix, "mg." + suffix + "@example.com", employeeRoleId, "Maya Manager", "Team Lead");
        UUID person = data.employee("PE" + suffix, "pe." + suffix + "@example.com", employeeRoleId, "Real Name", "Chief Wizard");
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager, person);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", hrId, manager);
        // the plaintext columns now hold something else; only the encrypted copies still say who these people are
        jdbc.update("UPDATE employees SET full_name = 'PLAINLEAK', designation = 'plainjob', first_name = 'PLAINFIRST', last_name = 'PLAINLAST' WHERE id IN (?, ?)", person, manager);

        long fallbacksBefore = nameReader.fallbackCount();
        for (String path : List.of("/api/v1/people", "/api/v1/directory", "/api/v1/people/" + person, "/api/v1/people/export", "/api/v1/letters/employees", "/api/v1/people/form-options?id=" + person)) {
            String body = body(path, hr);
            assertThat(body).as(path).doesNotContain("PLAINLEAK", "plainjob", "PLAINFIRST", "PLAINLAST");
        }
        assertThat(body("/api/v1/people", hr)).contains("Real Name", "Chief Wizard", "Maya Manager");
        assertThat(body("/api/v1/people/" + person, hr)).contains("Real Name", "Chief Wizard", "Maya Manager");
        assertThat(body("/api/v1/people/export", hr)).contains("Real Name", "Chief Wizard", "Maya Manager");
        assertThat(body("/api/v1/directory", hr)).contains("Real Name", "Chief Wizard");

        // search and sort work on the decrypted text
        mockMvc.perform(get("/api/v1/people?search=real%20na").cookie(hr)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].name").value("Real Name"));
        mockMvc.perform(get("/api/v1/people?search=WIZARD").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/people?search=maya").cookie(hr)).andExpect(jsonPath("$.data.total").value(2)); // Maya herself and her report (manager name is searched)
        mockMvc.perform(get("/api/v1/people?search=plainleak").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/directory?search=plainjob").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/directory?search=wizard").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));

        // the names payroll prints on payslips come from the encrypted copies too
        TenantContext.run(data.business(), () -> payrollOperations.insertStructure(UUID.randomUUID(), person, "2026-01-01", 4000000L, 2000000L, 500000L, 1500000L, "JOINING", null));
        List<Map<String, Object>> payrollRows = TenantContext.call(data.business(), () -> payrollRepository.payrollEmployees("",
                new Object[] {Date.valueOf(LocalDate.of(2026, 10, 31)), Date.valueOf(LocalDate.of(2026, 10, 1)), Date.valueOf(LocalDate.of(2026, 10, 31))}));
        assertThat(payrollRows).extracting(row -> row.get("full_name")).containsExactly("Real Name");
        assertThat(payrollRows.get(0)).containsEntry("designation", "Chief Wizard").doesNotContainKeys("full_name_enc", "designation_enc");
        assertThat(nameReader.fallbackCount()).as("every row had its encrypted copy").isEqualTo(fallbacksBefore);
    }

    @Test
    void aMissingEncryptedCopyFallsBackToThePlaintextAndIsCounted(CapturedOutput output) throws Exception {
        UUID oldRow = data.employee("OL" + suffix, "ol." + suffix + "@example.com", employeeRoleId, "Legacy Person", "Archivist");
        jdbc.update("UPDATE employees SET full_name_enc = NULL, designation_enc = NULL WHERE id = ?", oldRow); // as before the backfill reached it
        long before = nameReader.fallbackCount();
        String list = body("/api/v1/people?search=legacy", hr);
        assertThat(json.readTree(list).at("/data/total").asInt()).isEqualTo(1);
        assertThat(list).contains("Legacy Person", "Archivist");
        assertThat(body("/api/v1/people/" + oldRow, hr)).contains("Legacy Person");
        assertThat(nameReader.fallbackCount()).isGreaterThan(before);
        assertThat(output.getAll()).doesNotContain("Legacy Person").doesNotContain("Archivist");
    }

    @Test
    void anEncryptedCopyThatCannotBeDecryptedFallsBackAndNeverShowsOrLogsTheBrokenValue(CapturedOutput output) throws Exception {
        UUID row = data.employee("BR" + suffix, "br." + suffix + "@example.com", employeeRoleId, "Broken Copy", "Tester");
        jdbc.update("UPDATE employees SET full_name_enc = 'v1.AAAA.BBBB.CCCC' WHERE id = ?", row);
        long before = nameReader.undecryptableCount();
        assertThat(body("/api/v1/people/" + row, hr)).contains("Broken Copy");
        assertThat(nameReader.undecryptableCount()).isGreaterThan(before);
        assertThat(output.getAll()).doesNotContain("Broken Copy").doesNotContain("v1.AAAA.BBBB.CCCC");
    }

    // ───────────────────────── the in-memory search follows every edit ─────────────────────────

    @Test
    void theHeldNamesAreReusedAndDroppedByAnyEmployeeWrite() throws Exception {
        String email = "cache." + suffix + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Cachetest", "Person", "Associate", email))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).at("/data/id").asText());
        mockMvc.perform(get("/api/v1/people?search=cachetest").cookie(hr)).andExpect(jsonPath("$.data.total").value(1)); // a new person is found at once

        long loads = nameDirectory.loadCount();
        mockMvc.perform(get("/api/v1/people?search=cachetest").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/people?search=person&sort=name").cookie(hr)).andExpect(status().isOk());
        assertThat(nameDirectory.loadCount()).as("the second and third lists reuse the held names").isEqualTo(loads);

        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr).content(personBody("Renamed", "Zed", "Principal Associate", email))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people?search=cachetest").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/people?search=renamed").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/people?search=principal").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        assertThat(nameDirectory.loadCount()).as("the edit made the next search load the names again").isGreaterThan(loads);
    }

    @Test
    void aBusinessNeverSearchesAnotherBusinessesPeople() throws Exception {
        TestData other = new TestData(jdbc).inBusiness("other-" + suffix);
        UUID otherRole = other.role("employee", "self", "dir");
        other.employee("OT" + suffix, "ot." + suffix + "@example.com", otherRole, "Stranger Danger", "Spy");
        mockMvc.perform(get("/api/v1/people?search=stranger").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/directory?search=spy").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
    }

    // ───────────────────────── hostile input ─────────────────────────

    @Test
    void hostileSearchTextIsTakenLiterallyAndNeverBreaksAnything() throws Exception {
        data.employee("PC" + suffix, "pc." + suffix + "@example.com", employeeRoleId, "Rate 100%_Done", "Percent");
        data.employee("PL" + suffix, "pl." + suffix + "@example.com", employeeRoleId, "Plain Name", "Plainer");
        mockMvc.perform(get("/api/v1/people?search=%25").cookie(hr)).andExpect(jsonPath("$.data.total").value(1)); // % is a character, not a wildcard
        mockMvc.perform(get("/api/v1/people?search=_").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/directory?search=%25_").cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/people?search=P_ain").cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        for (String hostile : List.of("'; DROP TABLE employees; --", "%27%20OR%201%3D1%20--", "%5C", "%00", "a%00b", "%25%25%25%25", "‮", "%3Cscript%3Ealert(1)%3C%2Fscript%3E", "x".repeat(5000))) {
            for (String path : List.of("/api/v1/people?search=", "/api/v1/directory?search=", "/api/v1/onboardings?search=", "/api/v1/people?sort=manager&search=",
                    "/api/v1/audit?search=")) {
                String body = mockMvc.perform(get(path + hostile).cookie(path.contains("audit") ? owner : hr)).andReturn().getResponse().getContentAsString();
                assertThat(body).as(path + " with hostile text").doesNotContain("\"status\":500", "Exception", "SQL");
            }
        }
        mockMvc.perform(get("/api/v1/people?search='%3B%20DROP%20TABLE%20employees%3B%20--").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ?", Integer.class, data.business())).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aBusinessWithTooManyPeopleIsAskedToNarrowTheSearch() throws Exception {
        TestData big = new TestData(jdbc).inBusiness("big-" + suffix);
        UUID role = big.role("hrm", "self", "dir", "people.view", "people.add");
        UUID worker = big.role("employee", "self", "dir");
        big.employee("BG" + suffix, "bg." + suffix + "@example.com", role, "Big HR", "HR");
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, updated_at)
            SELECT gen_random_uuid(), ?, 'W' || n, 'Worker ' || n, 'w' || n || '.' || ? || '@example.com', 'Worker', ?, DATE '2025-01-01', 'ACTIVE', 'Asia/Kolkata', 'IN', false, now()
            FROM generate_series(1, ?) AS n""", big.business(), suffix, worker, EmployeeNameDirectory.MAX_PEOPLE_PER_BUSINESS);
        Cookie bigHr = signIn("bg." + suffix + "@example.com");
        mockMvc.perform(get("/api/v1/people?search=worker").cookie(bigHr)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("There are too many people to search or sort by name at once. Narrow your search with a filter and try again."));
        // lists that need no name search or name sort still work
        mockMvc.perform(get("/api/v1/people?sort=code").cookie(bigHr)).andExpect(status().isOk());
    }

    // ───────────────────────── audit and logs ─────────────────────────

    @Test
    void theAuditLogHoldsNoPlainNamesOrDesignationsAndStaysReadable(CapturedOutput output) throws Exception {
        String email = "audit." + suffix + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Priyanka", "Sharma-Verma", "Chief Architect", email))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).at("/data/id").asText());
        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr).content(personBody("Priyanka", "Sharma-Verma", "Principal Architect", email))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people/export?search=priyanka").cookie(hr)).andExpect(status().isOk());

        List<Map<String, Object>> entries = jdbc.queryForList("SELECT action, coalesce(before::text, '') AS before, coalesce(after::text, '') AS after FROM audit_log WHERE entity_id = ? ORDER BY at", id.toString());
        assertThat(entries).extracting(entry -> entry.get("action")).contains("employee.create", "employee.edit");
        for (Map<String, Object> entry : entries) {
            assertThat((String) entry.get("before") + entry.get("after")).as(entry.get("action") + " audit entry")
                    .doesNotContain("Priyanka", "Sharma", "Architect", "priyanka", "architect");
        }
        Map<String, Object> edit = entries.stream().filter(entry -> "employee.edit".equals(entry.get("action"))).findFirst().orElseThrow();
        assertThat((String) edit.get("after")).contains("\"fullName\": \"P*** S***\"").contains("\"designation\": \"P*** A***\"").contains("\"code\"");
        assertThat((String) edit.get("before")).contains("\"designation\": \"C*** A***\"");
        // the export and every other audit row of this run carry no names either
        List<String> allRows = jdbc.queryForList("SELECT coalesce(before::text, '') || coalesce(after::text, '') || path FROM audit_log WHERE business_id = ?", String.class, data.business());
        assertThat(String.join("\n", allRows)).doesNotContain("Priyanka", "Chief Architect", "Principal Architect");
        // the audit page still shows who did it (the actor's name is decrypted on the way out)
        assertThat(body("/api/v1/audit?search=hema", owner)).contains("Hema Rao");
        assertThat(body("/api/v1/audit?sort=actor", owner)).contains("Hema Rao");
        assertThat(output.getAll()).doesNotContain("Priyanka").doesNotContain("Sharma-Verma").doesNotContain("Chief Architect").doesNotContain("Principal Architect");
    }

    @Test
    void noNameIsEverWrittenToTheLog(CapturedOutput output) throws Exception {
        data.employee("LG" + suffix, "lg." + suffix + "@example.com", employeeRoleId, "Logtest Subject", "Logtest Role");
        for (String path : List.of("/api/v1/people?search=logtest", "/api/v1/people?sort=manager", "/api/v1/directory?search=logtest&sort=title", "/api/v1/people/export", "/api/v1/onboardings?search=logtest",
                "/api/v1/letters/employees", "/api/v1/people?search=%00")) {
            mockMvc.perform(get(path).cookie(hr));
        }
        assertThat(output.getAll()).doesNotContain("Logtest Subject").doesNotContain("Logtest Role").doesNotContain("Hema Rao").doesNotContain("Oscar Owner");
    }
}
