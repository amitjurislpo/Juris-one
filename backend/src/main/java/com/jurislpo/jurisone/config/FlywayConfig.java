package com.jurislpo.jurisone.config;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

/**
 * Hands schema ownership from Prisma to Flyway safely. A database created by Prisma is baselined at V1
 * only if every Prisma migration up to the one V1 was built from has been applied; otherwise startup fails
 * rather than marking an older schema as current.
 */
@Configuration
public class FlywayConfig {

    static final String LAST_PRISMA_MIGRATION = "20261006193828_tax_parents_senior";
    static final int PRISMA_MIGRATIONS = 4;

    @Bean
    FlywayMigrationStrategy prismaAwareMigration(DataSource dataSource, org.springframework.core.env.Environment env) {
        return flyway -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            if ("true".equals(env.getProperty("jurisone.reset"))) {
                // `reset` command (development only): drop everything in the schema, then migrate from scratch
                String appUrl = env.getProperty("APP_URL", "http://localhost:3005");
                boolean local = appUrl.matches("^https?://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?$");
                if (!local && !"true".equals(env.getProperty("ALLOW_DB_RESET"))) {
                    throw new IllegalStateException("Refusing to reset a database for " + appUrl + ". Set ALLOW_DB_RESET=true if you really mean it.");
                }
                flyway.clean();
            }
            boolean hasHistory = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean.class));
            boolean prisma = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('_prisma_migrations') IS NOT NULL", Boolean.class));
            if (!hasHistory && prisma) {
                Integer applied = jdbc.queryForObject(
                        "SELECT count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL", Integer.class);
                Integer last = jdbc.queryForObject(
                        "SELECT count(*) FROM _prisma_migrations WHERE migration_name = ? AND finished_at IS NOT NULL", Integer.class, LAST_PRISMA_MIGRATION);
                if (applied == null || applied != PRISMA_MIGRATIONS || last == null || last != 1) {
                    throw new IllegalStateException("Database has " + applied + " Prisma migrations applied; expected all "
                            + PRISMA_MIGRATIONS + " up to " + LAST_PRISMA_MIGRATION + ". Apply the remaining Prisma migrations with the previous release first.");
                }
            }
            if (flyway.getConfiguration().getBaselineVersion().compareTo(MigrationVersion.fromVersion("1")) != 0) {
                throw new IllegalStateException("Flyway baseline version must stay 1 (V1__baseline.sql).");
            }
            flyway.migrate();
        };
    }
}
