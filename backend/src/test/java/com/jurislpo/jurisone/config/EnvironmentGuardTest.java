package com.jurislpo.jurisone.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Only dev may use the fixed sign-in code; sqe/uat/prd need email; uat and prd are also checked for demo data, http and placeholders. */
class EnvironmentGuardTest {

    static Map<String, String> good(String env) {
        Map<String, String> m = new HashMap<>();
        m.put("jurisone.env", env);
        m.put("jurisone.otp.fixed-code", "");
        m.put("jurisone.seed.demo", "false");
        m.put("jurisone.mail.host", "smtp.example.com");
        m.put("jurisone.app-url", "https://people.example.com");
        m.put("jurisone.security.jwt-secret", "x".repeat(40));
        m.put("jurisone.security.pii-encryption-key", "k".repeat(44));
        m.put("spring.datasource.password", "db-password");
        return m;
    }

    static List<String> problems(Map<String, String> m) {
        return EnvironmentGuard.problems(m::get);
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        assertThat(problems(good("prd"))).isEmpty();
        assertThat(problems(good("uat"))).isEmpty();
    }

    @Test
    void devMayUseTheFixedCodeAndDemoData() {
        Map<String, String> m = new HashMap<>();
        m.put("jurisone.env", "dev");
        m.put("jurisone.otp.fixed-code", "000000");
        m.put("jurisone.seed.demo", "true");
        m.put("jurisone.app-url", "http://localhost:3005");
        assertThat(problems(m)).isEmpty();
    }

    @Test
    void sqeNeedsRealCodesByEmailButMayKeepDemoDataAndHttp() {
        Map<String, String> m = new HashMap<>();
        m.put("jurisone.env", "sqe");
        m.put("jurisone.seed.demo", "true");
        m.put("jurisone.app-url", "http://sqe.internal:3015");
        m.put("jurisone.mail.host", "smtp.example.com");
        assertThat(problems(m)).isEmpty();
        m.put("jurisone.otp.fixed-code", "000000");
        m.put("jurisone.mail.host", "");
        assertThat(problems(m)).hasSize(2).anyMatch(p -> p.contains("jurisone.otp.fixed-code")).anyMatch(p -> p.contains("jurisone.mail.host"));
    }

    @Test
    void anUnsetEnvironmentIsDev() {
        assertThat(problems(new HashMap<>())).isEmpty();
    }

    @Test
    void anUnknownEnvironmentIsRejected() {
        Map<String, String> m = good("staging");
        assertThat(problems(m)).singleElement().asString().contains("jurisone.env must be one of");
    }

    @Test
    void aFixedSignInCodeIsRefusedInProduction() {
        for (String env : List.of("uat", "prd")) {
            Map<String, String> m = good(env);
            m.put("jurisone.otp.fixed-code", "000000");
            assertThat(problems(m)).as(env).singleElement().asString().contains("jurisone.otp.fixed-code");
        }
    }

    @Test
    void demoDataMissingEmailAndHttpAreRefused() {
        Map<String, String> m = good("prd");
        m.put("jurisone.seed.demo", "true");
        m.put("jurisone.mail.host", "");
        m.put("jurisone.app-url", "http://people.example.com");
        assertThat(problems(m)).hasSize(3);
    }

    @Test
    void placeholderAndEmptySecretsAreRefused() {
        Map<String, String> m = good("prd");
        m.put("jurisone.security.jwt-secret", "<generate: openssl rand -base64 48>");
        m.remove("jurisone.security.pii-encryption-key");
        assertThat(problems(m)).hasSize(2).anyMatch(p -> p.startsWith("jurisone.security.jwt-secret")).anyMatch(p -> p.startsWith("jurisone.security.pii-encryption-key"));
    }
}
