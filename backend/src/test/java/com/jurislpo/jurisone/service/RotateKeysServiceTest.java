package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.crypto.FileCrypto;
import com.jurislpo.jurisone.crypto.Keyring;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The rotate-keys command against the real schema. The application's own key (the test key) plays the "new" key; "old" is a key that was in use before. */
class RotateKeysServiceTest extends IntegrationTest {

    static final String NEW_KEY = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="; // the key the Spring context is configured with
    static final String OLD_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Autowired JdbcTemplate jdbc;
    @Autowired JurisOneProperties properties;

    RotateKeysService service(boolean isApplying) {
        Keyring keyring = new Keyring(NEW_KEY, OLD_KEY);
        return new RotateKeysService(jdbc, new PiiCrypto(keyring), new EncryptStoredFilesService(Path.of("target/test-storage-rotation-none"), new FileCrypto(keyring), isApplying), isApplying);
    }

    @Test
    void theCatalogColumnsIncludeEveryEncryptedColumnOfThePeopleTables() {
        var columns = service(false).columns().stream().map(column -> column[0] + "." + column[1]).toList();
        assertThat(columns).contains("employees.pan_enc", "employees.aadhaar_enc", "employees.address_enc", "employees.date_of_birth_enc", "salary_structures.amounts_enc", "payslips.snapshot_enc",
                "employees.father_name", "onboarding_statutory.passport_number");
    }

    @Test
    void oldValuesMoveToTheNewestKeyOnlyWhenApplyingAndATamperedOneIsReportedNotChanged() {
        TestData data = new TestData(jdbc).inBusiness("rotate-" + TestData.letters(6));
        UUID employee = data.employee("RK" + TestData.letters(5).toUpperCase(), "rk." + TestData.letters(6) + "@example.com", data.role("employee", "self"));
        PiiCrypto oldCrypto = new PiiCrypto(OLD_KEY);
        String oldPan = oldCrypto.encrypt("ABCPE1234F"), oldAddress = oldCrypto.encrypt("12 Test Road, Delhi");
        jdbc.update("UPDATE employees SET pan_enc = ?, address_enc = ? WHERE id = ?", oldPan, oldAddress, employee);

        Map<String, Integer> dryRun = service(false).run();
        assertThat(dryRun.get("employees.pan_enc")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pan_enc FROM employees WHERE id = ?", String.class, employee)).isEqualTo(oldPan); // nothing written

        Map<String, Integer> applied = service(true).run();
        assertThat(applied.get("employees.pan_enc")).isGreaterThanOrEqualTo(1);
        String rotatedPan = jdbc.queryForObject("SELECT pan_enc FROM employees WHERE id = ?", String.class, employee);
        PiiCrypto onlyNew = new PiiCrypto(NEW_KEY); // the old key is no longer needed to read it
        assertThat(rotatedPan).isNotEqualTo(oldPan);
        assertThat(onlyNew.decrypt(rotatedPan)).isEqualTo("ABCPE1234F");
        assertThat(onlyNew.decrypt(jdbc.queryForObject("SELECT address_enc FROM employees WHERE id = ?", String.class, employee))).isEqualTo("12 Test Road, Delhi");

        assertThat(service(true).run().get("employees.pan_enc")).isZero(); // idempotent

        String[] parts = onlyNew.encrypt("tampered").split("\\.");
        String damaged = String.join(".", parts[0], parts[1], parts[2], (parts[3].startsWith("A") ? "B" : "A") + parts[3].substring(1));
        jdbc.update("UPDATE employees SET pan_enc = ? WHERE id = ?", damaged, employee);
        Map<String, Integer> withDamage = service(true).run();
        assertThat(withDamage).containsKey("failed:employees.pan_enc");
        assertThat(jdbc.queryForObject("SELECT pan_enc FROM employees WHERE id = ?", String.class, employee)).isEqualTo(damaged);
        assertThat(withDamage.get("employees.address_enc")).isZero(); // the other columns were still finished
        jdbc.update("UPDATE employees SET pan_enc = NULL WHERE id = ?", employee); // leave no damaged value for other tests
    }
}
