package com.jurislpo.jurisone.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The business short-name (web address) rules as plain functions. */
class BusinessSlugsTest {

    @ParameterizedTest
    @ValueSource(strings = {"acme", "juris-consultants", "a1b", "123", "a-b", "ab-cd-ef", "a--b", "abc--def", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "tenant-alpha", "jurisone-platform", "test-business"})
    void goodNamesPass(String slug) {
        assertThat(BusinessSlugs.problem(slug)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "Acme", "ACME", "-acme", "acme-", "-", "---", "ac me", "ac_me", "acme.com", "acme/x", "acнe",
            "аcme", "café", "ａｂｃ", "acme‮", "acme\n", "acme' OR '1'='1", "acme;DROP TABLE businesses", "a%2eb", "<script>", "xn--acme", "ab--cd", "xn--"})
    void badNamesAreRefused(String slug) {
        assertThat(BusinessSlugs.problem(slug)).isNotNull();
    }

    @Test
    void nullIsRefused() {
        assertThat(BusinessSlugs.problem(null)).isNotNull();
    }

    @Test
    void everyReservedNameIsRefused() {
        assertThat(BusinessSlugs.RESERVED).containsExactlyInAnyOrder("admin", "www", "api", "app", "mail", "smtp", "static", "assets", "cdn", "login", "auth", "sso", "status",
                "support", "help", "billing", "platform", "jurisone", "juristrack", "track", "one", "test", "staging", "dev", "uat", "sqe", "prd", "localhost", "ns1", "ns2");
        for (String reserved : BusinessSlugs.RESERVED) {
            assertThat(BusinessSlugs.isReserved(reserved)).isTrue();
            String problem = BusinessSlugs.problem(reserved);
            // names of one or two characters fail on length first; either way they are refused
            assertThat(problem).as(reserved).isNotNull();
        }
        assertThat(BusinessSlugs.problem("admin")).contains("reserved");
        assertThat(BusinessSlugs.isReserved("xn--anything")).isTrue();
        assertThat(BusinessSlugs.isReserved("admin-co")).isFalse();
    }

    @Test
    void derivedNamesAreSafe() {
        assertThat(BusinessSlugs.derive("Juris Consultants", taken -> false)).isEqualTo("juris-consultants");
        assertThat(BusinessSlugs.derive("  Acme & Sons (P) Ltd. ", taken -> false)).isEqualTo("acme-sons-p-ltd");
        assertThat(BusinessSlugs.derive("हिन्दी", taken -> false)).isEqualTo("business"); // nothing usable left
        assertThat(BusinessSlugs.derive("!!!", taken -> false)).isEqualTo("business");
        assertThat(BusinessSlugs.derive("Ab", taken -> false)).isEqualTo("ab-co"); // too short
        assertThat(BusinessSlugs.derive("X", taken -> false)).isEqualTo("x-co");
        assertThat(BusinessSlugs.derive("Admin", taken -> false)).isEqualTo("admin-co"); // reserved
        assertThat(BusinessSlugs.derive("JurisOne", taken -> false)).isEqualTo("jurisone-co");
        assertThat(BusinessSlugs.derive("Test", taken -> false)).isEqualTo("test-co");
        assertThat(BusinessSlugs.derive("xn--foo", taken -> false)).isEqualTo("xn-foo");
        String longName = BusinessSlugs.derive("a".repeat(30) + " " + "b".repeat(30), taken -> false);
        assertThat(longName).hasSize(40).endsWith("b"); // cut at 40 characters: 30 a, a dash, 9 b
        String cut = BusinessSlugs.derive("a".repeat(39) + " bcd", taken -> false);
        assertThat(cut).isEqualTo("a".repeat(39)); // the cut would end on a dash: it is trimmed
    }

    @Test
    void takenNamesGetANumber() {
        Set<String> taken = Set.of("acme", "acme-2");
        assertThat(BusinessSlugs.derive("Acme", taken::contains)).isEqualTo("acme-3");
        String longTaken = "a".repeat(40);
        assertThat(BusinessSlugs.derive(longTaken, longTaken::equals)).isEqualTo("a".repeat(38) + "-2").hasSize(40);
    }

    @Test
    void everyDerivedNameIsAcceptable() {
        for (String name : new String[] {"Juris Consultants", "A", "", "  ", "Admin", "ééé", "x".repeat(300), "a-b-c", "--a--", "Test", "9", "1 2"}) {
            String slug = BusinessSlugs.derive(name, taken -> false);
            assertThat(BusinessSlugs.problem(slug)).as(name + " -> " + slug).isNull();
        }
    }
}
