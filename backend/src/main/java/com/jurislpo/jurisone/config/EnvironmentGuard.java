package com.jurislpo.jurisone.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a shared environment (uat, prd) with development shortcuts or unfilled placeholders: a fixed
 * sign-in code, demo data, missing email delivery, an http:// public URL, or a secret still written as {@code <...>}.
 * dev and sqe are not checked. The environment name is {@code APP_ENV} (dev | sqe | uat | prd; default dev).
 */
@Component
public class EnvironmentGuard {

    static final List<String> ENVIRONMENTS = List.of("dev", "sqe", "uat", "prd");
    static final List<String> STRICT = List.of("uat", "prd");
    private static final List<String> SECRETS = List.of("JWT_SECRET", "PII_ENCRYPTION_KEY", "SUPER_ADMIN_PASSWORD", "DATABASE_URL");

    private final Environment env;

    public EnvironmentGuard(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void check() {
        List<String> problems = problems(env::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration for APP_ENV=" + env.getProperty("APP_ENV", "dev") + ":\n - " + String.join("\n - ", problems));
        }
    }

    /** What is wrong with this configuration (empty = fine). {@code get} returns a property or null. */
    static List<String> problems(Function<String, String> get) {
        List<String> out = new ArrayList<>();
        String name = blankTo(get.apply("APP_ENV"), "dev").trim().toLowerCase();
        if (!ENVIRONMENTS.contains(name)) {
            out.add("APP_ENV must be one of " + ENVIRONMENTS + ", not \"" + name + "\"");
            return out;
        }
        if (!STRICT.contains(name)) return out;
        if (!blankTo(get.apply("jurisone.otp.fixed-code"), "").isBlank()) out.add("OTP_FIXED_CODE must be empty (a fixed sign-in code is for dev and sqe only)");
        if ("true".equalsIgnoreCase(blankTo(get.apply("SEED_DEMO"), "false"))) out.add("SEED_DEMO must be false (no demo data outside dev and sqe)");
        if (blankTo(get.apply("SMTP_HOST"), "").isBlank()) out.add("SMTP_HOST must be set (sign-in codes and invites are emailed)");
        if (!blankTo(get.apply("APP_URL"), "").startsWith("https://")) out.add("APP_URL must be an https:// URL");
        for (String key : SECRETS) {
            String v = blankTo(get.apply(key), "");
            if (v.isBlank() || v.contains("<")) out.add(key + " is not set (still a placeholder or empty)");
        }
        return out;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
