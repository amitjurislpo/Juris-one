package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.PayslipSnapshotCrypto;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.crypto.SalaryAmountsCrypto;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import com.jurislpo.jurisone.repository.SeedRepository;
import com.jurislpo.jurisone.service.BusinessSetupService;
import com.jurislpo.jurisone.service.EncryptExistingColumnsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
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
 * Stage 1 of encrypting employee names and designations (dual-write, plaintext still read): every writer also stores the encrypted copy,
 * the backfill fills the old rows once, and hostile text is refused or stored as plain data and never logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class EncryptedNamesStage1Test extends IntegrationTest {

    static final List<String> PLAIN_COLUMNS = List.of("full_name", "first_name", "last_name", "designation");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PiiCrypto piiCrypto;
    @Autowired EncryptExistingColumnsRepository repository;
    @Autowired EncryptExistingColumnsService backfill;
    @Autowired SalaryAmountsCrypto salaryAmounts;
    @Autowired PayslipSnapshotCrypto snapshots;
    @Autowired BusinessSetupService setup;
    @Autowired SeedRepository seedRepository;

    TestData data;
    String suffix;
    UUID hrId, departmentId, employeeRoleId;
    Cookie hr;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("names-" + suffix);
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        employeeRoleId = data.role("employee", "self", "dir");
        UUID hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Ops " + suffix, data.business());
        hrId = data.employee("HR" + suffix, "hr." + suffix + "@example.com", hrRoleId);
        String set = SignIn.session(mockMvc, "hr." + suffix + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        hr = new Cookie("jo_at", matcher.group(1));
    }

    String personBody(String firstName, String lastName, String designation, String email) {
        return """
            {"firstName":"%s","middleName":"","lastName":"%s","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"%s","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}"""
                .formatted(firstName, lastName, email, designation, departmentId, hrId, employeeRoleId);
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT full_name, first_name, last_name, designation, full_name_enc, first_name_enc, last_name_enc, designation_enc FROM employees WHERE id = ?", id);
    }

    void assertEncryptedCopyMatches(Map<String, Object> row) {
        for (String column : PLAIN_COLUMNS) {
            String stored = (String) row.get(column + "_enc");
            assertThat(stored).as(column + "_enc").isNotNull();
            assertThat(PiiCrypto.isEncrypted(stored)).as(column + "_enc format").isTrue();
            assertThat(stored).as(column + "_enc is not the plaintext").doesNotContain((String) row.get(column));
            assertThat(piiCrypto.decrypt(stored)).as(column + " round trip").isEqualTo(row.get(column));
        }
    }

    // ───────────────────────── crypto ─────────────────────────

    @Test
    void namesRoundTripAndEveryEncryptionIsDifferent() {
        for (String name : List.of("Asha Joshi", "Zoë Müller", "अमित कुमार", "O'Neil <b>&</b>", "x'); DROP TABLE employees;--")) {
            String first = piiCrypto.encrypt(name), second = piiCrypto.encrypt(name);
            assertThat(first).startsWith("v1.").isNotEqualTo(second).doesNotContain(name);
            assertThat(piiCrypto.decrypt(first)).isEqualTo(name);
        }
    }

    @Test
    void aTamperedOrForeignCiphertextIsRefusedCleanlyWithoutRevealingAnything() {
        String stored = piiCrypto.encrypt("Asha Joshi");
        String[] parts = stored.split("\\.");
        String flipped = (parts[3].startsWith("A") ? "B" : "A") + parts[3].substring(1); // the first character always carries 6 real bits (the last may be padding)
        assertThatThrownBy(() -> piiCrypto.decrypt(parts[0] + "." + parts[1] + "." + parts[2] + "." + flipped))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Asha");
        assertThatThrownBy(() -> piiCrypto.decrypt("Asha Joshi")).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("Asha");
        assertThatThrownBy(() -> piiCrypto.decrypt("v1.AAAA.BBBB.CCCC")).isInstanceOf(IllegalArgumentException.class);
    }

    // ───────────────────────── every writer ─────────────────────────

    @Test
    void addAndEditEmployeeWriteTheEncryptedCopyInTheSameStatement() throws Exception {
        String email = "meera." + suffix + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Meera", "Joshi", "Associate", email))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).at("/data/id").asText());
        Map<String, Object> afterAdd = row(id);
        assertThat(afterAdd).containsEntry("full_name", "Meera Joshi").containsEntry("designation", "Associate"); // plaintext is still written and read
        assertEncryptedCopyMatches(afterAdd);

        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr).content(personBody("Meeraa", "Joshi-Rao", "Senior Associate", email))).andExpect(status().isOk());
        Map<String, Object> afterEdit = row(id);
        assertThat(afterEdit).containsEntry("full_name", "Meeraa Joshi-Rao").containsEntry("first_name", "Meeraa").containsEntry("designation", "Senior Associate");
        assertEncryptedCopyMatches(afterEdit);
        assertThat(piiCrypto.decrypt((String) afterEdit.get("full_name_enc"))).isEqualTo("Meeraa Joshi-Rao");
    }

    @Test
    void theOwnerOfANewBusinessGetsTheEncryptedCopy() {
        String code = "own-" + suffix.toLowerCase();
        UUID business = setup.create(new BusinessSetupService.NewBusiness("Owner Test " + suffix, null, code, "OT", "Asia/Kolkata", null, null, null, null), null);
        UUID owner = TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Asha Owner", "owner." + suffix + "@names.test", "Managing Partner"), null));
        Map<String, Object> ownerRow = row(owner);
        assertThat(ownerRow).containsEntry("full_name", "Asha Owner").containsEntry("designation", "Managing Partner");
        assertThat(piiCrypto.decrypt((String) ownerRow.get("full_name_enc"))).isEqualTo("Asha Owner");
        assertThat(piiCrypto.decrypt((String) ownerRow.get("designation_enc"))).isEqualTo("Managing Partner");
        assertThat(ownerRow.get("first_name_enc")).isNull(); // this writer has no first/last name
    }

    @Test
    void theSeedWritersGetTheEncryptedCopy() {
        UUID roleId = employeeRoleId;
        UUID superAdmin = UUID.randomUUID();
        // the super admin row has no business, so it can only be inserted without a tenant scope; the code SA001 is unique, so remove any earlier one first
        UUID existing = jdbc.query("SELECT id FROM employees WHERE code = 'SA001' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (existing == null) {
            seedRepository.insertSuperAdminEmployee(superAdmin, "sa." + suffix + "@names.test", roleId, Date.valueOf(LocalDate.of(2025, 1, 1)));
            Map<String, Object> adminRow = row(superAdmin);
            assertThat(piiCrypto.decrypt((String) adminRow.get("full_name_enc"))).isEqualTo("JurisOne Administrator");
            assertThat(piiCrypto.decrypt((String) adminRow.get("designation_enc"))).isEqualTo("Super Administrator");
        }
        UUID demo = UUID.randomUUID();
        TenantContext.call(data.business(), () -> {
            seedRepository.insertEmployee(demo, "SD" + suffix, "Demo Person", "Demo Father", "demo." + suffix + "@names.test", "Demo Title", roleId, null, null,
                    Date.valueOf(LocalDate.of(2025, 1, 1)), null, "ACTIVE", "Asia/Kolkata", "IN", null, false, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
            return null;
        });
        Map<String, Object> demoRow = jdbc.queryForMap("SELECT full_name, designation, full_name_enc, designation_enc FROM employees WHERE id = ?", demo);
        assertThat(piiCrypto.decrypt((String) demoRow.get("full_name_enc"))).isEqualTo("Demo Person");
        assertThat(piiCrypto.decrypt((String) demoRow.get("designation_enc"))).isEqualTo("Demo Title");
    }

    // ───────────────────────── backfill ─────────────────────────

    @Test
    void theBackfillFillsOldRowsOnceVerifiesThemAndRepairsMismatches() {
        UUID oldRow = data.employee("OL" + suffix, "old." + suffix + "@example.com", employeeRoleId); // written by a helper that knows nothing of the copies
        jdbc.update("UPDATE employees SET first_name = 'Old', last_name = 'Timer' WHERE id = ?", oldRow);
        assertThat(row(oldRow).get("full_name_enc")).isNull();

        // a dry run counts but writes nothing
        Map<String, Integer> dryRun = new EncryptExistingColumnsService(repository, piiCrypto, salaryAmounts, snapshots, true).run();
        assertThat(dryRun.get("employees.full_name_enc")).isGreaterThanOrEqualTo(1);
        assertThat(row(oldRow).get("full_name_enc")).isNull();

        Map<String, Integer> first = backfill.run();
        for (String column : PLAIN_COLUMNS) assertThat(first.get("employees." + column + "_enc")).as(column).isGreaterThanOrEqualTo(1);
        Map<String, Object> filled = row(oldRow);
        assertEncryptedCopyMatches(filled);
        assertThat(filled).containsEntry("full_name", "Person OL" + suffix); // plaintext untouched

        // running again changes nothing, and does not re-encrypt a good copy
        String before = (String) filled.get("full_name_enc");
        assertThat(backfill.run().values()).allMatch(count -> count == 0);
        assertThat(row(oldRow).get("full_name_enc")).isEqualTo(before);

        // a stale copy (plain edited while the switch was off) and a corrupted copy are both rewritten
        jdbc.update("UPDATE employees SET full_name = 'Renamed Person', designation_enc = 'v1.AAAA.BBBB.CCCC' WHERE id = ?", oldRow);
        Map<String, Integer> repair = backfill.run();
        assertThat(repair.get("employees.full_name_enc")).isEqualTo(1);
        assertThat(repair.get("employees.designation_enc")).isEqualTo(1);
        assertEncryptedCopyMatches(row(oldRow));
        assertThat(backfill.run().values()).allMatch(count -> count == 0);
    }

    @Test
    void theColumnAllowListRefusesAnythingElse() {
        assertThatThrownBy(() -> repository.findCopies(new EncryptExistingColumnsRepository.CopiedColumn("employees", "id", "email; DROP TABLE employees", "full_name_enc")))
                .hasMessageContaining("Not an encrypted column");
        assertThatThrownBy(() -> repository.replaceCopyIfUnchanged(new EncryptExistingColumnsRepository.CopiedColumn("users", "id", "full_name", "full_name_enc"), UUID.randomUUID(), "a", null, "b"))
                .hasMessageContaining("Not an encrypted column");
    }

    // ───────────────────────── hostile input ─────────────────────────

    @Test
    void hostileNamesAndDesignationsAreRefusedOrStoredAsPlainDataAndNeverLogged(CapturedOutput output) throws Exception {
        String sql = "x'); DROP TABLE employees;--", script = "<script>alert(1)</script>";
        long before = jdbc.queryForObject("SELECT count(*) FROM employees", Long.class);

        // SQL and script text are just data: stored (plain and encrypted), no table dropped
        String email = "hostile." + suffix + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Sql", "Hostile", "Tester " + sql.replace("'", "’"), email))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).at("/data/id").asText());
        assertThat(piiCrypto.decrypt((String) row(id).get("designation_enc"))).isEqualTo(row(id).get("designation")).contains("DROP TABLE");
        String scriptEmail = "script." + suffix + "@example.com";
        UUID scriptId = UUID.fromString(json.readTree(mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Script", "Name", "Tester " + script, scriptEmail)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText());
        assertThat(piiCrypto.decrypt((String) row(scriptId).get("designation_enc"))).contains("<script>");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Long.class)).isEqualTo(before + 2);

        // oversized text is refused before anything is written
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("A".repeat(500), "Long", "Tester", "long1." + suffix + "@example.com"))).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Long", "Designation", "D".repeat(5000), "long2." + suffix + "@example.com"))).andExpect(status().isBadRequest());

        // a NUL character is refused (the database rejects it) and leaves neither column behind
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Nul\\u0000Name", "Nul", "Tester", "nul." + suffix + "@example.com"))).andExpect(status().is4xxClientError());
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Nul", "Name", "Tes\\u0000ter", "nul2." + suffix + "@example.com"))).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Long.class)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE email LIKE ?", Long.class, "nul%." + suffix + "@example.com")).isZero();

        // the values never reach the log, and the backfill logs counts only
        backfill.run();
        assertThat(output.getAll()).doesNotContain("DROP TABLE", "<script>", "Hostile", "Meera");
    }
}
