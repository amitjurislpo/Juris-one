package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The host rules, with good and hostile hosts (no database: a map stands in for businesses.code). */
class TenantHostResolverTest {

    static final UUID ACME_ID = UUID.randomUUID();
    static final UUID GLOBEX_ID = UUID.randomUUID();
    static final Map<String, UUID> BUSINESSES = Map.of("acme", ACME_ID, "globex-co", GLOBEX_ID);

    final List<String> lookedUp = new ArrayList<>();
    final TenantHostResolver resolver = resolver("localhost");

    TenantHostResolver resolver(String baseDomain) {
        return new TenantHostResolver(baseDomain, "admin", new SlugLookupCache(slug -> {
            lookedUp.add(slug);
            return BUSINESSES.get(slug);
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme.localhost", "ACME.LOCALHOST", "Acme.Localhost", "acme.localhost.", "acme.localhost:3005", "acme.localhost.:3005", "acme.localhost:80"})
    void aKnownSlugBelowTheBaseDomainIsThatBusiness(String host) {
        assertThat(resolver.resolve(host)).isEqualTo(HostBusiness.business(ACME_ID, "acme"));
    }

    @Test
    void anotherKnownSlugIsAnotherBusiness() {
        assertThat(resolver.resolve("globex-co.localhost")).isEqualTo(HostBusiness.business(GLOBEX_ID, "globex-co"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin.localhost", "ADMIN.localhost", "admin.localhost.", "admin.localhost:3005"})
    void theAdminLabelIsThePlatform(String host) {
        assertThat(resolver.resolve(host)).isEqualTo(HostBusiness.PLATFORM);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "localhost", "localhost.", "localhost:3005", ".localhost", "..localhost", "acme..localhost", ".acme.localhost", // bare base, empty labels
            "unknown.localhost", "nobody-here.localhost", // not a business
            "a.b.localhost", "acme.admin.localhost", "admin.admin.localhost", "x.acme.localhost", "www.acme.localhost", // nested
            "acme.localhost..", "acme.localhost...", // two trailing dots
            "127.0.0.1", "127.0.0.1:3005", "10.0.0.1", "acme.127.0.0.1", "[::1]", "[::1]:3005", "::1", "[acme.localhost]", "acme.localhost:::3005", // addresses
            "acme.example.com", "acme.localhost.evil.com", "evil.com", "acmelocalhost", "xacme.localhost.com", "acme-localhost", "acme_localhost", // other domains
            "acme.localhost:abc", "acme.localhost:", "acme.localhost:3005:3005", "acme.localhost:123456", "acme.localhost:-1", "acme.localhost: 80", // ports
            "evil.com#.localhost", "evil.com?.localhost", "evil.com/.localhost", "evil.com\\.localhost", "evil.com@acme.localhost", "acme@acme.localhost", "user:pass@acme.localhost",
            "acme.localhost/", "acme.localhost/x", "acme.localhost\\", "acme.localhost#", "acme.localhost?x=1", "acme.localhost@evil.com", // separators and credentials
            " acme.localhost", "acme.localhost ", "acme .localhost", "acme.localhost\t", "acme.localhost\n", "acme.localhost\r\nX: y", "acme.localhost\0", "acme%2e.localhost", "acme%00.localhost", // whitespace, control, encoding
            "acme.localhosт", "аcme.localhost", "acmе.localhost", "Kcme.localhost", "acKe.localhost", "İacme.localhost", "acme.localhost。", "acme。localhost", "acme.localhost​", "ａcme.localhost", // unicode lookalikes
            "xn--acme-abc.localhost", "xn--.localhost", "XN--80ak6aa92e.localhost", "xn--n3h.localhost", // punycode
            "ab.localhost", "a.localhost", "-acme.localhost", "acme-.localhost", "ac--me.localhost", "a_cme.localhost", "ac me.localhost", "ac*me.localhost", // not a slug
            "www.localhost", "api.localhost", "login.localhost", "jurisone.localhost", "juristrack.localhost" // reserved names
    })
    void everythingElseIsUnknown(String host) {
        assertThat(resolver.resolve(host)).isEqualTo(HostBusiness.UNKNOWN);
    }

    @Test
    void aVeryLongHostIsUnknownAndNeverReadsTheDatabase() {
        String longLabel = "a".repeat(63);
        for (String host : new String[] {longLabel + ".localhost", "a".repeat(300) + ".localhost", ("a".repeat(60) + ".").repeat(10) + "localhost", "a".repeat(100_000)}) {
            assertThat(resolver.resolve(host)).isEqualTo(HostBusiness.UNKNOWN);
        }
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void onlyPossibleSlugsReachTheDatabase() {
        resolver.resolve("unknown.localhost");
        resolver.resolve("a.b.localhost");
        resolver.resolve("www.localhost");
        resolver.resolve("xn--abc-def.localhost");
        resolver.resolve("evil.com#.localhost");
        assertThat(lookedUp).containsExactly("unknown");
    }

    @Test
    void theBaseDomainIsComparedWholeSoALookalikeSuffixIsNotAMatch() {
        TenantHostResolver production = resolver("jurisone.example.com");
        assertThat(production.resolve("acme.jurisone.example.com")).isEqualTo(HostBusiness.business(ACME_ID, "acme"));
        assertThat(production.resolve("admin.jurisone.example.com:8443")).isEqualTo(HostBusiness.PLATFORM);
        for (String host : new String[] {"jurisone.example.com", "acme.example.com", "acmejurisone.example.com", "acme.jurisone.example.com.evil.com", "evil-jurisone.example.com",
                "acme.xjurisone.example.com", "a.acme.jurisone.example.com", "acme.localhost"}) {
            assertThat(production.resolve(host)).as(host).isEqualTo(HostBusiness.UNKNOWN);
        }
    }

    @Test
    void withoutABaseDomainEverythingIsUnknown() {
        for (String baseDomain : new String[] {"", " ", null}) {
            assertThat(resolver(baseDomain).resolve("acme.localhost")).isEqualTo(HostBusiness.UNKNOWN);
            assertThat(resolver(baseDomain).resolve("admin.")).isEqualTo(HostBusiness.UNKNOWN);
        }
    }

    @Test
    void theConfiguredBaseDomainIsNormalised() {
        assertThat(resolver(" LocalHost. ").resolve("acme.localhost")).isEqualTo(HostBusiness.business(ACME_ID, "acme"));
    }

    @Test
    void theSameSlugIsReadFromTheDatabaseOnlyOnce() {
        AtomicInteger reads = new AtomicInteger();
        TenantHostResolver counting = new TenantHostResolver("localhost", "admin", new SlugLookupCache(slug -> {
            reads.incrementAndGet();
            return ACME_ID;
        }));
        counting.resolve("acme.localhost");
        counting.resolve("ACME.localhost:3005");
        counting.resolve("acme.localhost.");
        assertThat(reads).hasValue(1);
        counting.evict("acme");
        counting.resolve("acme.localhost");
        assertThat(reads).hasValue(2);
    }
}
