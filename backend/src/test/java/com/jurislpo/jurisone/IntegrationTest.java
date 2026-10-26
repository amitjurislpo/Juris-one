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
        "jurisone.storage-dir=target/test-storage",
        "jurisone.security.jwt-secret=test-only-jwt-secret-0123456789abcdef0123456789", "jurisone.refresh-reuse-grace-seconds=0", "jurisone.otp.requests-per-ip=100000", "jurisone.otp.requests-per-email=100000"})
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> PG = PostgresContainer.start();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        // the JurisTrack gateway talks to a local stub and signs with a key made for the run
        registry.add("jurisone.juristrack.enabled", () -> "true");
        registry.add("jurisone.juristrack.base-url", JurisTrackStub::baseUrl);
        registry.add("jurisone.juristrack.issuer", () -> "http://localhost:3005");
        registry.add("jurisone.juristrack.private-key", JurisTrackStub::privateKey);
        registry.add("jurisone.juristrack.roster-debounce-millis", () -> "300"); // a burst of changes still becomes one push, without slowing the tests
    }
}
