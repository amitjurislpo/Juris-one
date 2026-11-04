package com.jurislpo.jurisone.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one place a stored name becomes text: encrypted copy first, plaintext fallback (counted), nothing else. */
class EmployeeNameReaderTest {

    static final PiiCrypto CRYPTO = new PiiCrypto("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=");

    @Test
    void withTheSwitchOffTheEncryptedCopyIsIgnored() {
        EmployeeNameReader reader = new EmployeeNameReader(CRYPTO, false);
        assertThat(reader.read("Plain Name", CRYPTO.encrypt("Other Name"))).isEqualTo("Plain Name");
        assertThat(reader.read("Plain Name", null)).isEqualTo("Plain Name");
        assertThat(reader.fallbackCount()).isZero();
    }

    @Test
    void withTheSwitchOnTheEncryptedCopyWinsAndAMissingOneFallsBackAndIsCounted() {
        EmployeeNameReader reader = new EmployeeNameReader(CRYPTO, true);
        assertThat(reader.read("Old Plain", CRYPTO.encrypt("New Secret"))).isEqualTo("New Secret");
        assertThat(reader.fallbackCount()).isZero();
        assertThat(reader.read("Legacy Plain", null)).isEqualTo("Legacy Plain");
        assertThat(reader.read("Legacy Plain", "")).isEqualTo("Legacy Plain");
        assertThat(reader.fallbackCount()).isEqualTo(2);
        assertThat(reader.read((String) null, (String) null)).isNull(); // no manager: nothing to fall back on, nothing counted
        assertThat(reader.fallbackCount()).isEqualTo(2);
    }

    @Test
    void aCopyThatDoesNotDecryptFallsBackAndIsCountedSeparately() {
        EmployeeNameReader reader = new EmployeeNameReader(CRYPTO, true);
        assertThat(reader.read("Kept Plain", "v1.AAAA.BBBB.CCCC")).isEqualTo("Kept Plain");
        assertThat(reader.read("Kept Plain", "not encrypted at all")).isEqualTo("Kept Plain");
        assertThat(reader.undecryptableCount()).isEqualTo(2);
        assertThat(reader.fallbackCount()).isZero();
    }

    @Test
    void rowMapsAreResolvedInPlaceAndTheEncryptedTwinIsRemoved() {
        EmployeeNameReader reader = new EmployeeNameReader(CRYPTO, true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "plain");
        row.put("name_enc", CRYPTO.encrypt("Decrypted"));
        row.put("code", "E1");
        assertThat(reader.resolve(row, "name")).containsExactly(Map.entry("name", "Decrypted"), Map.entry("code", "E1"));
        Map<String, Object> noColumn = new LinkedHashMap<>(Map.of("code", "E2"));
        assertThat(reader.resolve(noColumn, "name")).containsOnlyKeys("code");
        EmployeeNameReader off = new EmployeeNameReader(CRYPTO, false);
        Map<String, Object> offRow = new LinkedHashMap<>(Map.of("name", "plain", "name_enc", CRYPTO.encrypt("Decrypted")));
        assertThat(off.resolve(offRow, "name")).containsExactly(Map.entry("name", "plain"));
    }

    @Test
    void listsOfNamesAreResolvedAndOrdered() {
        EmployeeNameReader reader = new EmployeeNameReader(CRYPTO, true);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("owners", new ArrayList<>(List.of("p1", "p2", "p3")));
        row.put("owners_enc", new ArrayList<>(java.util.Arrays.asList(CRYPTO.encrypt("Zed"), null, CRYPTO.encrypt("Amit"))));
        reader.resolveNameLists(List.of(row), "owners");
        assertThat(row).containsEntry("owners", List.of("Amit", "Zed", "p2")).doesNotContainKey("owners_enc");
        assertThat(reader.fallbackCount()).isEqualTo(1);
    }
}
