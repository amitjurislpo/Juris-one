package com.jurislpo.jurisone.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a shared environment with development shortcuts or unfilled placeholders. Only dev may use the fixed
 * sign-in code; sqe, uat and prd must send real codes by email (SMTP set). uat and prd are also checked for demo data,
 * an http:// public URL, per-business addresses switched on without a real base domain, an enabled JurisTrack gateway without its URL or signing key (all shared environments) and secrets still written as {@code <...>} or missing. The environment name is
 * {@code jurisone.env} (dev | sqe | uat | prd), set by each application-&lt;env&gt;.properties.
 */
@Component
public class EnvironmentGuard {

    static final List<String> ENVIRONMENTS = List.of("dev", "sqe", "uat", "prd");
    static final List<String> STRICT = List.of("uat", "prd");
    private static final List<String> SECRETS = List.of("jurisone.security.jwt-secret", "jurisone.security.pii-encryption-key", "spring.datasource.password");

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern BASE_DOMAIN = Pattern.compile("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private final Environment environment;

    public EnvironmentGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        List<String> problems = problems(environment::getProperty);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration for jurisone.env=" + environment.getProperty("jurisone.env", "dev") + ":\n - " + String.join("\n - ", problems));
        }
    }

    /** What is wrong with this configuration (empty = fine). {@code get} returns a property or null. */
    static List<String> problems(Function<String, String> get) {
        List<String> out = new ArrayList<>();
        String name = blankTo(get.apply("jurisone.env"), "dev").trim().toLowerCase();
        if (!ENVIRONMENTS.contains(name)) {
            out.add("jurisone.env must be one of " + ENVIRONMENTS + ", not \"" + name + "\"");
            return out;
        }
        if ("dev".equals(name)) return out;
        if (!blankTo(get.apply("jurisone.otp.fixed-code"), "").isBlank()) out.add("jurisone.otp.fixed-code must be empty (a fixed sign-in code is for dev only)");
        if (blankTo(get.apply("jurisone.mail.host"), "").isBlank()) out.add("jurisone.mail.host must be set (sign-in codes are emailed)");
        if ("true".equalsIgnoreCase(blankTo(get.apply("jurisone.juristrack.enabled"), "false"))) {
            if (blankTo(get.apply("jurisone.juristrack.base-url"), "").isBlank()) out.add("jurisone.juristrack.base-url must be set when the JurisTrack gateway is enabled (JURISTRACK_BASE_URL)");
            if (blankTo(get.apply("jurisone.juristrack.private-key"), "").isBlank()) out.add("jurisone.juristrack.private-key must be set when the JurisTrack gateway is enabled (JURISTRACK_PRIVATE_KEY)");
        }
        if ("true".equalsIgnoreCase(blankTo(get.apply("jurisone.tenant-host.enabled"), "false"))) out.addAll(tenantHostProblems(get));
        if (!STRICT.contains(name)) return out;
        if ("true".equalsIgnoreCase(blankTo(get.apply("jurisone.seed.demo"), "false"))) out.add("jurisone.seed.demo must be false (no demo data outside dev and sqe)");
        if (!blankTo(get.apply("jurisone.app-url"), "").startsWith("https://")) out.add("jurisone.app-url must be an https:// URL");
        for (String key : SECRETS) {
            String value = blankTo(get.apply(key), "");
            if (value.isBlank() || value.contains("<")) out.add(key + " is not set (still a placeholder or empty)");
        }
        return out;
    }

    /** Per-business addresses on: a real base domain (no scheme, port, path or wildcard, not localhost) and a valid admin label. */
    private static List<String> tenantHostProblems(Function<String, String> get) {
        List<String> out = new ArrayList<>();
        String baseDomain = blankTo(get.apply("jurisone.tenant-host.base-domain"), "").trim();
        if (baseDomain.isBlank()) out.add("jurisone.tenant-host.base-domain must be set when jurisone.tenant-host.enabled=true (TENANT_BASE_DOMAIN)");
        else if (baseDomain.equalsIgnoreCase("localhost") || baseDomain.toLowerCase().endsWith(".localhost")) out.add("jurisone.tenant-host.base-domain must not be localhost outside dev");
        else if (!BASE_DOMAIN.matcher(baseDomain.toLowerCase()).matches()) out.add("jurisone.tenant-host.base-domain must be a plain domain name such as example.com (no scheme, port, path, wildcard or spaces)");
        String adminLabel = blankTo(get.apply("jurisone.tenant-host.admin-label"), "admin").trim();
        if (!DNS_LABEL.matcher(adminLabel).matches()) out.add("jurisone.tenant-host.admin-label must be one lowercase DNS label (letters, digits, hyphens)");
        return out;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
