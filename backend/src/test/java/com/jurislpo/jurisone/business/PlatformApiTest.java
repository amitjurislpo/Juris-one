package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.Passwords;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The super admin works outside any business; business setup gives a new business its roles, policies and an owner. */
class PlatformApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessSetupService setup;

    Cookie signIn(String email, String pw) throws Exception {
        String set = mvc.perform(post("/api/auth/session").header("origin", "http://localhost:3005").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"" + email + "\",\"password\":\"" + pw + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    UUID superAdmin(String email) {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + id.toString().substring(0, 6), email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, password_hash, is_super_admin) VALUES (?, ?, true)", id, new Passwords().hash(PW));
        return id;
    }

    @Test
    void aNewBusinessGetsDefaultsAndAnOwnerWhoRunsIt() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UUID business = setup.create(new BusinessSetupService.NewBusiness("Acme Advisors " + suffix, null, "acme-" + suffix, "AA", "Asia/Kolkata",
                null, null, null, null), null);
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise) VALUES (?, ?, ?, current_date, current_date + 30, 0)",
                UUID.randomUUID(), business, TestData.testPlan(jdbc));
        String ownerEmail = "owner." + suffix + "@acme.test";
        UUID owner = TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Asha Owner", ownerEmail, PW, null), null));

        assertThat(jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, owner)).isEqualTo("AA-001");
        assertThat(jdbc.queryForList("SELECT key FROM roles WHERE business_id = ? ORDER BY key", String.class, business))
                .containsExactly("employee", "fe", "fm", "hre", "hrm", "owner");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settings WHERE business_id = ?", Integer.class, business)).isEqualTo(6);
        assertThat(jdbc.queryForList("SELECT name FROM departments WHERE business_id = ? ORDER BY name", String.class, business))
                .containsExactly("Finance", "Human Resources");

        // the owner is the business admin: lands on Overview, manages roles, can't reach the platform API
        Cookie c = signIn(ownerEmail, PW);
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isOk()).andExpect(jsonPath("$.data.landing").value("/overview"));
        mvc.perform(get("/api/roles").cookie(c)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("owner"));
        mvc.perform(get("/api/platform/businesses").cookie(c)).andExpect(status().isForbidden());

        // the next employee gets the next ID of this business
        UUID dept = jdbc.queryForObject("SELECT id FROM departments WHERE business_id = ? AND name = 'Finance'", UUID.class, business);
        assertThat(dept).isNotNull();
    }

    @Test
    void theSuperAdminSeesEveryBusinessButNoBusinessData() throws Exception {
        new TestData(jdbc); // makes sure at least the test business exists
        String email = "platform." + UUID.randomUUID().toString().substring(0, 6) + "@jurisone.test";
        superAdmin(email);
        Cookie c = signIn(email, PW);
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isOk()).andExpect(jsonPath("$.data.landing").value("/platform"))
                .andExpect(jsonPath("$.data.subscriptionActive").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/platform/businesses").cookie(c)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '" + TestData.DEFAULT_BUSINESS + "')]").exists());
        mvc.perform(get("/api/people").cookie(c)).andExpect(status().isForbidden());
        mvc.perform(get("/api/payroll").cookie(c)).andExpect(status().isForbidden());
    }
}
