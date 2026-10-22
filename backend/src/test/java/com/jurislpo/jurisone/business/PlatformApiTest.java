package com.jurislpo.jurisone.business;

import com.jurislpo.jurisone.service.BusinessSetupService;
import com.jurislpo.jurisone.service.PlatformCompanyService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessSetupService setup;
    @Autowired PlatformCompanyService platformCompany;

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    UUID superAdmin(String email) {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
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
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
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
        UUID owner = TenantContext.call(business, () -> setup.addOwner(new BusinessSetupService.NewOwner("Asha Owner", ownerEmail, null), null));

        assertThat(jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, owner)).isEqualTo("AA-001");
        assertThat(jdbc.queryForList("SELECT key FROM roles WHERE business_id = ? ORDER BY key", String.class, business))
                .containsExactly("employee", "fe", "fm", "hre", "hrm", "owner");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM settings WHERE business_id = ?", Integer.class, business)).isEqualTo(7);
        assertThat(jdbc.queryForList("SELECT name FROM departments WHERE business_id = ? ORDER BY name", String.class, business))
                .containsExactly("Finance", "Human Resources");

        // the owner is the business admin: lands on Overview, manages roles, can't reach the platform API
        Cookie cookie = signIn(ownerEmail);
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.landing").value("/overview"));
        mockMvc.perform(get("/api/roles").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("owner"));
        mockMvc.perform(get("/api/platform/businesses").cookie(cookie)).andExpect(status().isForbidden());

        // the next employee gets the next ID of this business
        UUID department = jdbc.queryForObject("SELECT id FROM departments WHERE business_id = ? AND name = 'Finance'", UUID.class, business);
        assertThat(department).isNotNull();
    }

    @Test
    void theSuperAdminSeesEveryBusinessButNoBusinessData() throws Exception {
        new TestData(jdbc); // makes sure at least the test business exists
        String email = "platform." + UUID.randomUUID().toString().substring(0, 6) + "@jurisone.test";
        superAdmin(email);
        Cookie cookie = signIn(email);
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.landing").value("/platform"))
                .andExpect(jsonPath("$.data.subscriptionActive").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/platform/businesses").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == '" + TestData.DEFAULT_BUSINESS + "')]").exists());
        mockMvc.perform(get("/api/people").cookie(cookie)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payroll").cookie(cookie)).andExpect(status().isForbidden());
    }

    @Test
    void theSuperAdminRunsThePlatformCompanyWhichCustomersNeverSee() throws Exception {
        String customerCode = "customer-" + TestData.letters(6);
        TestData customer = new TestData(jdbc).inBusiness(customerCode);
        UUID customerEmp = customer.employee("PC-" + TestData.letters(4).toUpperCase(), TestData.letters(8) + "@customer.test", customer.role("employee", "self", "dir"));
        String email = "platform." + TestData.letters(6) + "@jurisone.test";
        UUID superAdminId = superAdmin(email);
        UUID company = platformCompany.ensure();
        assertThat(jdbc.queryForObject("SELECT business_id FROM employees WHERE id = ?", UUID.class, superAdminId)).isEqualTo(company);
        assertThat(platformCompany.ensure()).isEqualTo(company); // idempotent

        Cookie cookie = signIn(email);
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(jsonPath("$.data.landing").value("/platform"))
                .andExpect(jsonPath("$.data.subscriptionActive").value(true)); // the platform company needs no plan
        // the console still works across businesses, and doesn't list the platform company as a customer
        String list = mockMvc.perform(get("/api/platform/businesses").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(customerCode).doesNotContain(company.toString());
        mockMvc.perform(get("/api/platform/businesses/" + company).cookie(cookie)).andExpect(status().isNotFound());
        mockMvc.perform(write(put("/api/platform/businesses/" + company + "/status")).cookie(cookie).content("{\"status\":\"SUSPENDED\"}")).andExpect(status().isNotFound());

        // its own employees: the super admin adds one; customers' people are not there
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        UUID hrDept = jdbc.queryForObject("SELECT id FROM departments WHERE business_id = ? AND name = 'Human Resources'", UUID.class, company);
        UUID hrRole = jdbc.queryForObject("SELECT id FROM roles WHERE business_id = ? AND key = 'hrm'", UUID.class, company);
        String body = """
            {"firstName":"Kiran","lastName":"Rao","fatherName":"M Rao","email":"kiran.%s@jurisone.test","phoneCountry":"+91","phone":"9876501234","location":"",
             "joiningOffice":"Head office","pan":"","aadhaar":"","uan":"","address":"","designation":"HR Manager","departmentId":"%s","managerId":"%s",
             "dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}""".formatted(TestData.letters(5), hrDept, superAdminId, hrRole);
        String created = mockMvc.perform(write(post("/api/people")).cookie(cookie).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(created).contains("JO-");
        String people = mockMvc.perform(get("/api/people").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(people).contains("Kiran Rao").doesNotContain(customerEmp.toString()).doesNotContain(email);
        // and a customer's admin can't reach them
        UUID ownerRole = customer.role("owner", "dir", "roles.manage", "people.view", "people.add");
        String ownerEmail = TestData.letters(8) + "@customer.test";
        customer.employee("PO-" + TestData.letters(4).toUpperCase(), ownerEmail, ownerRole);
        String theirs = mockMvc.perform(get("/api/people?search=Kiran").cookie(signIn(ownerEmail))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(theirs).doesNotContain("Kiran Rao");
    }
}
