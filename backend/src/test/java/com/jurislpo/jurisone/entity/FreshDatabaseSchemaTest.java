package com.jurislpo.jurisone.entity;

import com.jurislpo.jurisone.entity.AttendanceDay;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.Department;
import com.jurislpo.jurisone.entity.EmailOutbox;
import com.jurislpo.jurisone.entity.EmailStatus;
import com.jurislpo.jurisone.entity.Employee;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.entity.Region;
import com.jurislpo.jurisone.entity.Role;
import com.jurislpo.jurisone.entity.RolePermission;
import com.jurislpo.jurisone.entity.Setting;
import com.jurislpo.jurisone.entity.Shift;
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
@SpringBootTest(properties = "jurisone.jobs-enabled=false")
@Transactional
class FreshDatabaseSchemaTest {

    static final PostgreSQLContainer<?> PG = PostgresContainer.start();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

    @AfterAll
    static void stop() { PG.stop(); }

    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayAppliedTheBaseline() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class)).startsWith("1", "2", "3", "4", "5", "6", "7", "8", "9").contains("12", "15", "22", "23"); // HR letters, employee onboarding
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_type WHERE typname = 'EmployeeStatus'", Integer.class)).isEqualTo(1);
    }

    @Test
    void employeeOnboardingTablesReplaceTheCandidateFlow() {
        for (String table : new String[] {"employee_onboardings", "onboarding_personal", "onboarding_addresses", "onboarding_emergency_contacts", "onboarding_education", "onboarding_courses",
                "onboarding_employments", "onboarding_bank", "onboarding_statutory", "onboarding_family_members", "onboarding_nominations", "onboarding_attachments", "onboarding_review_flags"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table)).as(table).isEqualTo(1);
        }
        for (String table : new String[] {"onboardings", "onboarding_documents"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table)).as(table).isZero();
        }
    }

    @Test
    void entitiesRoundTripWithNativeEnumsJsonAndDefaults() throws Exception {
        insideABusiness();
        Role role = new Role();
        role.setKey("employee");
        role.setName("Employee");
        entityManager.persist(role);

        Shift shift = new Shift();
        shift.setCode("IN_DAY");
        shift.setName("Day");
        shift.setStartLocal("09:30");
        shift.setEndLocal("18:30");
        shift.setRegion(Region.IN);
        entityManager.persist(shift);

        Employee employee = new Employee();
        employee.setCode("JC-001");
        employee.setFullName("Test Person");
        employee.setEmail("t@example.com");
        employee.setDesignation("Tester");
        employee.setRoleId(role.getId());
        employee.setShiftId(shift.getId());
        employee.setDateOfJoining(LocalDate.of(2026, 4, 1));
        employee.setTimeZone("Asia/Kolkata");
        employee.setRegion(Region.IN);
        entityManager.persist(employee);

        RolePermission rolePermission = new RolePermission();
        rolePermission.setRoleId(role.getId());
        rolePermission.setPermissionKey("self");
        entityManager.persist(rolePermission);

        AttendanceDay day = new AttendanceDay();
        day.setEmployeeId(employee.getId());
        day.setWorkDate(LocalDate.of(2026, 9, 8));
        day.setStatus(DayStatus.NOT_TRACKED);
        day.setLopDays(new BigDecimal("0.5"));
        entityManager.persist(day);

        Setting setting = new Setting();
        setting.setKey("company");
        setting.setValue(new ObjectMapper().readTree("{\"name\":\"Juris\",\"n\":1}"));
        entityManager.persist(setting);

        EmailOutbox mail = new EmailOutbox();
        mail.setTo("t@example.com");
        mail.setSubject("Hi");
        mail.setText("Body");
        entityManager.persist(mail);
        entityManager.flush();
        entityManager.clear();

        Employee back = entityManager.find(Employee.class, employee.getId());
        assertThat(back.getStatus()).isEqualTo(EmployeeStatus.ONBOARDING); // the Prisma default
        assertThat(back.getPortalAccess()).isTrue();
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(back.getUpdatedAt()).isNotNull();
        assertThat(back.getRole().getKey()).isEqualTo("employee"); // read-only navigation
        assertThat(entityManager.find(AttendanceDay.class, new AttendanceDay.Key(employee.getId(), LocalDate.of(2026, 9, 8))).getStatus()).isEqualTo(DayStatus.NOT_TRACKED);
        assertThat(jdbc.queryForObject("SELECT (value->>'n')::int FROM settings WHERE key = 'company' AND value->>'name' = 'Juris'", Integer.class)).isEqualTo(1);
        assertThat(entityManager.find(EmailOutbox.class, mail.getId()).getStatus()).isEqualTo(EmailStatus.QUEUED);
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, employee.getId())).isEqualTo("ONBOARDING");
        assertThat(jdbc.queryForObject("SELECT \"to\" FROM email_outbox WHERE id = ?", String.class, mail.getId())).isEqualTo("t@example.com");
    }

    /** Business-owned rows take their business from app.business_id (set on this test's transaction). */
    UUID insideABusiness() {
        UUID businessId = UUID.randomUUID();
        jdbc.update("INSERT INTO businesses (id, name, code, employee_prefix) VALUES (?, 'Schema test', ?, 'ST')", businessId, "schema-" + businessId);
        jdbc.queryForObject("SELECT set_config('app.business_id', ?, false)", String.class, businessId.toString());
        return businessId;
    }

    @Test
    void assignedIdsAreKept() {
        UUID business = insideABusiness();
        UUID id = UUID.randomUUID();
        Department department = new Department();
        department.setId(id);
        department.setName("Finance");
        entityManager.persist(department);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT name FROM departments WHERE id = ?", String.class, id)).isEqualTo("Finance");
        assertThat(jdbc.queryForObject("SELECT business_id FROM departments WHERE id = ?", UUID.class, id)).isEqualTo(business);
    }
}
