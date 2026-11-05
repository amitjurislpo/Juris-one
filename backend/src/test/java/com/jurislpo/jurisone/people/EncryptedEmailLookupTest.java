package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.EmployeeEmailEncryption;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Employee email with the lookup switches ON: {@code lookup-by-blind-index=true}, plaintext fallback on (the backfill is not finished), read-encrypted on.
 * Sign-in and "email already used" must answer exactly as they do through the plaintext column: for rows that have the index, for rows that do not
 * (mixed table), for odd spellings and for hostile text, within a business and across businesses.
 */
@TestPropertySource(properties = {"jurisone.security.encrypt-email.lookup-by-blind-index=true", "jurisone.security.encrypt-email.plaintext-fallback=true",
        "jurisone.security.encrypt-email.read-encrypted=true"})
class EncryptedEmailLookupTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PiiCrypto piiCrypto;
    @Autowired EmployeeEmailEncryption emailEncryption;

    TestData data, otherData;
    String suffix;
    UUID hrId, departmentId, employeeRoleId, otherRoleId;
    Cookie hr;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static Cookie cookieOf(org.springframework.test.web.servlet.ResultActions signedIn) {
        String set = signedIn.andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("look-" + suffix);
        otherData = new TestData(jdbc).inBusiness("other-" + suffix);
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        employeeRoleId = data.role("employee", "self", "dir");
        otherRoleId = otherData.role("employee", "self", "dir", "people.view");
        UUID hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Ops " + suffix, data.business());
        hrId = data.employee("HR" + suffix, "hr." + suffix + "@example.com", hrRoleId); // a plain row: no index yet (found through the fallback)
        hr = cookieOf(SignIn.session(mockMvc, "hr." + suffix + "@example.com").andExpect(status().isCreated()));
    }

    /** A row that has the encrypted copy and the index, as the dual-write or the backfill leave it. */
    UUID indexed(TestData business, UUID role, String code, String email) {
        UUID id = business.employee(code, email, role);
        jdbc.update("UPDATE employees SET email_enc = ?, email_blind_index = ? WHERE id = ?", piiCrypto.encrypt(email), emailEncryption.blindIndex(email), id);
        return id;
    }

    /** An indexed row whose plaintext column no longer holds the address: only the index can find it. */
    UUID indexedOnly(TestData business, UUID role, String code, String email) {
        UUID id = indexed(business, role, code, email);
        jdbc.update("UPDATE employees SET email = ? WHERE id = ?", "stale-" + code.toLowerCase() + "@gone.test", id);
        return id;
    }

    String personBody(String firstName, String email) {
        return """
            {"firstName":"%s","middleName":"","lastName":"Joshi","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","paymentType":"BANK_TRANSFER","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"Associate","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}"""
                .formatted(firstName, email, departmentId, hrId, employeeRoleId);
    }

    // ───────────────────────── sign-in ─────────────────────────

    @Test
    void signInFindsIndexedRowsAndPlainRowsAlike() throws Exception {
        indexed(data, employeeRoleId, "IX" + suffix, "indexed." + suffix + "@example.com");
        data.employee("PL" + suffix, "plain." + suffix + "@example.com", employeeRoleId);
        SignIn.session(mockMvc, "indexed." + suffix + "@example.com").andExpect(status().isCreated());
        SignIn.session(mockMvc, "plain." + suffix + "@example.com").andExpect(status().isCreated()); // mixed table: the fallback finds it
    }

    @Test
    void signInFindsARowByItsIndexAloneAndIgnoresCaseAndWhitespace() throws Exception {
        String email = "only." + suffix + "@example.com";
        indexedOnly(data, employeeRoleId, "ON" + suffix, email);
        SignIn.session(mockMvc, email).andExpect(status().isCreated());
        SignIn.session(mockMvc, "  ONLY." + suffix + "@Example.COM  ").andExpect(status().isCreated());
        // a stale plaintext value alone no longer signs anyone in by itself being the old address: it is still the plaintext column, so the fallback finds it too
        SignIn.session(mockMvc, "stale-on" + suffix + "@gone.test").andExpect(status().isCreated());
    }

    @Test
    void lookAlikesWrongSpellingsAndSqlTextDoNotSignInAnyone() throws Exception {
        String email = "target." + suffix + "@example.com";
        indexed(data, employeeRoleId, "TG" + suffix, email);
        List<String> attempts = List.of("ｔarget." + suffix + "@example.com", "tаrget." + suffix + "@example.com", "target." + suffix + "@example.com.", "target." + suffix + "@example.co",
                "target." + suffix + "@example.com' OR '1'='1", "x'@y.com' OR 1=1 --", "%@%", "target." + suffix + "@example.com; DROP TABLE employees");
        for (String attempt : attempts) {
            // asking for a code always answers the same; the sign-in with the fixed dev code must then be refused
            SignIn.session(mockMvc, attempt).andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE email = ?", Long.class, email)).isEqualTo(1);
    }

    // ───────────────────────── duplicates ─────────────────────────

    @Test
    void anEmailAlreadyUsedInTheSameBusinessIsRefusedEvenWhenOnlyTheIndexKnowsIt() throws Exception {
        String email = "taken." + suffix + "@example.com";
        indexedOnly(data, employeeRoleId, "TK" + suffix, email);
        String body = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Dup", email.toUpperCase()))).andExpect(status().is4xxClientError())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("already");
        long before = jdbc.queryForObject("SELECT count(*) FROM employees", Long.class);
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Dup", " " + email + " "))).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Long.class)).isEqualTo(before);
        // the same through the plaintext column (a row without an index)
        data.employee("PD" + suffix, "plaindup." + suffix + "@example.com", employeeRoleId);
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Dup", "plaindup." + suffix + "@example.com"))).andExpect(status().is4xxClientError());
        // a different address is fine and gets its own index
        String fresh = "fresh." + suffix + "@example.com";
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Fresh", fresh))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT email_blind_index FROM employees WHERE email = ?", String.class, fresh)).isEqualTo(emailEncryption.blindIndex(fresh));
    }

    @Test
    void anEmailUsedInAnotherBusinessIsRefusedWithoutNamingThatBusinessOrPerson() throws Exception {
        String email = "elsewhere." + suffix + "@example.com";
        indexedOnly(otherData, otherRoleId, "EL" + suffix.toUpperCase(), email);
        String body = mockMvc.perform(write(post("/api/v1/people")).cookie(hr).content(personBody("Dup", email))).andExpect(status().is4xxClientError()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("already in use").doesNotContain("EL" + suffix.toUpperCase()).doesNotContain(suffix + "@gone.test").doesNotContain("other-" + suffix);
    }

    // ───────────────────────── IDOR and reads ─────────────────────────

    @Test
    void anotherBusinessesEmployeeCannotBeReadOrFoundThroughTheirEmail() throws Exception {
        String email = "secret." + suffix + "@example.com";
        UUID foreign = indexed(otherData, otherRoleId, "SE" + suffix.toUpperCase(), email);
        String profile = mockMvc.perform(get("/api/v1/people/" + foreign).cookie(hr)).andReturn().getResponse().getContentAsString();
        assertThat(profile).doesNotContain(email);
        mockMvc.perform(get("/api/v1/people/" + foreign).cookie(hr)).andExpect(status().is4xxClientError());
        String directory = mockMvc.perform(get("/api/v1/directory").param("search", "secret." + suffix).cookie(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(directory).doesNotContain(email).doesNotContain("SE" + suffix.toUpperCase());
        // signing in with it works only for its own owner: the hr cookie of this business is unchanged and the foreign account has its own session
        Cookie foreignSession = cookieOf(SignIn.session(mockMvc, email).andExpect(status().isCreated()));
        mockMvc.perform(get("/api/v1/people/" + hrId).cookie(foreignSession)).andExpect(status().is4xxClientError());
    }

    @Test
    void readersPreferTheEncryptedCopyAndFallBackToThePlaintext() throws Exception {
        UUID id = data.employee("RD" + suffix.toUpperCase(), "plainshown." + suffix + "@example.com", employeeRoleId);
        jdbc.update("UPDATE employees SET email_enc = ? WHERE id = ?", piiCrypto.encrypt("encshown." + suffix + "@example.com"), id);
        UUID corrupt = data.employee("CR" + suffix.toUpperCase(), "corrupt." + suffix + "@example.com", employeeRoleId);
        jdbc.update("UPDATE employees SET email_enc = 'v1.AAAA.BBBB.CCCC' WHERE id = ?", corrupt);
        String directory = mockMvc.perform(get("/api/v1/directory").param("search", suffix.toUpperCase()).cookie(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(directory).contains("encshown." + suffix + "@example.com").doesNotContain("plainshown." + suffix).contains("corrupt." + suffix + "@example.com");
    }
}
