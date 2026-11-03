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
        assertThat(BusinessSlugs.derive("Juris Consultants")).isEqualTo("juris-consultants");
        assertThat(BusinessSlugs.derive("  Acme & Sons (P) Ltd. ")).isEqualTo("acme-sons-p-ltd");
        assertThat(BusinessSlugs.derive("हिन्दी")).isEqualTo("business"); // nothing usable left
        assertThat(BusinessSlugs.derive("!!!")).isEqualTo("business");
        assertThat(BusinessSlugs.derive("Ab")).isEqualTo("ab-co"); // too short
        assertThat(BusinessSlugs.derive("X")).isEqualTo("x-co");
        assertThat(BusinessSlugs.derive("Admin")).isEqualTo("admin-co"); // reserved
        assertThat(BusinessSlugs.derive("JurisOne")).isEqualTo("jurisone-co");
        assertThat(BusinessSlugs.derive("Test")).isEqualTo("test-co");
        assertThat(BusinessSlugs.derive("xn--foo")).isEqualTo("xn-foo");
        String longName = BusinessSlugs.derive("a".repeat(30) + " " + "b".repeat(30));
        assertThat(longName).hasSize(40).endsWith("b"); // cut at 40 characters: 30 a, a dash, 9 b
        String cut = BusinessSlugs.derive("a".repeat(39) + " bcd");
        assertThat(cut).isEqualTo("a".repeat(39)); // the cut would end on a dash: it is trimmed
    }

    @Test
    void deriveNeverNumbersAndNeverLooksAtWhatIsTaken() {
        assertThat(BusinessSlugs.derive("Acme")).isEqualTo("acme");
        assertThat(BusinessSlugs.derive("Acme")).isEqualTo(BusinessSlugs.derive("Acme"));
    }

    @Test
    void suggestionsAreValidUniqueAndShort() {
        for (String typed : new String[] {"juris consultants", "Juris-Consultants", "acme", "a".repeat(40), "x".repeat(500), "admin", "ab", "Acme & Sons (P) Ltd.", "a'; DROP TABLE businesses; --", "日本語"}) {
            java.util.List<String> suggestions = BusinessSlugs.suggestionCandidates(typed);
            assertThat(suggestions).as(typed.length() > 30 ? "long" : typed).doesNotHaveDuplicates().allSatisfy(slug -> assertThat(BusinessSlugs.problem(slug)).isNull());
        }
        assertThat(BusinessSlugs.suggestionCandidates("Juris Consultants Llp")).contains("juris-consultants-llp", "juris-consultants-llp-hr", "jcl", "jcl-co");
        assertThat(BusinessSlugs.suggestionCandidates("acme")).startsWith("acme-2", "acme-hr").doesNotContain("acme");
        assertThat(BusinessSlugs.suggestionCandidates("!!!")).isEmpty();
        assertThat(BusinessSlugs.suggestionCandidates(null)).isEmpty();
    }

    @Test
    void everyDerivedNameIsAcceptable() {
        for (String name : new String[] {"Juris Consultants", "A", "", "  ", "Admin", "ééé", "x".repeat(300), "a-b-c", "--a--", "Test", "9", "1 2"}) {
            String slug = BusinessSlugs.derive(name);
            assertThat(BusinessSlugs.problem(slug)).as(name + " -> " + slug).isNull();
        }
    }
}
