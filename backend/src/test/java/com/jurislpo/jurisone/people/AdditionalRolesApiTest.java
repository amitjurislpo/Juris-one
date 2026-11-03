package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
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

/** {@code /api/v1/people/{employeeId}/additional-roles}: more than one role per person. */
class AdditionalRolesApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.jurislpo.jurisone.repository.RolesRepository rolesRepository;
    TestData data;
    UUID ownerRole, hrRole, employeeRole, auditorRole, jurisTrackManagerRole;
    static int sequence = 5000;

    record Person(UUID id, Cookie cookie) {}

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("add-roles");
        ownerRole = data.role("owner", "dir", "people.view", "people.add", "roles.manage", "business.manage");
        hrRole = data.role("hrm", "self", "dir", "people.view", "people.add");
        employeeRole = data.role("employee", "self", "dir");
        auditorRole = data.role("auditor", "att.all");
        jurisTrackManagerRole = data.role("juristrack_manager", "self", "juristrack.manager");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, employeeRole);
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, auditorRole);
    }

    void grantJurisTrack(UUID business) {
        if (jdbc.queryForObject("SELECT count(*) FROM business_modules WHERE business_id = ?", Integer.class, business) == 0) {
            jdbc.update("INSERT INTO business_modules (id, business_id, module, source, starts_on) VALUES (?, ?, 'JURIS_TRACK', 'COMPLIMENTARY', ?)",
                    UUID.randomUUID(), business, Date.valueOf(LocalDate.now().minusDays(1)));
        }
    }

    Person person(TestData inBusiness, UUID role) throws Exception {
        String code = "AR-" + (++sequence) + (int) (Math.random() * 1000);
        String email = code.toLowerCase() + "@example.com";
        UUID id = inBusiness.employee(code, email, role);
        String set = SignIn.session(mockMvc, email).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    String path(UUID employee) {
        return "/api/v1/people/" + employee + "/additional-roles";
    }

    String body(UUID... roleIds) {
        StringBuilder builder = new StringBuilder("{\"roleIds\":[");
        for (int i = 0; i < roleIds.length; i++) builder.append(i == 0 ? "" : ",").append('"').append(roleIds[i]).append('"');
        return builder.append("]}").toString();
    }

    @Test
    void anAdditionalRoleAddsItsPermissionsToTheMainRoleAndRemovingItTakesThemBack() throws Exception {
        Person owner = person(data, ownerRole), worker = person(data, employeeRole);
        mockMvc.perform(get("/api/v1/me").cookie(worker.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.perms").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("att.all"))));

        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(auditorRole))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.additionalRoles[0].key").value("auditor")).andExpect(jsonPath("$.data.mainRole.key").value("employee"));
        // the very next request of the worker carries the union
        mockMvc.perform(get("/api/v1/me").cookie(worker.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.perms").value(org.hamcrest.Matchers.hasItems("att.all", "self", "dir")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.additional_roles' AND entity_id = ?", Integer.class, worker.id().toString())).isEqualTo(1);

        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content("{\"roleIds\":[]}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").cookie(worker.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.perms").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("att.all"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_additional_roles WHERE employee_id = ?", Integer.class, worker.id())).isZero();
    }

    @Test
    void theRolesOfAPersonAreListedWithTheRolesThatCouldBeAdded() throws Exception {
        Person owner = person(data, ownerRole), worker = person(data, employeeRole);
        mockMvc.perform(get(path(worker.id())).cookie(owner.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.canChange").value(true))
                .andExpect(jsonPath("$.data.offeredRoles[?(@.key=='auditor')]").exists())
                .andExpect(jsonPath("$.data.offeredRoles[?(@.key=='owner')]").doesNotExist())
                .andExpect(jsonPath("$.data.offeredRoles[?(@.key=='employee')]").doesNotExist()); // their main role
        // the JurisTrack manager role is not offered to a business without the module
        mockMvc.perform(get(path(worker.id())).cookie(owner.cookie())).andExpect(jsonPath("$.data.offeredRoles[?(@.key=='juristrack_manager')]").doesNotExist());
    }

    @Test
    void theJurisTrackManagerRoleNeedsTheModule() throws Exception {
        TestData withoutModule = new TestData(jdbc).inBusiness("add-roles-no-module");
        UUID owner2 = withoutModule.role("owner", "dir", "people.view", "people.add", "roles.manage", "business.manage");
        UUID employee2 = withoutModule.role("employee", "self", "dir");
        UUID manager2 = withoutModule.role("juristrack_manager", "self", "juristrack.manager");
        Person owner = person(withoutModule, owner2), worker = person(withoutModule, employee2);
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(manager2))).andExpect(status().isUnprocessableEntity());

        grantJurisTrack(withoutModule.business());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(manager2))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.additionalRoles[0].key").value("juristrack_manager"));
        mockMvc.perform(get("/api/v1/me").cookie(worker.cookie())).andExpect(jsonPath("$.data.user.perms").value(org.hamcrest.Matchers.hasItem("juristrack.manager")));
    }

    @Test
    void theJurisTrackManagerRoleCannotBeTheMainRoleWithoutTheModuleEither() throws Exception {
        TestData withoutModule = new TestData(jdbc).inBusiness("add-roles-main");
        UUID owner2 = withoutModule.role("owner", "dir", "people.view", "people.add", "roles.manage", "business.manage");
        UUID manager2 = withoutModule.role("juristrack_manager", "self", "juristrack.manager");
        withoutModule.role("employee", "self", "dir");
        Person owner = person(withoutModule, owner2);
        mockMvc.perform(get("/api/v1/people/form-options").cookie(owner.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles[?(@.id=='" + manager2 + "')]").doesNotExist());
    }

    @Test
    void hostileInputIsRefused() throws Exception {
        Person owner = person(data, ownerRole), worker = person(data, employeeRole), other = person(data, employeeRole);
        // the owner role, the person's own main role, an unknown role, a role of another business, and text that is no id
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(ownerRole))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(employeeRole))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(UUID.randomUUID()))).andExpect(status().isUnprocessableEntity());
        TestData stranger = new TestData(jdbc).inBusiness("add-roles-stranger");
        UUID foreignRole = stranger.role("auditor", "att.all");
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(foreignRole))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content("{\"roleIds\":[\"1'; DROP TABLE roles; --\"]}")).andExpect(status().is4xxClientError());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content("{\"roleIds\":\"nope\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content("")).andExpect(status().is4xxClientError());
        // too many
        UUID[] many = new UUID[6];
        for (int i = 0; i < many.length; i++) many[i] = UUID.randomUUID();
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(many))).andExpect(status().isBadRequest());
        // not an id in the path
        mockMvc.perform(write(put("/api/v1/people/not-an-id/additional-roles")).cookie(owner.cookie()).content(body())).andExpect(status().is4xxClientError());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_additional_roles WHERE employee_id IN (?, ?)", Integer.class, worker.id(), other.id())).isZero();
    }

    @Test
    void whoMayChangeRolesAndOnWhomIsChecked() throws Exception {
        Person owner = person(data, ownerRole), hr = person(data, hrRole), worker = person(data, employeeRole), plain = person(data, employeeRole);
        // an employee with no people permission: forbidden, signed out: 401
        mockMvc.perform(write(put(path(worker.id()))).cookie(plain.cookie()).content(body(auditorRole))).andExpect(status().isForbidden());
        mockMvc.perform(get(path(worker.id())).cookie(plain.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(write(put(path(worker.id()))).content(body(auditorRole))).andExpect(status().isUnauthorized());
        // nobody changes their own roles
        mockMvc.perform(write(put(path(owner.id()))).cookie(owner.cookie()).content(body(auditorRole))).andExpect(status().isForbidden());
        // HR hands out what HR may assign (the auditor role) and also the JurisTrack manager role; but not a role outside HR's list
        grantJurisTrack(data.business());
        UUID financeRole = data.role("finance", "pay.view");
        mockMvc.perform(write(put(path(worker.id()))).cookie(hr.cookie()).content(body(financeRole))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put(path(worker.id()))).cookie(hr.cookie()).content(body(jurisTrackManagerRole, auditorRole))).andExpect(status().isOk());
        // never for themselves, and an employee without people.add cannot do it
        mockMvc.perform(write(put(path(hr.id()))).cookie(hr.cookie()).content(body(jurisTrackManagerRole))).andExpect(status().isForbidden());
        mockMvc.perform(write(put(path(worker.id()))).cookie(plain.cookie()).content(body(jurisTrackManagerRole))).andExpect(status().isForbidden());
        // a past employee's roles are frozen
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE id = ?", plain.id());
        mockMvc.perform(write(put(path(plain.id()))).cookie(owner.cookie()).content(body(auditorRole))).andExpect(status().isForbidden());
    }

    @Test
    void anotherBusinessesPersonIsNotFound() throws Exception {
        Person owner = person(data, ownerRole);
        TestData stranger = new TestData(jdbc).inBusiness("add-roles-other");
        UUID strangerEmployee = stranger.employee("AR-X" + (++sequence), "x" + sequence + "@example.com", stranger.role("employee", "self", "dir"));
        mockMvc.perform(get(path(strangerEmployee)).cookie(owner.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(put(path(strangerEmployee))).cookie(owner.cookie()).content(body(auditorRole))).andExpect(status().isNotFound());
        mockMvc.perform(write(put(path(UUID.randomUUID()))).cookie(owner.cookie()).content(body(auditorRole))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_additional_roles WHERE employee_id = ?", Integer.class, strangerEmployee)).isZero();
    }

    @Test
    void aRoleStillHeldAsAnAdditionalRoleIsCountedSoItCannotBeDeleted() throws Exception {
        Person owner = person(data, ownerRole), worker = person(data, employeeRole);
        UUID extra = data.role("extra-role", "att.all");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, extra);
        mockMvc.perform(write(put(path(worker.id()))).cookie(owner.cookie()).content(body(extra))).andExpect(status().isOk());
        // the roles page counts it, so the super admin cannot delete a role somebody still holds as an additional one
        assertThat(rolesRepository.countEmployees(extra)).isEqualTo(1);
    }
}
