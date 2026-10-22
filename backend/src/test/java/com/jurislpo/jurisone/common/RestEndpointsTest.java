package com.jurislpo.jurisone.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** REST conventions: resource paths, methods and status codes (MIGRATION_PLAN §14). */
class RestEndpointsTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID empRole, finRole, hrRole, adminRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        empRole = data.role("rest-employee", "self", "dir");
        finRole = data.role("rest-finance", "self", "dir", "people.view", "pay.view", "pay.close", "struct.manage");
        hrRole = data.role("rest-hr", "self", "dir", "people.view", "people.add");
        adminRole = data.role("rest-admin", "dir", "roles.manage", "people.view");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int sequence = 9500;

    record Person(UUID id, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String code = "JC-" + (++sequence) + (int) (Math.random() * 1000);
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com")
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    @Test
    void sessionsAreCreatedAndDeleted() throws Exception {
        Person person = person(empRole);
        mockMvc.perform(get("/api/me").cookie(person.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(delete("/api/auth/session")).cookie(person.cookie())).andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(get("/api/me").cookie(person.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void notificationsArePatchedAndOldVerbPathsAreGone() throws Exception {
        Person person = person(empRole);
        mockMvc.perform(write(patch("/api/notifications")).cookie(person.cookie()).content("{\"read\":true}")).andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/notifications")).cookie(person.cookie()).content("{\"read\":false}")).andExpect(status().isBadRequest());
        // reads are GETs now; the old POST answers 405 with Allow
        mockMvc.perform(write(post("/api/leave/previews")).cookie(person.cookie()).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.detail").value("Method not allowed."));
        mockMvc.perform(write(post("/api/notifications/read")).cookie(person.cookie())).andExpect(status().isMethodNotAllowed()); // now /{id}, PATCH only
        mockMvc.perform(write(post("/api/approvals/leave")).cookie(person.cookie()).content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void payrollAdjustmentsAndStatusAreResources() throws Exception {
        Person finance = person(finRole), employee = person(empRole);
        // closing needs {"status":"CLOSED"} and pay.close
        mockMvc.perform(write(put("/api/payroll/2026-09/status")).cookie(finance.cookie()).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid option: expected \"CLOSED\""));
        mockMvc.perform(write(put("/api/payroll/2026-09/status")).cookie(employee.cookie()).content("{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        // the employee id comes from the path
        mockMvc.perform(write(put("/api/payroll/2026-09/adjustments/not-a-uuid")).cookie(finance.cookie()).content("{\"lopDays\":null,\"bonus\":0,\"otherDeduction\":0,\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(write(delete("/api/payroll/2026-09/adjustments/" + employee.id())).cookie(employee.cookie())).andExpect(status().isForbidden());

        // a new salary structure is created → 201
        mockMvc.perform(write(post("/api/structures")).cookie(finance.cookie()).content("{\"employeeId\":\"" + employee.id() + "\",\"effectiveFrom\":\"2031-04-01\",\"reason\":\"APPRAISAL\","
                + "\"basic\":50000,\"hra\":20000,\"lta\":5000,\"special\":25000}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salary_structures WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(1);
    }

    @Test
    void peopleSubResources() throws Exception {
        Person hrPerson = person(hrRole);
        String nobody = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/people/" + nobody + "/contact").cookie(hrPerson.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't view this person's contact details."));
        mockMvc.perform(write(put("/api/people/" + nobody + "/status")).cookie(hrPerson.cookie()).content("{\"status\":\"EXITED\",\"exitDate\":\"2031-01-01\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't change this person's record."));
        mockMvc.perform(get("/api/people/form-options").cookie(hrPerson.cookie())).andExpect(status().isUnprocessableEntity()); // this role may assign no roles
        mockMvc.perform(write(post("/api/people/" + nobody + "/reveal-contact")).cookie(hrPerson.cookie())).andExpect(status().isNotFound());
    }

    @Test
    void assignableRolesAreGrantedAndRevoked() throws Exception {
        Person admin = person(data.role("rest-super", "dir", "roles.manage", "platform.manage", "people.view")); // roles are changed by the platform super admin only
        UUID role = data.role("rest-custom-" + UUID.randomUUID().toString().substring(0, 6), "self");
        mockMvc.perform(write(put("/api/roles/" + role + "/assignable-roles/" + empRole)).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("can now add")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", Integer.class, role, empRole)).isEqualTo(1);
        mockMvc.perform(write(delete("/api/roles/" + role + "/assignable-roles/" + empRole)).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("can no longer add")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", Integer.class, role, empRole)).isZero();
        // a business admin (roles.manage without platform.manage) can look but not change
        Person owner = person(adminRole);
        mockMvc.perform(write(put("/api/roles/" + role + "/assignable-roles/" + empRole)).cookie(owner.cookie())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Roles are set up for your business by JurisOne."));
    }
}
