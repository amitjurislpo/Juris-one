package com.jurislpo.jurisone.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings (application.properties and application-&lt;env&gt;.properties; secrets of sqe/uat/prd come from environment variables).
 *
 * @param projectDir   base directory for relative file paths stored in the DB (the repo root in dev)
 * @param frontendOrigin the only origin allowed by CORS (the web app)
 * @param jobsEnabled  run the scheduled jobs in this process
 */
@ConfigurationProperties(prefix = "jurisone")
public record JurisOneProperties(String projectDir, String frontendOrigin, boolean jobsEnabled) {

    /** Without an explicit {@code jurisone.project-dir} (JURISONE_PROJECT_DIR) it is the repository root: the working directory, or its parent when run from backend/. */
    public JurisOneProperties {
        if (projectDir == null || projectDir.isBlank()) {
            Path cwd = Path.of("").toAbsolutePath();
            projectDir = (cwd.endsWith("backend") && cwd.getParent() != null ? cwd.getParent() : cwd).toString();
        }
    }
}
