package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.repository.EncryptExistingColumnsRepository;
import com.jurislpo.jurisone.crypto.PayslipSnapshotCrypto;
import com.jurislpo.jurisone.crypto.SalaryAmountsCrypto;
import com.jurislpo.jurisone.service.EncryptExistingColumnsService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Father's name, emergency contact and bank name/IFSC are stored encrypted; old plaintext rows stay readable and the backfill encrypts them once. */
class EncryptedEmployeeColumnsTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PiiCrypto piiCrypto;
    @Autowired EncryptExistingColumnsRepository repository;
    @Autowired EncryptExistingColumnsService backfill;
    @Autowired SalaryAmountsCrypto salaryAmounts;
    @Autowired PayslipSnapshotCrypto snapshots;

    String suffix;
    Cookie employeeA, employeeB;

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
    void twoBusinesses() throws Exception {
        suffix = TestData.letters(8);
        for (String business : List.of("ea-" + suffix, "eb-" + suffix)) {
            TestData testData = new TestData(jdbc).inBusiness(business);
            UUID role = testData.role("employee", "self", "dir");
            String tag = business.substring(0, 2).toUpperCase();
            testData.employee(tag + "-" + suffix, tag.toLowerCase() + "." + suffix + "@example.com", role);
        }
        employeeA = signIn("ea." + suffix + "@example.com");
        employeeB = signIn("eb." + suffix + "@example.com");
    }

    String column(String column, String code) {
        return jdbc.queryForObject("SELECT " + column + " FROM employees WHERE code = ?", String.class, code);
    }

    String profile(String father, String emergencyName, String phone) {
        return """
            {"fatherName":"%s","dateOfBirth":"1995-05-20","currentAddress":"","permanentAddress":"","emergencyName":"%s","emergencyRelation":"Mother",
             "emergencyPhoneCountry":"+91","emergencyPhone":"%s"}""".formatted(father, emergencyName, phone);
    }

    @Test
    void selfServiceDetailsAreStoredEncryptedAndReadBackInTheClear() throws Exception {
        mockMvc.perform(write(put("/api/v1/profile")).cookie(employeeA).content(profile("Ravi Kumar", "Asha Devi", "98765 43210"))).andExpect(status().isOk());
        String code = "EA-" + suffix;
        for (String column : List.of("father_name", "emergency_name", "emergency_relation", "emergency_phone")) {
            assertThat(column(column, code)).as(column).startsWith("v1.").doesNotContain("Ravi", "Asha", "Mother", "98765");
        }
        mockMvc.perform(get("/api/v1/profile").cookie(employeeA)).andExpect(jsonPath("$.data.fatherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.data.emergencyName").value("Asha Devi")).andExpect(jsonPath("$.data.emergencyRelation").value("Mother"))
                .andExpect(jsonPath("$.data.emergencyPhone").value("9876543210"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE after::text LIKE '%Ravi Kumar%' OR after::text LIKE '%Asha Devi%'", Integer.class)).isZero();
    }

    @Test
    void oldPlaintextRowsStayReadableThenTheBackfillEncryptsThemOnceOnly() throws Exception {
        String codeA = "EA-" + suffix, codeB = "EB-" + suffix;
        for (String code : List.of(codeA, codeB)) {
            jdbc.update("UPDATE employees SET father_name = ?, emergency_name = 'Old Contact', emergency_relation = 'Aunt', emergency_phone = '+91 9000000001', bank_name = 'HDFC Bank', bank_ifsc = 'HDFC0001234' WHERE code = ?",
                    "Old Father " + code, code);
        }
        // before the backfill the screens still work
        mockMvc.perform(get("/api/v1/profile").cookie(employeeA)).andExpect(jsonPath("$.data.fatherName").value("Old Father " + codeA))
                .andExpect(jsonPath("$.data.emergencyName").value("Old Contact")).andExpect(jsonPath("$.data.emergencyPhone").value("9000000001"));

        // a dry run counts but writes nothing
        Map<String, Integer> dryRun = new EncryptExistingColumnsService(repository, piiCrypto, salaryAmounts, snapshots, true).run();
        assertThat(dryRun.get("employees.father_name")).isGreaterThanOrEqualTo(2);
        assertThat(column("father_name", codeA)).isEqualTo("Old Father " + codeA);

        Map<String, Integer> first = backfill.run(); // both businesses, although no business is signed in
        assertThat(first).containsKeys("employees.father_name", "employees.bank_ifsc").extractingByKey("employees.father_name").isNotNull();
        for (String key : List.of("employees.father_name", "employees.emergency_name", "employees.emergency_relation", "employees.emergency_phone", "employees.bank_name", "employees.bank_ifsc")) assertThat(first.get(key)).as(key).isGreaterThanOrEqualTo(2);
        for (String code : List.of(codeA, codeB)) {
            for (String column : List.of("father_name", "emergency_name", "emergency_relation", "emergency_phone", "bank_name", "bank_ifsc")) {
                assertThat(PiiCrypto.isEncrypted(column(column, code))).as(column + " " + code).isTrue();
            }
            assertThat(piiCrypto.decrypt(column("father_name", code))).isEqualTo("Old Father " + code);
            assertThat(piiCrypto.decrypt(column("bank_ifsc", code))).isEqualTo("HDFC0001234");
        }
        mockMvc.perform(get("/api/v1/profile").cookie(employeeB)).andExpect(jsonPath("$.data.fatherName").value("Old Father " + codeB))
                .andExpect(jsonPath("$.data.emergencyName").value("Old Contact"));

        // running again changes nothing: the stored values are not touched, not wrapped a second time
        String before = column("father_name", codeA);
        Map<String, Integer> second = backfill.run();
        assertThat(second.values()).allMatch(count -> count == 0);
        assertThat(column("father_name", codeA)).isEqualTo(before);
    }

    @Test
    void someoneElsesRowIsNeverShownAndHostileTextIsJustData() throws Exception {
        String hostile = "x'); DROP TABLE employees;-- <script>alert(1)</script>";
        mockMvc.perform(write(put("/api/v1/profile")).cookie(employeeA).content(profile(hostile, "Contact", "9876543210"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/profile").cookie(employeeA)).andExpect(jsonPath("$.data.fatherName").value(hostile));
        assertThat(column("father_name", "EA-" + suffix)).doesNotContain("DROP", "script");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Integer.class)).isPositive();
        // the other business's employee sees nothing of it
        String other = mockMvc.perform(get("/api/v1/profile").cookie(employeeB)).andReturn().getResponse().getContentAsString();
        assertThat(other).doesNotContain("DROP").doesNotContain("Contact");
        // oversized input is refused before anything is written
        mockMvc.perform(write(put("/api/v1/profile")).cookie(employeeB).content(profile("A".repeat(500), "Contact", "9876543210"))).andExpect(status().isBadRequest());
        assertThat(column("father_name", "EB-" + suffix)).isNull();
    }

    @Test
    void theColumnAllowListRefusesAnythingElse() {
        assertThatThrownBy(() -> repository.findValues(new EncryptExistingColumnsRepository.TextColumn("employees", "id", "email; DROP TABLE employees"))).hasMessageContaining("Not an encrypted column");
    }
}
