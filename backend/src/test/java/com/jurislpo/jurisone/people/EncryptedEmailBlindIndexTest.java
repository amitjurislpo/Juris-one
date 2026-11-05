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
import com.jurislpo.jurisone.crypto.EmployeeEmailEncryption;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.repository.BusinessSetupRepository;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import com.jurislpo.jurisone.service.EmployeeEmailBackfill;
import com.jurislpo.jurisone.service.EncryptExistingColumnsService;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Employee email, encryption stage (default switches except dual-write, which the tests turn on): every writer stores the encrypted copy and the blind index,
 * the index is keyed and reveals nothing, the backfill fills old rows once and can be repeated, and sign-in is unchanged.
 */
@ExtendWith(OutputCaptureExtension.class)
class EncryptedEmailBlindIndexTest extends IntegrationTest {

    static final String OTHER_KEY = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PiiCrypto piiCrypto;
    @Autowired EmployeeEmailEncryption emailEncryption;
    @Autowired EmployeeEmailBackfill emailBackfill;
    @Autowired EncryptExistingColumnsRepository repository;
    @Autowired EncryptExistingColumnsService backfill;
    @Autowired BusinessSetupRepository businessSetupRepository;

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
        data = new TestData(jdbc).inBusiness("mail-" + suffix);
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

    String personBody(String firstName, String email) {
        return """
            {"firstName":"%s","middleName":"","lastName":"Joshi","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"Associate","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}"""
                .formatted(firstName, email, departmentId, hrId, employeeRoleId);
    }

    Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT email, email_enc, email_blind_index FROM employees WHERE id = ?", id);
    }

    void assertEmailCopyMatches(Map<String, Object> row) {
        String email = (String) row.get("email"), stored = (String) row.get("email_enc"), blindIndex = (String) row.get("email_blind_index");
        assertThat(stored).isNotNull();
        assertThat(PiiCrypto.isEncrypted(stored)).isTrue();
        assertThat(stored).doesNotContain(email);
        assertThat(piiCrypto.decrypt(stored)).isEqualTo(email);
        assertThat(blindIndex).isEqualTo(emailEncryption.blindIndex(email));
    }

    // ───────────────────────── the blind index itself ─────────────────────────

    @Test
    void theBlindIndexIsKeyedStableAndNormalisedTheWayThePlaintextIs() throws Exception {
        String index = emailEncryption.blindIndex("asha.joshi@example.com");
        assertThat(index).matches("[0-9a-f]{64}");
        assertThat(emailEncryption.blindIndex("asha.joshi@example.com")).isEqualTo(index); // deterministic: that is what makes it a lookup key
        assertThat(emailEncryption.blindIndex("  ASHA.Joshi@Example.COM \t")).isEqualTo(index); // case and surrounding whitespace do not matter
        assertThat(emailEncryption.blindIndex("asha.joshi@example.com.")).isNotEqualTo(index);
        assertThat(emailEncryption.blindIndex("asha.joshi@example.co")).isNotEqualTo(index);
        assertThat(emailEncryption.blindIndex("asha joshi@example.com")).isNotEqualTo(index); // inner whitespace is part of the address
        // look-alikes are different addresses, exactly as in the plaintext column: full-width letters, Cyrillic 'а', a dotless i, a zero-width space
        for (String lookAlike : List.of("ａsha.joshi@example.com", "аsha.joshi@example.com", "asha.joshı@example.com", "asha.joshi@example​.com", "asha.joshi＠example.com")) {
            assertThat(emailEncryption.blindIndex(lookAlike)).as(lookAlike).isNotEqualTo(index);
        }
        assertThat(emailEncryption.blindIndex("   ")).isNull();
        assertThat(emailEncryption.blindIndex(null)).isNull();

        // keyed: not the plain hash of the address, and another key gives another value
        String plainHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("asha.joshi@example.com".getBytes(StandardCharsets.UTF_8)));
        assertThat(index).isNotEqualTo(plainHash);
        EmployeeEmailEncryption otherKey = new EmployeeEmailEncryption(piiCrypto, OTHER_KEY, true, false, true, false);
        assertThat(otherKey.blindIndex("asha.joshi@example.com")).isNotEqualTo(index);
        // and it is not made with the PII key
        EmployeeEmailEncryption piiKeyed = new EmployeeEmailEncryption(piiCrypto, "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=", true, false, true, false);
        assertThat(piiKeyed.blindIndex("asha.joshi@example.com")).isNotEqualTo(index);
    }

    @Test
    void theSwitchesAreCheckedAtStartUpAndDefaultToOff() {
        assertThatThrownBy(() -> new EmployeeEmailEncryption(piiCrypto, "", true, false, true, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new EmployeeEmailEncryption(piiCrypto, "not base64!", false, true, true, false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EmployeeEmailEncryption(piiCrypto, OTHER_KEY, false, true, false, false)).hasMessageContaining("dual-write"); // no fallback needs dual-write
        EmployeeEmailEncryption off = new EmployeeEmailEncryption(piiCrypto, "", false, false, true, false); // everything off needs no key
        assertThat(off.isKeyConfigured()).isFalse();
        assertThat(off.encryptForWrite("a@example.com")).isNull();
        assertThat(off.blindIndexForWrite("a@example.com")).isNull();
        assertThat(off.isLookupByBlindIndex()).isFalse();
        assertThat(off.isPlaintextFallback()).isTrue();
        assertThat(off.read("plain@example.com", piiCrypto.encrypt("enc@example.com"))).isEqualTo("plain@example.com"); // reads stay on the plaintext
        EmployeeEmailEncryption reading = new EmployeeEmailEncryption(piiCrypto, "", false, false, true, true);
        assertThat(reading.read("plain@example.com", piiCrypto.encrypt("enc@example.com"))).isEqualTo("enc@example.com");
        assertThat(reading.read("plain@example.com", null)).isEqualTo("plain@example.com");
        assertThat(reading.read("plain@example.com", "v1.AAAA.BBBB.CCCC")).isEqualTo("plain@example.com"); // an unreadable copy falls back to the plaintext
    }

    // ───────────────────────── every writer ─────────────────────────

    @Test
    void addAndEditEmployeeStoreTheCopyAndTheIndexAndNeverExposeThem() throws Exception {
        String email = "meera." + suffix + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Meera", "  " + email.toUpperCase() + " "))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(created).at("/data/id").asText());
        Map<String, Object> afterAdd = row(id);
        assertThat(afterAdd).containsEntry("email", email); // plaintext is still written, lower-case
        assertEmailCopyMatches(afterAdd);
        assertThat(created).doesNotContain((String) afterAdd.get("email_blind_index")).doesNotContain("v1.");

        String changed = "meera.new." + suffix + "@example.com";
        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr).content(personBody("Meera", changed))).andExpect(status().isOk());
        Map<String, Object> afterEdit = row(id);
        assertThat(afterEdit).containsEntry("email", changed);
        assertEmailCopyMatches(afterEdit);
        assertThat(afterEdit.get("email_blind_index")).isNotEqualTo(afterAdd.get("email_blind_index"));
    }

    @Test
    void theOwnerOfANewBusinessGetsTheCopyAndTheIndex() {
        UUID role = data.role("owner", "self", "dir");
        UUID id = UUID.randomUUID();
        String email = "owner." + suffix + "@names.test";
        TenantContext.call(data.business(), () -> {
            businessSetupRepository.insertOwnerEmployee(id, "OW" + suffix.toUpperCase(), "Asha Owner", email, "Owner", role, LocalDate.of(2025, 1, 1), "Asia/Kolkata", "IN", null);
            return null;
        });
        assertEmailCopyMatches(row(id));
    }

    @Test
    void theIndexIsUniquePlatformWideAndCheckedForItsFormat() {
        UUID role = employeeRoleId;
        UUID first = data.employee("U1" + suffix, "u1." + suffix + "@example.com", role);
        UUID second = data.employee("U2" + suffix, "u2." + suffix + "@example.com", role);
        String index = emailEncryption.blindIndex("u1." + suffix + "@example.com");
        jdbc.update("UPDATE employees SET email_blind_index = ? WHERE id = ?", index, first);
        assertThatThrownBy(() -> jdbc.update("UPDATE employees SET email_blind_index = ? WHERE id = ?", index, second)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE employees SET email_blind_index = 'not-a-hash' WHERE id = ?", second)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE employees SET email_blind_index = NULL WHERE id = ?", second); // rows without an index are many
        assertThat(jdbc.queryForObject("SELECT jurisone_email_blind_index_in_use(?, NULL)", Boolean.class, index)).isTrue();
        assertThat(jdbc.queryForObject("SELECT jurisone_email_blind_index_in_use(?, ?)", Boolean.class, index, first)).isFalse();
    }

    // ───────────────────────── backfill ─────────────────────────

    @Test
    void theBackfillFillsOldRowsOnceIsRepeatableAndRepairsMismatches(CapturedOutput output) {
        String email = "old." + suffix + "@example.com";
        UUID oldRow = data.employee("OL" + suffix, email, employeeRoleId); // written by a helper that knows nothing of the copy
        assertThat(row(oldRow).get("email_enc")).isNull();

        // a dry run counts but writes nothing
        Map<String, Integer> dryRunEmail = new EmployeeEmailBackfill(repository, piiCrypto, emailEncryption, true).run();
        assertThat(dryRunEmail.get("employees.email_enc")).isGreaterThanOrEqualTo(1);
        assertThat(dryRunEmail.get("employees.email_blind_index")).isGreaterThanOrEqualTo(1);
        assertThat(row(oldRow).get("email_enc")).isNull();
        assertThat(row(oldRow).get("email_blind_index")).isNull();

        Map<String, Integer> first = backfill.run(); // the real command, wired by Spring
        assertThat(first.get("employees.email_enc")).isGreaterThanOrEqualTo(1);
        assertThat(first.get("employees.email_blind_index_still_missing")).isZero();
        assertThat(first.get("employees.email_blind_index_conflicts")).isZero();
        Map<String, Object> filled = row(oldRow);
        assertEmailCopyMatches(filled);
        assertThat(filled).containsEntry("email", email); // plaintext untouched

        // running again changes nothing and does not re-encrypt a good copy
        String before = (String) filled.get("email_enc");
        assertThat(backfill.run().values()).allMatch(count -> count == 0);
        assertThat(row(oldRow).get("email_enc")).isEqualTo(before);

        // a missing index, a wrong index and a corrupt copy are each repaired; a stale copy (plain edited while the switch was off) too
        jdbc.update("UPDATE employees SET email_blind_index = NULL WHERE id = ?", oldRow);
        assertThat(backfill.run().get("employees.email_blind_index")).isEqualTo(1);
        jdbc.update("UPDATE employees SET email_blind_index = ?, email_enc = 'v1.AAAA.BBBB.CCCC' WHERE id = ?", "0".repeat(64), oldRow);
        Map<String, Integer> repair = backfill.run();
        assertThat(repair.get("employees.email_enc")).isEqualTo(1);
        assertThat(repair.get("employees.email_blind_index")).isEqualTo(1);
        assertEmailCopyMatches(row(oldRow));
        String renamed = "renamed." + suffix + "@example.com";
        jdbc.update("UPDATE employees SET email = ? WHERE id = ?", renamed, oldRow);
        assertThat(backfill.run().get("employees.email_enc")).isEqualTo(1);
        assertEmailCopyMatches(row(oldRow));
        assertThat(backfill.run().values()).allMatch(count -> count == 0);

        // no address and no index ever reaches the log
        assertThat(output.getAll()).doesNotContain(email, renamed, (String) row(oldRow).get("email_blind_index"));
    }

    @Test
    void twoOldRowsThatDifferOnlyInLetterCaseAreReportedNotCrashed() {
        String lower = "case." + suffix + "@example.com";
        UUID first = data.employee("C1" + suffix, lower, employeeRoleId);
        UUID second = data.employee("C2" + suffix, lower.replace("case", "CASE"), employeeRoleId); // possible in old data: the plaintext index is case-sensitive
        try {
            Map<String, Integer> counts = backfill.run();
            assertThat(counts.get("employees.email_blind_index_conflicts")).isEqualTo(1);
            assertThat(counts.get("employees.email_blind_index_still_missing")).isEqualTo(1);
            List<Object> withIndex = jdbc.queryForList("SELECT id FROM employees WHERE id IN (?, ?) AND email_blind_index IS NOT NULL", Object.class, first, second);
            assertThat(withIndex).hasSize(1); // the first keeps it; the other is still found through the plaintext fallback
            assertThat(backfill.run().get("employees.email_blind_index_conflicts")).isEqualTo(1); // still reported, nothing crashes
        } finally {
            jdbc.update("DELETE FROM users WHERE employee_id IN (?, ?)", first, second);
            jdbc.update("DELETE FROM employees WHERE id IN (?, ?)", first, second);
        }
    }

    // ───────────────────────── hostile input ─────────────────────────

    @Test
    void hostileEmailsAreRefusedAndNothingIsWritten() throws Exception {
        long before = jdbc.queryForObject("SELECT count(*) FROM employees", Long.class);
        for (String email : List.of("x'); DROP TABLE employees;--@example.com", "<script>alert(1)</script>@example.com", "a".repeat(300) + "@example.com", "not-an-email", "a@b@c.com", "a b@example.com")) {
            mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Hostile", email.replace("\"", "")))).andExpect(status().is4xxClientError());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE email_blind_index IS NOT NULL AND email_enc IS NULL", Long.class)).isZero();
    }
}
