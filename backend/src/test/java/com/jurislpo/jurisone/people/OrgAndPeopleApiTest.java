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

/** Roles, departments, holidays and people through the HTTP API with the seeded role rules. */
class OrgAndPeopleApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID adminRole, hrmRole, empRole, finRole, shiftIn, shiftUs;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        adminRole = data.role("owner", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage",
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
        UUID id = jdbc.query("SELECT id FROM shifts WHERE code = ?", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null, code);
        if (id != null) return id;
        id = UUID.randomUUID();
        jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) VALUES (?, ?, ?, ?, ?, ?::\"Region\")", id, code, name, start, end, region);
        return id;
    }

    static int sequence = 1000;

    String code() {
        return "JC-" + (++sequence) + (int) (Math.random() * 1000);
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    /** Signs in the test employee with this code (its email is {@code <code>@example.com}). */
    Cookie login(String code) throws Exception {
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com")
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    Cookie cookieForRole(UUID role) throws Exception {
        String code = code();
        data.employee(code, code.toLowerCase() + "@example.com", role);
        return login(code);
    }

    UUID department(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", id, name, data.business());
        return id;
    }

    @Test
    void rolesPermissionToggleIsAuditedAndTheAdminRoleIsLocked() throws Exception {
        Cookie owner = cookieForRole(adminRole);
        // the business owner can look at the roles but not change them: roles are set up by the platform super admin
        mockMvc.perform(get("/api/roles").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].key").value("owner")).andExpect(jsonPath("$.data[0].canEdit").value(false));
        mockMvc.perform(write(put("/api/roles/" + empRole + "/permissions/att.all")).cookie(owner)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Roles are set up for your business by JurisOne."));
        mockMvc.perform(write(post("/api/roles")).cookie(owner).content("{\"name\":\"Nope\",\"fromRoleId\":\"" + adminRole + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/roles/" + empRole)).cookie(owner)).andExpect(status().isForbidden());
        Cookie admin = cookieForRole(data.role("sup", "dir", "roles.manage", "platform.manage", "people.view"));
        mockMvc.perform(get("/api/roles").cookie(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].canEdit").value(true));
        mockMvc.perform(write(put("/api/roles/" + empRole + "/permissions/att.all")).cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Employee: granted “View everyone’s attendance (with time zones)”"));
        mockMvc.perform(write(delete("/api/roles/" + empRole + "/permissions/att.all")).cookie(admin)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'role.permission_change' AND entity_id = ?", Integer.class, empRole.toString())).isEqualTo(2);
        mockMvc.perform(write(put("/api/roles/" + adminRole + "/permissions/self")).cookie(admin))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Owner has full access and can't be changed."));
        mockMvc.perform(write(put("/api/roles/" + empRole + "/permissions/bogus")).cookie(admin))
                .andExpect(jsonPath("$.detail").value("Unknown permission."));

        String created = mockMvc.perform(write(post("/api/roles")).cookie(admin).content("{\"name\":\"Auditor " + UUID.randomUUID().toString().substring(0, 6) + "\",\"fromRoleId\":\"" + adminRole + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).at("/data/id").asText();
        assertThat(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, id)).doesNotContain("roles.manage").contains("pay.view");
        mockMvc.perform(write(delete("/api/roles/" + id)).cookie(admin)).andExpect(status().isOk());

        Cookie hrCookie = cookieForRole(hrmRole);
        mockMvc.perform(get("/api/roles").cookie(hrCookie)).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("You don't have permission to do that."));
    }

    @Test
    void departmentsAndHolidays() throws Exception {
        Cookie hrCookie = cookieForRole(hrmRole);
        String name = "Dept " + UUID.randomUUID().toString().substring(0, 6);
        mockMvc.perform(write(post("/api/departments")).cookie(hrCookie).content("{\"name\":\"" + name + "\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Department “" + name + "” created"));
        mockMvc.perform(write(post("/api/departments")).cookie(hrCookie).content("{\"name\":\"" + name.toUpperCase() + "\"}")).andExpect(jsonPath("$.detail").value("This department already exists."));
        mockMvc.perform(get("/api/departments?search=" + name.substring(5)).cookie(hrCookie)).andExpect(jsonPath("$.data.rows[0].name").value(name)).andExpect(jsonPath("$.data.rows[0].employeeCount").value(0));

        mockMvc.perform(write(post("/api/holidays")).cookie(hrCookie).content("{\"date\":\"2031-12-25\",\"name\":\"Christmas\",\"region\":\"ALL\"}"))
                .andExpect(jsonPath("$.message").value("Christmas added for everyone · Thu, 25 Dec 2031"));
        mockMvc.perform(write(post("/api/holidays")).cookie(hrCookie).content("{\"date\":\"2031-12-25\",\"name\":\"Other\",\"region\":\"IN\"}"))
                .andExpect(jsonPath("$.detail").value("There's already a holiday on this date for this team (Christmas)."));
        mockMvc.perform(write(post("/api/holidays")).cookie(hrCookie).content("{\"date\":\"2020-01-10\",\"name\":\"Old\",\"region\":\"IN\"}"))
                .andExpect(jsonPath("$.detail").value("That month's attendance is already locked."));
    }

    @Test
    void hrAddsAnEmployeeAndNeverSeesSalary() throws Exception {
        UUID department = department("Ops " + UUID.randomUUID().toString().substring(0, 6));
        String financeCode = code();
        UUID finance = data.employee(financeCode, financeCode.toLowerCase() + "@example.com", finRole);
        Cookie hrCookie = cookieForRole(hrmRole);
        String email = "new." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String body = """
            {"firstName":"Meera","middleName":"","lastName":"Joshi","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","pan":"abcde1234f",
             "aadhaar":"2345 6789 0123","uan":"","address":"12 Lake Road, Pune","designation":"Associate","departmentId":"%s","managerId":"%s",
             "dateOfJoining":"2031-10-12","shift":"IN_DAY","roleId":"%s"}""".formatted(email.toUpperCase(), department, finance, empRole);
        JsonNode saved = json.readTree(mockMvc.perform(write(post("/api/people")).cookie(hrCookie).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(saved.at("/message").asText()).matches("Meera Joshi added as TE-\\d+ · Employee · salary structure pending with Finance · they sign in with the code emailed to their work email");
        String id = saved.at("/data/id").asText();

        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("ONBOARDING");
        // onboarding is not required by default (immediate access); the list row says where it stands
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, id)).isEqualTo("NOT_REQUIRED");
        mockMvc.perform(get("/api/people?search=Meera").cookie(hrCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.id=='" + id + "')].onboardingStatus").value(org.hamcrest.Matchers.contains("NOT_REQUIRED")));
        // the blank add form does not ask for onboarding either
        mockMvc.perform(get("/api/people/form-options").cookie(hrCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.defaults.isOnboardingRequired").value(false));
        // HR can ask for onboarding when adding
        String requiredEmail = "required." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        String requiredId = json.readTree(mockMvc.perform(write(post("/api/people")).cookie(hrCookie)
                .content(body.replace(email.toUpperCase(), requiredEmail).replace("\"roleId\"", "\"isOnboardingRequired\":true,\"roleId\""))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/data/id").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, requiredId)).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT email FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT pan_last4 FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("234F");
        assertThat(jdbc.queryForObject("SELECT pan_enc FROM employees WHERE id = ?::uuid", String.class, id)).startsWith("v1.").doesNotContain("ABCDE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome to JurisOne%'", Integer.class, email)).isZero();
        // one per added employee: the first and the one added with onboarding switched off
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND link = '/structures'", Integer.class, finance)).isEqualTo(2);
        JsonNode audit = json.readTree(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'employee.create' AND entity_id = ?", String.class, id));
        assertThat(audit.get("dateOfJoining").asText()).isEqualTo("2031-10-12T00:00:00.000Z");
        assertThat(audit.has("panEnc")).isFalse();
        assertThat(audit.get("piiProvided").toString()).isEqualTo("[\"phone\",\"address\",\"pan\",\"aadhaar\"]");

        // HR profile: masked PII, no salary
        mockMvc.perform(get("/api/people/" + id).cookie(hrCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pan").value("XXXXXX234F")).andExpect(jsonPath("$.data.address").value("•••••, Pune"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"gross\":null")))
                .andExpect(jsonPath("$.data.canEdit").value(true));
        // finance sees gross (0 = not set)
        mockMvc.perform(get("/api/people/" + id).cookie(login(financeCode))).andExpect(jsonPath("$.data.gross").value(0));

        // duplicate email, edit, access toggle
        mockMvc.perform(write(post("/api/people")).cookie(hrCookie).content(body)).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("already uses that email.")));
        mockMvc.perform(write(put("/api/people/" + id)).cookie(hrCookie).content(body.replace("Associate", "Senior Associate").replace("+91 98765 43210", "")))
                .andExpect(jsonPath("$.message").value("Saved changes for Meera Joshi"));
        assertThat(jdbc.queryForObject("SELECT phone_last4 FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("3210"); // blank keeps
        mockMvc.perform(write(put("/api/people/" + id + "/access")).cookie(hrCookie).content("{\"enabled\":false}")).andExpect(jsonPath("$.message").value("Meera Joshi can no longer sign in"));

        // export: BOM + CRLF + header, no identity numbers
        mockMvc.perform(get("/api/people/export?status=All").cookie(hrCookie)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"jurisone-people-")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("﻿Employee ID,Name,Email,Designation,Department,Reporting manager,Manager ID,Location,Time zone,Shift,Role,Status,Portal access,Date of joining,Last working day\r\n")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ABCDE1234F"))));
        Cookie employee = cookieForRole(empRole);
        mockMvc.perform(get("/api/people/export").cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/directory?search=Meera").cookie(employee)).andExpect(jsonPath("$.data.rows[0].name").value("Meera Joshi"));
    }

    @Test
    void hrCannotGiveRolesOutsideItsList() throws Exception {
        UUID department = department("Legal " + UUID.randomUUID().toString().substring(0, 6));
        String managerCode = code();
        UUID manager = data.employee(managerCode, managerCode.toLowerCase() + "@example.com", empRole);
        Cookie hrCookie = cookieForRole(hrmRole);
        String body = """
            {"firstName":"Raj","lastName":"Verma","fatherName":"K Verma","email":"raj.%s@example.com","phoneCountry":"+1","phone":"555 000 1111","location":"US_ET","pan":"",
             "aadhaar":"","uan":"","address":"","designation":"Analyst","departmentId":"%s","managerId":"%s","dateOfJoining":"2031-01-01","shift":"US_DAY","roleId":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 6), department, manager, finRole);
        mockMvc.perform(write(post("/api/people")).cookie(hrCookie).content(body)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("You can't give people the Fm role."));
    }

    @Test
    void onlyTheAdminAndHrCanMakeAProbationEmployeePermanent() throws Exception {
        UUID teamLead = data.role("tl", "self", "dir", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", teamLead, empRole);
        Cookie lead = cookieForRole(teamLead), hrCookie = cookieForRole(hrmRole), owner = cookieForRole(adminRole);
        String[] codes = {code(), code(), code()};
        UUID[] ids = new UUID[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = data.employee(codes[i], codes[i].toLowerCase() + "@example.com", empRole);
            jdbc.update("UPDATE employees SET status = 'PROBATION' WHERE id = ?", ids[i]);
        }
        // the profile only offers the button to the admin and HR
        mockMvc.perform(get("/api/people/" + ids[0]).cookie(lead)).andExpect(jsonPath("$.data.canConfirm").value(false));
        mockMvc.perform(get("/api/people/" + ids[0]).cookie(hrCookie)).andExpect(jsonPath("$.data.canConfirm").value(true));
        // someone who can edit people but is neither admin nor HR is refused
        mockMvc.perform(write(put("/api/people/" + ids[0] + "/status")).cookie(lead).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, ids[0])).isEqualTo("PROBATION");
        mockMvc.perform(write(put("/api/people/" + ids[0] + "/status")).cookie(hrCookie).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        mockMvc.perform(write(put("/api/people/" + ids[1] + "/status")).cookie(owner).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, ids[0])).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status::text FROM employees WHERE id = ?", String.class, ids[1])).isEqualTo("ACTIVE");
        // already permanent: nothing left to confirm
        mockMvc.perform(write(put("/api/people/" + ids[0] + "/status")).cookie(hrCookie).content("{\"status\":\"ACTIVE\"}")).andExpect(status().isUnprocessableEntity());
        // changing a role is for the admin and HR only: a team lead editing sees just the current role and can't switch it
        mockMvc.perform(get("/api/people/form-options").param("id", ids[2].toString()).cookie(lead))
                .andExpect(jsonPath("$.data.roles.length()").value(1));
    }
}
