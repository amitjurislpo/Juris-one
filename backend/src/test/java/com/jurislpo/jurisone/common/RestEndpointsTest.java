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

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
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

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    static int seq = 9500;

    record Person(UUID id, Cookie cookie) {}

    Person person(UUID role) throws Exception {
        String c = "JC-" + (++seq) + (int) (Math.random() * 1000);
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role, PW);
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + c.toLowerCase() + "@example.com\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, new Cookie("jo_at", m.group(1)));
    }

    @Test
    void sessionsAreCreatedAndDeleted() throws Exception {
        Person p = person(empRole);
        mvc.perform(get("/api/me").cookie(p.cookie())).andExpect(status().isOk());
        mvc.perform(write(delete("/api/auth/session")).cookie(p.cookie())).andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(get("/api/me").cookie(p.cookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void notificationsArePatchedAndOldVerbPathsAreGone() throws Exception {
        Person p = person(empRole);
        mvc.perform(write(patch("/api/notifications")).cookie(p.cookie()).content("{\"read\":true}")).andExpect(status().isOk());
        mvc.perform(write(patch("/api/notifications")).cookie(p.cookie()).content("{\"read\":false}")).andExpect(status().isBadRequest());
        // reads are GETs now; the old POST answers 405 with Allow
        mvc.perform(write(post("/api/leave/preview")).cookie(p.cookie()).content("{}"))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.detail").value("Method not allowed."));
        mvc.perform(write(post("/api/notifications/read")).cookie(p.cookie())).andExpect(status().isMethodNotAllowed()); // now /{id}, PATCH only
        mvc.perform(write(post("/api/approvals/leave")).cookie(p.cookie()).content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void payrollAdjustmentsAndStatusAreResources() throws Exception {
        Person fin = person(finRole), emp = person(empRole);
        // closing needs {"status":"CLOSED"} and pay.close
        mvc.perform(write(put("/api/payroll/2026-09/status")).cookie(fin.cookie()).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Invalid option: expected \"CLOSED\""));
        mvc.perform(write(put("/api/payroll/2026-09/status")).cookie(emp.cookie()).content("{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        // the employee id comes from the path
        mvc.perform(write(put("/api/payroll/2026-09/adjustments/not-a-uuid")).cookie(fin.cookie()).content("{\"lopDays\":null,\"bonus\":0,\"otherDeduction\":0,\"note\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(delete("/api/payroll/2026-09/adjustments/" + emp.id())).cookie(emp.cookie())).andExpect(status().isForbidden());

        // a new salary structure is created → 201
        mvc.perform(write(post("/api/structures")).cookie(fin.cookie()).content("{\"employeeId\":\"" + emp.id() + "\",\"effectiveFrom\":\"2031-04-01\",\"reason\":\"APPRAISAL\","
                + "\"basic\":50000,\"hra\":20000,\"lta\":5000,\"special\":25000}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM salary_structures WHERE employee_id = ?", Integer.class, emp.id())).isEqualTo(1);
    }

    @Test
    void peopleSubResources() throws Exception {
        Person hr = person(hrRole);
        String nobody = UUID.randomUUID().toString();
        mvc.perform(get("/api/people/" + nobody + "/contact").cookie(hr.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't view this person's contact details."));
        mvc.perform(write(post("/api/people/" + nobody + "/invitations")).cookie(hr.cookie()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't send an invite for this person."));
        mvc.perform(write(put("/api/people/" + nobody + "/exit")).cookie(hr.cookie()).content("{\"exitDate\":\"2031-01-01\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("You can't change this person's record."));
        mvc.perform(get("/api/people/form-options").cookie(hr.cookie())).andExpect(status().isUnprocessableEntity()); // this role may assign no roles
        mvc.perform(write(post("/api/people/" + nobody + "/reveal-contact")).cookie(hr.cookie())).andExpect(status().isNotFound());
    }

    @Test
    void assignableRolesAreGrantedAndRevoked() throws Exception {
        Person admin = person(adminRole);
        UUID role = data.role("rest-custom-" + UUID.randomUUID().toString().substring(0, 6), "self");
        mvc.perform(write(put("/api/roles/" + role + "/assignable-roles/" + empRole)).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("can now add")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", Integer.class, role, empRole)).isEqualTo(1);
        mvc.perform(write(delete("/api/roles/" + role + "/assignable-roles/" + empRole)).cookie(admin.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("can no longer add")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", Integer.class, role, empRole)).isZero();
    }
}
