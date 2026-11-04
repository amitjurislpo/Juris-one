package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.EmployeeNameEncryption;
import com.jurislpo.jurisone.repository.BusinessSetupRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** With {@code jurisone.security.encrypt-names.dual-write=false} only the plaintext columns are written; the encrypted copies stay empty (never stale). */
@TestPropertySource(properties = "jurisone.security.encrypt-names.dual-write=false")
class EncryptedNamesDualWriteOffTest extends IntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired EmployeeNameEncryption nameEncryption;
    @Autowired BusinessSetupRepository businessSetupRepository;

    @Test
    void theSwitchIsOffAndWritersStoreOnlyPlaintext() {
        assertThat(nameEncryption.encrypt("Asha Joshi")).isNull();
        TestData data = new TestData(jdbc).inBusiness("names-off-" + TestData.letters(6));
        UUID role = data.role("owner", "self", "dir");
        UUID id = UUID.randomUUID();
        String code = "OF" + TestData.letters(5).toUpperCase();
        TenantContext.call(data.business(), () -> {
            businessSetupRepository.insertOwnerEmployee(id, code, "Plain Owner", code.toLowerCase() + "@names.test", "Owner", role, LocalDate.of(2025, 1, 1), "Asia/Kolkata", "IN", null);
            return null;
        });
        Map<String, Object> row = jdbc.queryForMap("SELECT full_name, designation, full_name_enc, first_name_enc, last_name_enc, designation_enc FROM employees WHERE id = ?", id);
        assertThat(row).containsEntry("full_name", "Plain Owner").containsEntry("designation", "Owner");
        assertThat(row.get("full_name_enc")).isNull();
        assertThat(row.get("designation_enc")).isNull();
        assertThat(row.get("first_name_enc")).isNull();
        assertThat(row.get("last_name_enc")).isNull();
    }
}
