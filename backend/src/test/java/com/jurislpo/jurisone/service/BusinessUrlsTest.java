package com.jurislpo.jurisone.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.repository.BusinessRepository;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The one builder of user-facing links: unchanged with the flag off; per-business hosts with it on; the path can never change the host. */
class BusinessUrlsTest {

    static final UUID ACME_ID = UUID.randomUUID();

    static BusinessUrls urls(String appUrl, boolean isEnabled, String baseDomain) {
        BusinessRepository repository = new BusinessRepository(null) {
            @Override
            public String findCode(UUID businessId) {
                return Map.of(ACME_ID, "acme-legal").get(businessId);
            }
        };
        return new BusinessUrls(new JurisOneProperties(null, appUrl, false, new JurisOneProperties.TenantHost(isEnabled, baseDomain, "admin")), repository);
    }

    @Test
    void flagOffReturnsAppUrlPlusPathForEveryKindOfLink() {
        BusinessUrls off = urls("http://localhost:3005", false, "");
        for (String path : new String[] {"/login", "/subscription", "/my-letters", "/payslips", "/onboard/abc123"}) {
            assertThat(off.forBusiness(ACME_ID, path)).isEqualTo("http://localhost:3005" + path);
            assertThat(off.forSlug("whatever", path)).isEqualTo("http://localhost:3005" + path);
            assertThat(off.platformUrl(path)).isEqualTo("http://localhost:3005" + path);
        }
        assertThat(urls("https://people.example.com/", false, "").forBusiness(null, "/login")).isEqualTo("https://people.example.com/login");
    }

    @Test
    void flagOffNeverReadsTheDatabase() {
        assertThat(urls("http://localhost:3005", false, "").forBusiness(UUID.randomUUID(), "/x")).isEqualTo("http://localhost:3005/x");
    }

    @Test
    void flagOnInDevUsesTheCodeAsSubdomainAndKeepsThePort() {
        BusinessUrls on = urls("http://localhost:3005", true, "localhost");
        assertThat(on.forBusiness(ACME_ID, "/login")).isEqualTo("http://acme-legal.localhost:3005/login");
        assertThat(on.forSlug("acme", "/subscription")).isEqualTo("http://acme.localhost:3005/subscription");
        assertThat(on.platformUrl("/platform")).isEqualTo("http://admin.localhost:3005/platform");
    }

    @Test
    void flagOnWithAProductionLikeDomainUsesHttpsWithoutAPort() {
        BusinessUrls on = urls("https://people.example.com", true, "Example.COM");
        assertThat(on.forSlug("juris-consultants", "/my-letters")).isEqualTo("https://juris-consultants.example.com/my-letters");
        assertThat(on.forBusiness(ACME_ID, "/payslips")).isEqualTo("https://acme-legal.example.com/payslips");
        assertThat(on.platformUrl("/")).isEqualTo("https://admin.example.com/");
    }

    @Test
    void aMissingOrUnknownBusinessCannotBuildAHostWhenOn() {
        BusinessUrls on = urls("http://localhost:3005", true, "localhost");
        assertThatThrownBy(() -> on.forBusiness(null, "/login")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> on.forBusiness(UUID.randomUUID(), "/login")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCodeThatIsNotAHostLabelIsRefused() {
        BusinessUrls on = urls("http://localhost:3005", true, "localhost");
        for (String slug : new String[] {"", "-a", "a-", "a.b", "a/b", "a@evil.com", "a b", "evil.com#", "a:80", "a\r\nb", "x".repeat(64)}) {
            assertThatThrownBy(() -> on.forSlug(slug, "/login")).as(slug).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void pathsAreNormalisedAndEncodedInBothModes() {
        for (boolean isEnabled : new boolean[] {false, true}) {
            BusinessUrls urls = urls("http://localhost:3005", isEnabled, "localhost");
            String prefix = isEnabled ? "http://acme.localhost:3005" : "http://localhost:3005";
            assertThat(urls.forSlug("acme", "login")).isEqualTo(prefix + "/login");
            assertThat(urls.forSlug("acme", "")).isEqualTo(prefix + "/");
            assertThat(urls.forSlug("acme", null)).isEqualTo(prefix + "/");
            assertThat(urls.forSlug("acme", "/a/b/")).isEqualTo(prefix + "/a/b");
            assertThat(urls.forSlug("acme", "/my letters/é")).isEqualTo(prefix + "/my%20letters/%C3%A9");
            assertThat(urls.forSlug("acme", "/onboard/ab-c_1.~")).isEqualTo(prefix + "/onboard/ab-c_1.~");
            assertThat(urls.forSlug("acme", "/tasks?status=open&page=2#top")).isEqualTo(prefix + "/tasks?status=open&page=2#top");
            assertThat(urls.forSlug("acme", "/search?q=a b&r=<script>")).isEqualTo(prefix + "/search?q=a%20b&r=%3Cscript%3E");
        }
    }

    @Test
    void hostileInputNeverChangesTheHost() {
        String[] hostile = {"//evil.com", "///evil.com/x", "https://evil.com", "https://evil.com/login", "/../../evil.com", "/..;/evil.com", "\\evil.com", "@evil.com", ".evil.com",
                "/login\r\nLocation: https://evil.com", "/login\r\nHost: evil.com", "/a/../../b", "/%2e%2e/x", "/login?next=https://evil.com", "/login#@evil.com", ":80", "evil.com:80/x", "/\u0000x"};
        for (boolean isEnabled : new boolean[] {false, true}) {
            BusinessUrls urls = urls("http://localhost:3005", isEnabled, "localhost");
            String expectedHost = isEnabled ? "acme.localhost" : "localhost";
            for (String path : hostile) {
                for (String url : new String[] {urls.forSlug("acme", path), urls.forBusiness(ACME_ID, path).replace("acme-legal", "acme"), urls.platformUrl(path).replace("admin.", "acme.")}) {
                    URI uri = URI.create(url);
                    assertThat(uri.getHost()).as(path + " -> " + url).isEqualTo(expectedHost);
                    assertThat(uri.getPort()).as(url).isEqualTo(3005);
                    assertThat(uri.getUserInfo()).as(url).isNull();
                    assertThat(url).as(url).doesNotContain("\r").doesNotContain("\n").doesNotContain(" ").doesNotContain("\\");
                }
            }
        }
    }

    @Test
    void dotDotCannotClimbOutOfTheSite() {
        assertThat(BusinessUrls.normalisePath("/a/../../b")).isEqualTo("/a/b");
        assertThat(BusinessUrls.normalisePath("//evil.com")).isEqualTo("/evil.com");
        assertThat(BusinessUrls.normalisePath("https://evil.com")).isEqualTo("/https%3A/evil.com");
    }

    @Test
    void aBrokenSetupIsRefusedWhenOn() {
        assertThatThrownBy(() -> urls("localhost:3005", true, "localhost")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> urls("http://localhost:3005", true, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> urls("http://localhost:3005", true, "*.example.com")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> urls("http://localhost:3005", true, "example.com:8080")).isInstanceOf(IllegalStateException.class);
        urls("not a url", false, ""); // off: nothing is parsed
    }
}
