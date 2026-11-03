package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.repository.PayrollOperationsRepository;
import com.jurislpo.jurisone.repository.PayrollRepository;
import com.jurislpo.jurisone.repository.PeopleRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Date of birth, salary structures, payslip snapshots and the onboarding statutory names: stored encrypted, old plain rows readable, backfill once only. */
class EncryptedPayrollAndPersonalDataTest extends IntegrationTest {

    static final String SNAPSHOT = "{\"v\": 1, \"net\": 5123456, \"employee\": {\"name\": \"Secret Person\"}}";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PiiCrypto piiCrypto;
    @Autowired EncryptExistingColumnsService backfill;
    @Autowired PayrollOperationsRepository payrollOperations;
    @Autowired PayrollRepository payroll;
    @Autowired PeopleRepository people;

    String suffix;
    UUID businessA, businessB, employeeIdA, employeeIdB;
    Cookie cookieA;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void twoBusinesses() throws Exception {
        suffix = TestData.letters(8);
        TestData dataA = new TestData(jdbc).inBusiness("pa-" + suffix), dataB = new TestData(jdbc).inBusiness("pb-" + suffix);
        businessA = dataA.business();
        businessB = dataB.business();
        employeeIdA = dataA.employee("PA-" + suffix, "pa." + suffix + "@example.com", dataA.role("employee", "self", "dir"));
        employeeIdB = dataB.employee("PB-" + suffix, "pb." + suffix + "@example.com", dataB.role("employee", "self", "dir"));
        String set = SignIn.session(mockMvc, "pa." + suffix + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        cookieA = new Cookie("jo_at", matcher.group(1));
    }

    String string(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Test
    void dateOfBirthIsStoredEncryptedReadBackAndOldPlainDatesAreMovedOnce() throws Exception {
        String body = """
            {"fatherName":"Ravi","dateOfBirth":"1995-05-20","currentAddress":"","permanentAddress":"","emergencyName":"","emergencyRelation":"","emergencyPhoneCountry":"+91","emergencyPhone":""}""";
        mockMvc.perform(write(put("/api/v1/profile")).cookie(cookieA).content(body)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT date_of_birth FROM employees WHERE id = ?", Object.class, employeeIdA)).isNull();
        assertThat(string("SELECT date_of_birth_enc FROM employees WHERE id = ?", employeeIdA)).startsWith("v1.").doesNotContain("1995");
        mockMvc.perform(get("/api/v1/profile").cookie(cookieA)).andExpect(jsonPath("$.data.dateOfBirth").value("1995-05-20"));

        // an old row with a plain date still reads, then the backfill moves it
        jdbc.update("UPDATE employees SET date_of_birth = '1990-01-02', date_of_birth_enc = NULL WHERE id = ?", employeeIdA);
        mockMvc.perform(get("/api/v1/profile").cookie(cookieA)).andExpect(jsonPath("$.data.dateOfBirth").value("1990-01-02"));
        assertThat(backfill.run().get("employees.date_of_birth")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT date_of_birth FROM employees WHERE id = ?", Object.class, employeeIdA)).isNull();
        mockMvc.perform(get("/api/v1/profile").cookie(cookieA)).andExpect(jsonPath("$.data.dateOfBirth").value("1990-01-02"));
        assertThat(backfill.run().get("employees.date_of_birth")).isZero();
    }

    @Test
    void salaryStructuresAreEncryptedOldRowsReadAndTheBackfillClearsThePlainAmounts() {
        // written through the application: encrypted, plain columns empty
        TenantContext.run(businessA, () -> payrollOperations.insertStructure(UUID.randomUUID(), employeeIdA, "2026-01-01", 4000000L, 2000000L, 500000L, 1500000L, "JOINING", null));
        assertThat(jdbc.queryForObject("SELECT basic FROM salary_structures WHERE employee_id = ?", Object.class, employeeIdA)).isNull();
        assertThat(string("SELECT amounts_enc FROM salary_structures WHERE employee_id = ?", employeeIdA)).startsWith("v1.");

        // an old plain row (business B) reads too
        jdbc.update("INSERT INTO salary_structures (id, employee_id, effective_from, basic, hra, lta, special, reason) VALUES (?, ?, ?, 3000000, 1000000, 200000, 800000, 'JOINING')",
                UUID.randomUUID(), employeeIdB, Date.valueOf(LocalDate.of(2026, 1, 1)));
        List<com.jurislpo.jurisone.service.PayrollOperationsService.SalaryRevision> before = TenantContext.call(businessB, () -> payrollOperations.revisions(employeeIdB));
        assertThat(before).singleElement().satisfies(revision -> assertThat(revision.basic()).isEqualTo(3000000L));
        assertThat(TenantContext.call(businessB, () -> people.findGross(employeeIdB, Date.valueOf(LocalDate.of(2026, 6, 1))))).containsExactly(5000000L);

        assertThat(backfill.run().get("salary_structures.amounts")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT basic FROM salary_structures WHERE employee_id = ?", Object.class, employeeIdB)).isNull();
        assertThat(TenantContext.call(businessB, () -> payrollOperations.revisions(employeeIdB))).isEqualTo(before);
        Map<String, Object> structure = TenantContext.call(businessB, () -> payroll.structureOn(employeeIdB, LocalDate.of(2026, 6, 1)));
        assertThat(((Number) structure.get("special")).longValue()).isEqualTo(800000L);
        assertThat(TenantContext.call(businessA, () -> people.findGross(employeeIdA, Date.valueOf(LocalDate.of(2026, 6, 1))))).containsExactly(8000000L);
        assertThat(backfill.run().get("salary_structures.amounts")).isZero();
        // a structure of another business is invisible without its scope
        assertThat(TenantContext.call(businessA, () -> payrollOperations.revisions(employeeIdB))).isEmpty();
    }

    @Test
    void payslipSnapshotsAreEncryptedOldRowsReadAndTheBackfillMovesThemOnce() {
        UUID runId = UUID.randomUUID();
        jdbc.update("INSERT INTO payroll_runs (id, business_id, month, status) VALUES (?, ?, ?, 'CLOSED')", runId, businessA, "2026-02");
        // an old plain snapshot
        jdbc.update("INSERT INTO payslips (run_id, employee_id, snapshot) VALUES (?, ?, ?::jsonb)", runId, employeeIdA, SNAPSHOT);
        List<Map<String, Object>> plainRead = TenantContext.call(businessA, () -> payrollOperations.closedPayslips(employeeIdA));
        assertThat(plainRead).singleElement().satisfies(row -> assertThat((String) row.get("snapshot")).contains("Secret Person"));

        assertThat(backfill.run().get("payslips.snapshot")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snapshot FROM payslips WHERE employee_id = ?", Object.class, employeeIdA)).isNull();
        assertThat(string("SELECT snapshot_enc FROM payslips WHERE employee_id = ?", employeeIdA)).startsWith("v1.").doesNotContain("Secret").doesNotContain("5123456");
        List<Map<String, Object>> encryptedRead = TenantContext.call(businessA, () -> payrollOperations.closedPayslips(employeeIdA));
        assertThat((String) encryptedRead.get(0).get("snapshot")).contains("Secret Person").contains("5123456");
        assertThat(backfill.run().get("payslips.snapshot")).isZero();

        // a new payslip is written encrypted straight away
        UUID secondRun = UUID.randomUUID();
        jdbc.update("INSERT INTO payroll_runs (id, business_id, month, status) VALUES (?, ?, ?, 'CLOSED')", secondRun, businessA, "2026-03");
        TenantContext.run(businessA, () -> payrollOperations.insertPayslip(secondRun, employeeIdA, SNAPSHOT, null));
        assertThat(jdbc.queryForObject("SELECT snapshot FROM payslips WHERE run_id = ?", Object.class, secondRun)).isNull();
        assertThat(string("SELECT snapshot_enc FROM payslips WHERE run_id = ?", secondRun)).startsWith("v1.");
        assertThat(TenantContext.call(businessA, () -> payrollOperations.closedPayslips(employeeIdA))).hasSize(2);
        // another business sees none of it
        assertThat(TenantContext.call(businessB, () -> payrollOperations.closedPayslips(employeeIdA))).isEmpty();
    }

    @Test
    void onboardingStatutoryNamesAndPassportAreEncryptedByTheBackfill() {
        UUID onboardingId = UUID.randomUUID();
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'IN_PROGRESS')", onboardingId, businessA, employeeIdA);
        jdbc.update("INSERT INTO onboarding_statutory (onboarding_id, business_id, name_per_pan, name_per_aadhaar, passport_number, name_per_passport) VALUES (?, ?, 'Pan Name', 'Aadhaar Name', 'P1234567', 'Passport Name')", onboardingId, businessA);
        jdbc.update("INSERT INTO onboarding_personal (onboarding_id, business_id, date_of_birth) VALUES (?, ?, '1991-03-04')", onboardingId, businessA);
        Map<String, Integer> first = backfill.run();
        for (String key : List.of("onboarding_statutory.name_per_pan", "onboarding_statutory.name_per_aadhaar", "onboarding_statutory.passport_number", "onboarding_statutory.name_per_passport", "onboarding_personal.date_of_birth")) {
            assertThat(first.get(key)).as(key).isGreaterThanOrEqualTo(1);
        }
        String dump = string("SELECT name_per_pan || name_per_aadhaar || passport_number || name_per_passport FROM onboarding_statutory WHERE onboarding_id = ?", onboardingId);
        assertThat(dump).doesNotContain("Pan Name", "Aadhaar Name", "P1234567", "Passport Name");
        assertThat(piiCrypto.decrypt(string("SELECT name_per_pan FROM onboarding_statutory WHERE onboarding_id = ?", onboardingId))).isEqualTo("Pan Name");
        assertThat(piiCrypto.decrypt(string("SELECT date_of_birth_enc FROM onboarding_personal WHERE onboarding_id = ?", onboardingId))).isEqualTo("1991-03-04");
        assertThat(backfill.run().values()).allMatch(count -> count == 0);
    }
}
