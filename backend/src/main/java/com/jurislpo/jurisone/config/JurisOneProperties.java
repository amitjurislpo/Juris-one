package com.jurislpo.jurisone.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings (application.properties and application-&lt;env&gt;.properties; secrets of sqe/uat/prd come from environment variables).
 *
 * @param storageDir   where payslip PDFs, investment proofs, policy PDFs and onboarding files are stored, outside the
 *                     project (dev: Documents/JurisOne). The paths kept in the database are relative to it.
 * @param frontendOrigin the only origin allowed by CORS (the web app)
 * @param jobsEnabled  run the scheduled jobs in this process
 */
@ConfigurationProperties(prefix = "jurisone")
public record JurisOneProperties(String storageDir, String frontendOrigin, boolean jobsEnabled) {

    /** Without an explicit {@code jurisone.storage-dir} the files go to the user's Documents/JurisOne folder. */
    public JurisOneProperties {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = Path.of(System.getProperty("user.home"), "Documents", "JurisOne").toString();
        }
    }
}
