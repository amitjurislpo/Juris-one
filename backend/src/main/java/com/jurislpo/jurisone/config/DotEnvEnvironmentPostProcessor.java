package com.jurislpo.jurisone.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Makes the backend use the same configuration as the Next.js app:
 * <ul>
 *   <li>loads the environment's file {@code .env.<APP_ENV>} (dev, sqe, uat or prd; default dev; falls back to a plain
 *       {@code .env}), found in the working directory or its parent, as the
 *       lowest-priority property source — real environment variables always win;</li>
 *   <li>derives {@code spring.datasource.*} from the Prisma-style {@code DATABASE_URL}
 *       unless {@code SPRING_DATASOURCE_URL} is set explicitly (e.g. in docker-compose);</li>
 *   <li>sets {@code jurisone.project-dir}: stored file paths (payslip PDFs, proofs) are relative to it.</li>
 * </ul>
 */
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Path cwd = Path.of("").toAbsolutePath();
        // .env.<APP_ENV> (dev | sqe | uat | prd; default dev), else the legacy single .env
        String appEnv = env.getProperty("APP_ENV", "dev").trim().toLowerCase();
        Path envFile = null;
        for (String name : new String[] {".env." + appEnv, ".env"}) {
            for (Path dir : new Path[] {cwd, cwd.getParent()}) {
                if (envFile == null && dir != null && Files.isRegularFile(dir.resolve(name))) envFile = dir.resolve(name);
            }
        }
        Map<String, Object> props = new LinkedHashMap<>();
        if (envFile != null) props.putAll(parse(envFile));
        String projectDir = env.getProperty("JURISONE_PROJECT_DIR",
                envFile != null ? envFile.getParent().toString() : (cwd.endsWith("backend") && cwd.getParent() != null ? cwd.getParent() : cwd).toString());
        props.put("jurisone.project-dir", projectDir);
        env.getPropertySources().addLast(new MapPropertySource("dotenv", props));

        String explicit = env.getProperty("spring.datasource.url");
        String dbUrl = env.getProperty("DATABASE_URL");
        if ((explicit == null || explicit.isBlank()) && dbUrl != null && !dbUrl.isBlank()) {
            // lowest priority: SPRING_DATASOURCE_*, command-line args and test properties all win
            Map<String, Object> ds = new HashMap<>(datasourceFrom(dbUrl));
            env.getPropertySources().addLast(new MapPropertySource("database-url", ds));
        }
    }

    /** postgresql://user:pass@host:port/db?schema=public → jdbc url + credentials. */
    static Map<String, String> datasourceFrom(String url) {
        URI u = URI.create(url.replaceFirst("^postgres(ql)?://", "http://"));
        String schema = "public";
        if (u.getRawQuery() != null) {
            for (String kv : u.getRawQuery().split("&")) if (kv.startsWith("schema=")) schema = kv.substring(7);
        }
        String jdbc = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getPath() + "?currentSchema=" + schema;
        Map<String, String> out = new HashMap<>();
        out.put("spring.datasource.url", jdbc);
        if (u.getRawUserInfo() != null) {
            String[] up = u.getRawUserInfo().split(":", 2);
            out.put("spring.datasource.username", decode(up[0]));
            if (up.length > 1) out.put("spring.datasource.password", decode(up[1]));
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /** KEY=value lines; # comments; optional single/double quotes; no interpolation. */
    static Map<String, String> parse(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                char q = val.isEmpty() ? 0 : val.charAt(0);
                boolean quoted = q == '"' || q == '\'';
                // the value is everything up to the last quote when the line ends with one, else up to the first closing quote
                int close = !quoted ? -1 : val.length() >= 2 && val.charAt(val.length() - 1) == q ? val.length() - 1 : val.indexOf(q, 1);
                if (close > 0) {
                    val = val.substring(1, close); // quoted value; anything after the closing quote (a comment) is dropped
                } else {
                    int hash = val.indexOf(" #");
                    if (hash >= 0) val = val.substring(0, hash).strip();
                }
                out.put(key, val);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file, e);
        }
        return out;
    }
}
