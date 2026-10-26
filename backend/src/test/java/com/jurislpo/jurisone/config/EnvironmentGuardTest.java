package com.jurislpo.jurisone.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Only dev may use the fixed sign-in code; sqe/uat/prd need email; uat and prd are also checked for demo data, http and placeholders. */
class EnvironmentGuardTest {

    static Map<String, String> good(String environmentName) {
        Map<String, String> environment = new HashMap<>();
        environment.put("jurisone.env", environmentName);
        environment.put("jurisone.otp.fixed-code", "");
        environment.put("jurisone.seed.demo", "false");
        environment.put("jurisone.mail.host", "smtp.example.com");
        environment.put("jurisone.app-url", "https://people.example.com");
        environment.put("jurisone.security.jwt-secret", "x".repeat(40));
        environment.put("jurisone.security.pii-encryption-key", "k".repeat(44));
        environment.put("spring.datasource.password", "db-password");
        return environment;
    }

    static List<String> problems(Map<String, String> environment) {
        return EnvironmentGuard.problems(environment::get);
    }

    @Test
    void aCompleteProductionConfigurationPasses() {
        assertThat(problems(good("prd"))).isEmpty();
        assertThat(problems(good("uat"))).isEmpty();
    }

    @Test
    void devMayUseTheFixedCodeAndDemoData() {
        Map<String, String> environment = new HashMap<>();
        environment.put("jurisone.env", "dev");
        environment.put("jurisone.otp.fixed-code", "000000");
        environment.put("jurisone.seed.demo", "true");
        environment.put("jurisone.app-url", "http://localhost:3005");
        assertThat(problems(environment)).isEmpty();
    }

    @Test
    void sqeNeedsRealCodesByEmailButMayKeepDemoDataAndHttp() {
        Map<String, String> environment = new HashMap<>();
        environment.put("jurisone.env", "sqe");
        environment.put("jurisone.seed.demo", "true");
        environment.put("jurisone.app-url", "http://sqe.internal:3015");
        environment.put("jurisone.mail.host", "smtp.example.com");
        assertThat(problems(environment)).isEmpty();
        environment.put("jurisone.otp.fixed-code", "000000");
        environment.put("jurisone.mail.host", "");
        assertThat(problems(environment)).hasSize(2).anyMatch(p -> p.contains("jurisone.otp.fixed-code")).anyMatch(p -> p.contains("jurisone.mail.host"));
    }

    @Test
    void anEnabledJurisTrackGatewayNeedsItsUrlAndSigningKeyInSharedEnvironments() {
        for (String environmentName : List.of("sqe", "uat", "prd")) {
            Map<String, String> environment = good(environmentName);
            environment.put("jurisone.juristrack.enabled", "true");
            assertThat(problems(environment)).as(environmentName).hasSize(2).anyMatch(p -> p.contains("base-url")).anyMatch(p -> p.contains("private-key"));
            environment.put("jurisone.juristrack.base-url", "http://juristrack.internal:8081");
            environment.put("jurisone.juristrack.private-key", "MIIE...");
            assertThat(problems(environment)).as(environmentName).isEmpty();
            environment.put("jurisone.juristrack.enabled", "false");
            environment.remove("jurisone.juristrack.private-key");
            assertThat(problems(environment)).as(environmentName + " disabled").isEmpty();
        }
    }

    @Test
    void anUnsetEnvironmentIsDev() {
        assertThat(problems(new HashMap<>())).isEmpty();
    }

    @Test
    void anUnknownEnvironmentIsRejected() {
        Map<String, String> environment = good("staging");
        assertThat(problems(environment)).singleElement().asString().contains("jurisone.env must be one of");
    }

    @Test
    void aFixedSignInCodeIsRefusedInProduction() {
        for (String environmentName : List.of("uat", "prd")) {
            Map<String, String> environment = good(environmentName);
            environment.put("jurisone.otp.fixed-code", "000000");
            assertThat(problems(environment)).as(environmentName).singleElement().asString().contains("jurisone.otp.fixed-code");
        }
    }

    @Test
    void demoDataMissingEmailAndHttpAreRefused() {
        Map<String, String> environment = good("prd");
        environment.put("jurisone.seed.demo", "true");
        environment.put("jurisone.mail.host", "");
        environment.put("jurisone.app-url", "http://people.example.com");
        assertThat(problems(environment)).hasSize(3);
    }

    @Test
    void placeholderAndEmptySecretsAreRefused() {
        Map<String, String> environment = good("prd");
        environment.put("jurisone.security.jwt-secret", "<generate: openssl rand -base64 48>");
        environment.remove("jurisone.security.pii-encryption-key");
        assertThat(problems(environment)).hasSize(2).anyMatch(p -> p.startsWith("jurisone.security.jwt-secret")).anyMatch(p -> p.startsWith("jurisone.security.pii-encryption-key"));
    }
}
