package com.jurislpo.jurisone.config;

import com.jurislpo.jurisone.config.FlywayConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.JurisOneApplication;
import com.jurislpo.jurisone.PostgresContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** A database created by Prisma is baselined at V1 (not re-migrated) — but only when all Prisma migrations are applied. */
class ExistingPrismaDatabaseTest {

    static final String[] MIGRATIONS = {
        "20261005220955_init", "20261006134356_department_archive", "20261006143825_attendance_engine", FlywayConfig.LAST_PRISMA_MIGRATION,
    };

    static void prismaDatabase(PostgreSQLContainer<?> pg, int migrationsApplied) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement st = c.createStatement()) {
            st.execute(Files.readString(Path.of("src/main/resources/db/migration/V1__baseline.sql")));
            st.execute("CREATE TABLE _prisma_migrations (id varchar(36) PRIMARY KEY, checksum varchar(64) NOT NULL, finished_at timestamptz, "
                    + "migration_name varchar(255) NOT NULL, logs text, rolled_back_at timestamptz, started_at timestamptz NOT NULL DEFAULT now(), "
                    + "applied_steps_count int NOT NULL DEFAULT 0)");
            for (int i = 0; i < migrationsApplied; i++) {
                st.execute("INSERT INTO _prisma_migrations (id, checksum, finished_at, migration_name, applied_steps_count) "
                        + "VALUES ('m" + i + "', 'x', now(), '" + MIGRATIONS[i] + "', 1)");
            }
            st.execute("INSERT INTO roles (id, key, name) VALUES ('11111111-1111-1111-1111-111111111111', 'admin', 'Admin')");
        }
    }

    static ConfigurableApplicationContext start(PostgreSQLContainer<?> pg) {
        return new SpringApplicationBuilder(JurisOneApplication.class).run(
                "--spring.datasource.url=" + pg.getJdbcUrl(), "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(), "--server.port=0", "--JOBS_ENABLED=false");
    }

    @Test
    void fullyMigratedPrismaDatabaseIsBaselinedAndKeepsItsData() throws Exception {
        PostgreSQLContainer<?> pg = PostgresContainer.start();
        try {
            prismaDatabase(pg, 4);
            try (ConfigurableApplicationContext ctx = start(pg)) {
                JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version = '1'", String.class)).isEqualTo("BASELINE");
                assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9"); // baseline, then later migrations
                // (the platform company, created at startup, has roles of its own)
                assertThat(jdbc.queryForList("SELECT name FROM roles WHERE business_id IS NULL OR business_id NOT IN (SELECT id FROM businesses WHERE is_platform)",
                        String.class)).containsExactly("Admin");
            }
        } finally {
            pg.stop();
        }
    }

    @Test
    void partiallyMigratedPrismaDatabaseIsRefused() throws Exception {
        PostgreSQLContainer<?> pg = PostgresContainer.start();
        try {
            prismaDatabase(pg, 3);
            assertThatThrownBy(() -> start(pg).close()).hasStackTraceContaining("Apply the remaining Prisma migrations");
        } finally {
            pg.stop();
        }
    }
}
