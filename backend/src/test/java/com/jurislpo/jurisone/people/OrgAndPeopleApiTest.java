package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Roles, departments, holidays and people through the HTTP API with the seeded role rules. */
class OrgAndPeopleApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID adminRole, hrmRole, empRole, finRole, shiftIn, shiftUs;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        adminRole = data.role("admin", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage",
                "holidays.manage", "pay.view", "pay.close", "struct.manage", "tax.view", "proofs.verify", "roles.manage");
        hrmRole = data.role("hrm", "self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage");
        empRole = data.role("employee", "self", "dir");
        finRole = data.role("fm", "self", "dir", "people.view", "struct.manage", "pay.view");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrmRole, empRole);
        jdbc.update("UPDATE roles SET is_locked = true WHERE id = ?", adminRole);
        shiftIn = shift("IN_NIGHT", "Night", "19:30", "04:30", "IN");
        shift("IN_DAY", "Day", "09:30", "18:30", "IN");
        shiftUs = shift("US_DAY", "US day", "09:00", "18:00", "US");
    }

    UUID shift(String code, String name, String start, String end, String region) {
        UUID id = jdbc.query("SELECT id FROM shifts WHERE code = ?", rs -> rs.next() ? (UUID) rs.getObject(1) : null, code);
        if (id != null) return id;
        id = UUID.randomUUID();
        jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) VALUES (?, ?, ?, ?, ?, ?::\"Region\")", id, code, name, start, end, region);
        return id;
    }

    static int seq = 1000;

    String code() {
        return "JC-" + (++seq) + (int) (Math.random() * 1000);
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie login(String code) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + code + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    Cookie as(UUID role) throws Exception {
        String c = code();
        data.employee(c, c.toLowerCase() + "@example.com", role, PW);
        return login(c);
    }

    UUID department(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    @Test
    void rolesPermissionToggleIsAuditedAndTheAdminRoleIsLocked() throws Exception {
        Cookie admin = as(adminRole);
        mvc.perform(get("/api/roles").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("admin"));
        mvc.perform(write(put("/api/roles/" + empRole + "/permissions/att.all")).cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.msg").value("Employee: granted “View everyone’s attendance (with time zones)”"));
        mvc.perform(write(delete("/api/roles/" + empRole + "/permissions/att.all")).cookie(admin)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'role.permission_change' AND entity_id = ?", Integer.class, empRole.toString())).isEqualTo(2);
        mvc.perform(write(put("/api/roles/" + adminRole + "/permissions/self")).cookie(admin))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Admin has full access and can't be changed."));
        mvc.perform(write(put("/api/roles/" + empRole + "/permissions/bogus")).cookie(admin))
                .andExpect(jsonPath("$.detail").value("Unknown permission."));

        String created = mvc.perform(write(post("/api/roles")).cookie(admin).content("{\"name\":\"Auditor " + UUID.randomUUID().toString().substring(0, 6) + "\",\"fromRoleId\":\"" + adminRole + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).at("/data/id").asText();
        assertThat(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, id)).doesNotContain("roles.manage").contains("pay.view");
        mvc.perform(write(delete("/api/roles/" + id)).cookie(admin)).andExpect(status().isOk());

        Cookie hr = as(hrmRole);
        mvc.perform(get("/api/roles").cookie(hr)).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("You don't have permission to do that."));
    }

    @Test
    void departmentsAndHolidays() throws Exception {
        Cookie hr = as(hrmRole);
        String name = "Dept " + UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(write(post("/api/departments")).cookie(hr).content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.msg").value("Department “" + name + "” created"));
        mvc.perform(write(post("/api/departments")).cookie(hr).content("{\"name\":\"" + name.toUpperCase() + "\"}")).andExpect(jsonPath("$.detail").value("This department already exists."));
        mvc.perform(get("/api/departments?q=" + name.substring(5)).cookie(hr)).andExpect(jsonPath("$.data.rows[0].name").value(name)).andExpect(jsonPath("$.data.rows[0].n").value(0));

        mvc.perform(write(post("/api/holidays")).cookie(hr).content("{\"date\":\"2031-12-25\",\"name\":\"Christmas\",\"region\":\"ALL\"}"))
                .andExpect(jsonPath("$.msg").value("Christmas added for everyone · Thu, 25 Dec 2031"));
        mvc.perform(write(post("/api/holidays")).cookie(hr).content("{\"date\":\"2031-12-25\",\"name\":\"Other\",\"region\":\"IN\"}"))
                .andExpect(jsonPath("$.detail").value("There's already a holiday on this date for this team (Christmas)."));
        mvc.perform(write(post("/api/holidays")).cookie(hr).content("{\"date\":\"2020-01-10\",\"name\":\"Old\",\"region\":\"IN\"}"))
                .andExpect(jsonPath("$.detail").value("That month's attendance is already locked."));
    }

    @Test
    void hrAddsAnEmployeeAndNeverSeesSalary() throws Exception {
        UUID dept = department("Ops " + UUID.randomUUID().toString().substring(0, 6));
        String financeCode = code();
        UUID finance = data.employee(financeCode, financeCode.toLowerCase() + "@example.com", finRole, PW);
        Cookie hr = as(hrmRole);
        String email = "new." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String body = """
            {"fullName":"Meera Joshi","fatherName":"Prakash Joshi","email":"%s","phone":"+91 98765 43210","location":"IN_IST","pan":"abcde1234f",
             "aadhaar":"2345 6789 0123","uan":"","address":"12 Lake Road, Pune","designation":"Associate","departmentId":"%s","managerId":"%s",
             "doj":"2031-10-12","shift":"IN_DAY","roleId":"%s"}""".formatted(email.toUpperCase(), dept, finance, empRole);
        JsonNode r = json.readTree(mvc.perform(write(post("/api/people")).cookie(hr).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(r.at("/msg").asText()).matches("Meera Joshi added as JC-\\d+ · Employee · salary structure pending with Finance · invite sent");
        String id = r.at("/data/id").asText();

        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("ONBOARDING");
        assertThat(jdbc.queryForObject("SELECT email FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT pan_last4 FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("234F");
        assertThat(jdbc.queryForObject("SELECT pan_enc FROM employees WHERE id = ?::uuid", String.class, id)).startsWith("v1.").doesNotContain("ABCDE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome to JurisOne%'", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/structures'", Integer.class, finance)).isEqualTo(1);
        JsonNode audit = json.readTree(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'employee.create' AND entity_id = ?", String.class, id));
        assertThat(audit.get("dateOfJoining").asText()).isEqualTo("2031-10-12T00:00:00.000Z");
        assertThat(audit.has("panEnc")).isFalse();
        assertThat(audit.get("piiProvided").toString()).isEqualTo("[\"phone\",\"address\",\"pan\",\"aadhaar\"]");

        // HR profile: masked PII, no salary
        mvc.perform(get("/api/people/" + id).cookie(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pan").value("XXXXXX234F")).andExpect(jsonPath("$.data.address").value("•••••, Pune"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"gross\":null"))).andExpect(jsonPath("$.data.invitePending").value(true))
                .andExpect(jsonPath("$.data.canEdit").value(true));
        // finance sees gross (0 = not set)
        mvc.perform(get("/api/people/" + id).cookie(login(financeCode))).andExpect(jsonPath("$.data.gross").value(0));

        // duplicate email, edit, access toggle
        mvc.perform(write(post("/api/people")).cookie(hr).content(body)).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("already uses that email.")));
        mvc.perform(write(put("/api/people/" + id)).cookie(hr).content(body.replace("Associate", "Senior Associate").replace("+91 98765 43210", "")))
                .andExpect(jsonPath("$.msg").value("Saved changes for Meera Joshi"));
        assertThat(jdbc.queryForObject("SELECT phone_last4 FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("3210"); // blank keeps
        mvc.perform(write(put("/api/people/" + id + "/access")).cookie(hr).content("{\"on\":false}")).andExpect(jsonPath("$.msg").value("Meera Joshi can no longer sign in"));

        // export: BOM + CRLF + header, no identity numbers
        mvc.perform(get("/api/people/export?status=All").cookie(hr)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"jurisone-people-")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("﻿Employee ID,Name,Email,Designation,Department,Reporting manager,Manager ID,Location,Time zone,Shift,Role,Status,Portal access,Date of joining,Last working day\r\n")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ABCDE1234F"))));
        Cookie emp = as(empRole);
        mvc.perform(get("/api/people/export").cookie(emp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/directory?q=Meera").cookie(emp)).andExpect(jsonPath("$.data.rows[0].name").value("Meera Joshi"));
    }

    @Test
    void hrCannotGiveRolesOutsideItsList() throws Exception {
        UUID dept = department("Legal " + UUID.randomUUID().toString().substring(0, 6));
        String mc = code();
        UUID manager = data.employee(mc, mc.toLowerCase() + "@example.com", empRole, PW);
        Cookie hr = as(hrmRole);
        String body = """
            {"fullName":"Raj Verma","fatherName":"K Verma","email":"raj.%s@example.com","phone":"+1 555 000 1111","location":"US_ET","pan":"",
             "aadhaar":"","uan":"","address":"","designation":"Analyst","departmentId":"%s","managerId":"%s","doj":"2031-01-01","shift":"US_DAY","roleId":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 6), dept, manager, finRole);
        mvc.perform(write(post("/api/people")).cookie(hr).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("You can't give people the Fm role."));
    }
}
