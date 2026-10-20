package com.jurislpo.jurisone;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for API tests: one Postgres 16 container shared by every subclass (the Spring context is cached too),
 * schema from Flyway V1. Tests create the rows they need.
 */
@SpringBootTest(properties = {"jurisone.jobs-enabled=false", "jurisone.security.pii-encryption-key=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=", "jurisone.mail.host=", "jurisone.app-url=http://localhost:3005",
        "jurisone.upload-dir=backend/target/test-storage",
        "jurisone.security.jwt-secret=test-only-jwt-secret-0123456789abcdef0123456789", "jurisone.refresh-reuse-grace-seconds=0", "jurisone.password-login.enabled=true"})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> PG = PostgresContainer.start();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
}
