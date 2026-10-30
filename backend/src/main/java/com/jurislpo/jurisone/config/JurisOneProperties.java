package com.jurislpo.jurisone.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * App settings (application.properties and application-&lt;env&gt;.properties; secrets of sqe/uat/prd come from environment variables).
 *
 * @param storageDir   where payslip PDFs, investment proofs, policy PDFs and onboarding files are stored, outside the
 *                     project (dev: Documents/JurisOne). The paths kept in the database are relative to it.
 * @param frontendOrigin the only origin allowed by CORS (the web app)
 * @param jobsEnabled  run the scheduled jobs in this process
 * @param tenantHost   per-business web addresses ({@code jurisone.tenant-host.*}); off unless switched on
 */
@ConfigurationProperties(prefix = "jurisone")
public record JurisOneProperties(String storageDir, String frontendOrigin, boolean jobsEnabled, @DefaultValue TenantHost tenantHost) {

    /**
     * @param enabled    links use {@code <business code>.<baseDomain>}; false = every link is the single {@code jurisone.app-url}
     * @param baseDomain the domain below which each business gets its own host (dev: {@code localhost}; sqe/uat/prd: {@code TENANT_BASE_DOMAIN})
     * @param adminLabel the first label of the platform (super admin) host: {@code <adminLabel>.<baseDomain>}
     */
    public record TenantHost(@DefaultValue("false") boolean enabled, @DefaultValue("") String baseDomain, @DefaultValue("admin") String adminLabel) {}

    /** Without an explicit {@code jurisone.storage-dir} the files go to the user's Documents/JurisOne folder. */
    public JurisOneProperties {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = Path.of(System.getProperty("user.home"), "Documents", "JurisOne").toString();
        }
    }
}
