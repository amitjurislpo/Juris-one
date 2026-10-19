package com.jurislpo.jurisone.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** uat and prd must not start with development shortcuts or unfilled placeholders; dev and sqe are free. */
class EnvironmentGuardTest {

    static Map<String, String> good(String env) {
        Map<String, String> m = new HashMap<>();
        m.put("APP_ENV", env);
        m.put("jurisone.otp.fixed-code", "");
        m.put("SEED_DEMO", "false");
        m.put("SMTP_HOST", "smtp.example.com");
        m.put("APP_URL", "https://people.example.com");
        m.put("JWT_SECRET", "x".repeat(40));
        m.put("PII_ENCRYPTION_KEY", "k".repeat(44));
        m.put("SUPER_ADMIN_PASSWORD", "s3cret-pass");
        m.put("DATABASE_URL", "postgresql://u:p@db:5432/x");
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
    void devAndSqeAreNotChecked() {
        for (String env : List.of("dev", "sqe")) {
            Map<String, String> m = new HashMap<>();
            m.put("APP_ENV", env);
            m.put("jurisone.otp.fixed-code", "000000");
            m.put("SEED_DEMO", "true");
            m.put("APP_URL", "http://localhost:3005");
            assertThat(problems(m)).as(env).isEmpty();
        }
    }

    @Test
    void anUnsetEnvironmentIsDev() {
        assertThat(problems(new HashMap<>())).isEmpty();
    }

    @Test
    void anUnknownEnvironmentIsRejected() {
        Map<String, String> m = good("staging");
        assertThat(problems(m)).singleElement().asString().contains("APP_ENV must be one of");
    }

    @Test
    void aFixedSignInCodeIsRefusedInProduction() {
        for (String env : List.of("uat", "prd")) {
            Map<String, String> m = good(env);
            m.put("jurisone.otp.fixed-code", "000000");
            assertThat(problems(m)).as(env).singleElement().asString().contains("OTP_FIXED_CODE");
        }
    }

    @Test
    void demoDataMissingEmailAndHttpAreRefused() {
        Map<String, String> m = good("prd");
        m.put("SEED_DEMO", "true");
        m.put("SMTP_HOST", "");
        m.put("APP_URL", "http://people.example.com");
        assertThat(problems(m)).hasSize(3);
    }

    @Test
    void placeholderAndEmptySecretsAreRefused() {
        Map<String, String> m = good("prd");
        m.put("JWT_SECRET", "<generate: openssl rand -base64 48>");
        m.remove("PII_ENCRYPTION_KEY");
        assertThat(problems(m)).hasSize(2).anyMatch(p -> p.startsWith("JWT_SECRET")).anyMatch(p -> p.startsWith("PII_ENCRYPTION_KEY"));
    }
}
