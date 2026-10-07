package com.jurislpo.jurisone.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.PostgresContainer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Empty database: Flyway applies V1__baseline.sql, then Hibernate validates every entity
 * against it (ddl-auto=validate). Representative rows round-trip through JPA.
 */
@SpringBootTest
@Transactional
class FreshDatabaseSchemaTest {

    static final PostgreSQLContainer<?> PG = PostgresContainer.start();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @AfterAll
    static void stop() { PG.stop(); }

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheBaseline() {
        assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_type WHERE typname = 'EmployeeStatus'", Integer.class)).isEqualTo(1);
    }

    @Test
    void entitiesRoundTripWithNativeEnumsJsonAndDefaults() throws Exception {
        Role role = new Role();
        role.setKey("employee");
        role.setName("Employee");
        em.persist(role);

        Shift shift = new Shift();
        shift.setCode("IN_DAY");
        shift.setName("Day");
        shift.setStartLocal("09:30");
        shift.setEndLocal("18:30");
        shift.setRegion(Region.IN);
        em.persist(shift);

        Employee e = new Employee();
        e.setCode("JC-001");
        e.setFullName("Test Person");
        e.setEmail("t@example.com");
        e.setDesignation("Tester");
        e.setRoleId(role.getId());
        e.setShiftId(shift.getId());
        e.setDateOfJoining(LocalDate.of(2026, 4, 1));
        e.setTimeZone("Asia/Kolkata");
        e.setRegion(Region.IN);
        em.persist(e);

        RolePermission rp = new RolePermission();
        rp.setRoleId(role.getId());
        rp.setPermissionKey("self");
        em.persist(rp);

        AttendanceDay day = new AttendanceDay();
        day.setEmployeeId(e.getId());
        day.setWorkDate(LocalDate.of(2026, 9, 8));
        day.setStatus(DayStatus.NOT_TRACKED);
        day.setLopDays(new BigDecimal("0.5"));
        em.persist(day);

        Setting s = new Setting();
        s.setKey("company");
        s.setValue(new ObjectMapper().readTree("{\"name\":\"Juris\",\"n\":1}"));
        em.persist(s);

        EmailOutbox mail = new EmailOutbox();
        mail.setTo("t@example.com");
        mail.setSubject("Hi");
        mail.setText("Body");
        em.persist(mail);
        em.flush();
        em.clear();

        Employee back = em.find(Employee.class, e.getId());
        assertThat(back.getStatus()).isEqualTo(EmployeeStatus.ONBOARDING); // the Prisma default
        assertThat(back.getPortalAccess()).isTrue();
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(back.getUpdatedAt()).isNotNull();
        assertThat(back.getRole().getKey()).isEqualTo("employee"); // read-only navigation
        assertThat(em.find(AttendanceDay.class, new AttendanceDay.Key(e.getId(), LocalDate.of(2026, 9, 8))).getStatus()).isEqualTo(DayStatus.NOT_TRACKED);
        assertThat(em.find(Setting.class, "company").getValue().get("n").asInt()).isEqualTo(1);
        assertThat(em.find(EmailOutbox.class, mail.getId()).getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, e.getId())).isEqualTo("ONBOARDING");
        assertThat(jdbc.queryForObject("SELECT \"to\" FROM email_outbox WHERE id = ?", String.class, mail.getId())).isEqualTo("t@example.com");
    }

    @Test
    void assignedIdsAreKept() {
        UUID id = UUID.randomUUID();
        Department d = new Department();
        d.setId(id);
        d.setName("Finance");
        em.persist(d);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT name FROM departments WHERE id = ?", String.class, id)).isEqualTo("Finance");
    }
}
