package com.jurislpo.jurisone.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings, all from the environment (see application.yml). No secret has a default.
 *
 * @param projectDir   base directory for relative file paths stored in the DB (the repo root in dev)
 * @param frontendOrigin the only origin allowed by CORS (the Next.js app)
 * @param jobsEnabled  run the scheduled jobs in this process
 */
@ConfigurationProperties(prefix = "jurisone")
public record JurisOneProperties(String projectDir, String frontendOrigin, boolean jobsEnabled) {}
