package com.jurislpo.jurisone.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
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

/** The super admin's role templates: what a business gets is decided at onboarding, and the owner can't change roles. */
class RoleTemplatesApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    Cookie admin;
    String suffix;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void superAdmin() throws Exception {
        new TestData(jdbc);
        suffix = UUID.randomUUID().toString().substring(0, 6);
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        String email = "platform." + suffix + "@jurisone.test";
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + suffix, email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        String set = SignIn.session(mvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        admin = new Cookie("jo_at", m.group(1));
    }

    JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    String template(String name, String perms, String assign) throws Exception {
        return body(mvc.perform(write(post("/api/platform/role-templates")).cookie(admin)
                .content("{\"name\":\"" + name + "\",\"permissions\":" + perms + ",\"assignable\":" + assign + "}")).andExpect(status().isCreated())).at("/data/key").asText();
    }

    String business(String roles) throws Exception {
        String code = "rt-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
        return body(mvc.perform(write(post("/api/platform/businesses")).cookie(admin)
                .content("{\"business\":{\"name\":\"Roles Co " + code + "\",\"code\":\"" + code + "\",\"employeePrefix\":\"RC\"},\"roles\":" + roles + "}"))
                .andExpect(status().isCreated())).at("/data/id").asText();
    }

    List<String> roleKeys(String business) {
        return jdbc.queryForList("SELECT key FROM roles WHERE business_id = ?::uuid ORDER BY key", String.class, business);
    }

    @Test
    void theCatalogueIsSeededAndOnlyTheSuperAdminSeesIt() throws Exception {
        JsonNode d = body(mvc.perform(get("/api/platform/role-templates").cookie(admin)).andExpect(status().isOk())).get("data");
        List<String> keys = new java.util.ArrayList<>();
        d.get("templates").forEach(t -> keys.add(t.get("key").asText()));
        assertThat(keys).contains("owner", "hrm", "hre", "fm", "fe", "employee");
        for (JsonNode t : d.get("templates")) {
            boolean mandatory = List.of("owner", "hrm", "fm", "employee").contains(t.get("key").asText());
            if (List.of("owner", "hrm", "hre", "fm", "fe", "employee").contains(t.get("key").asText())) assertThat(t.get("mandatory").asBoolean()).isEqualTo(mandatory);
        }
        assertThat(d.get("groups").size()).isEqualTo(4);
        assertThat(d.get("groups").toString()).contains("people.add").contains("Add & edit employees").doesNotContain("business.manage");

        // a business user can't reach it
        UUID role = new TestData(jdbc).role("rt-plain", "dir", "roles.manage");
        UUID e = UUID.randomUUID();
        String code = "RTP-" + suffix, email = code.toLowerCase() + "@example.com";
        new TestData(jdbc).employee(code, email, role);
        String set = SignIn.session(mvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        mvc.perform(get("/api/platform/role-templates").cookie(new Cookie("jo_at", m.group(1)))).andExpect(status().isForbidden());
    }

    @Test
    void templateRulesAreEnforced() throws Exception {
        String name = "Team Lead " + suffix;
        String key = template(name, "[\"self\",\"dir\",\"people.view\"]", "[\"employee\"]");
        assertThat(key).startsWith("team_lead_");
        mvc.perform(write(post("/api/platform/role-templates")).cookie(admin).content("{\"name\":\"" + name.toUpperCase() + "\",\"permissions\":[],\"assignable\":[]}"))
                .andExpect(status().isConflict());
        mvc.perform(write(post("/api/platform/role-templates")).cookie(admin).content("{\"name\":\"Bogus " + suffix + "\",\"permissions\":[\"nope\"],\"assignable\":[]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Unknown permission: nope."));
        mvc.perform(write(post("/api/platform/role-templates")).cookie(admin).content("{\"name\":\"Business.manage " + suffix + "\",\"permissions\":[\"business.manage\"],\"assignable\":[]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(post("/api/platform/role-templates")).cookie(admin).content("{\"name\":\"X " + suffix + "\",\"permissions\":[],\"assignable\":[\"owner\"]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("The Owner role can't be handed out."));
        // HR roles never hold salary / tax permissions
        mvc.perform(write(post("/api/platform/role-templates")).cookie(admin).content("{\"name\":\"HR Intern " + suffix + "\",\"permissions\":[\"self\",\"pay.view\"],\"assignable\":[]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("HR roles can't hold salary, payroll or tax permissions."));
        mvc.perform(write(put("/api/platform/role-templates/hrm")).cookie(admin).content("{\"name\":\"HR Manager\",\"permissions\":[\"self\",\"tax.view\"],\"assignable\":[]}"))
                .andExpect(status().isUnprocessableEntity());

        // the owner is fixed; mandatory ones can't be switched off or deleted
        mvc.perform(write(put("/api/platform/role-templates/owner")).cookie(admin).content("{\"name\":\"Owner\",\"permissions\":[\"dir\"],\"assignable\":[]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Owner is fixed and can't be changed."));
        mvc.perform(write(put("/api/platform/role-templates/fm")).cookie(admin).content("{\"name\":\"Finance Manager\",\"permissions\":[\"self\"],\"assignable\":[],\"active\":false}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Finance Manager is part of every business and can't be switched off."));
        mvc.perform(write(delete("/api/platform/role-templates/employee")).cookie(admin)).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(delete("/api/platform/role-templates/nothing")).cookie(admin)).andExpect(status().isNotFound());

        // a template that no business uses can be edited, switched off and deleted; one in use can't be deleted
        mvc.perform(write(put("/api/platform/role-templates/" + key)).cookie(admin)
                .content("{\"name\":\"" + name + "\",\"permissions\":[\"self\",\"dir\",\"people.view\",\"att.all\"],\"assignable\":[],\"active\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT unnest(permissions) FROM role_templates WHERE key = ?", String.class, key)).contains("att.all");
        String biz = business("[\"" + key + "\"]");
        mvc.perform(write(delete("/api/platform/role-templates/" + key)).cookie(admin)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(name + " is used by 1 business")));
        assertThat(biz).isNotBlank();
        String spare = template("Spare " + suffix, "[\"self\"]", "[]");
        mvc.perform(write(delete("/api/platform/role-templates/" + spare)).cookie(admin)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_templates WHERE key = ?", Integer.class, spare)).isZero();
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY at", String.class, spare)).contains("role_template.create", "role_template.delete");
    }

    @Test
    void aNewBusinessGetsTheMandatoryRolesPlusTheChosenOnes() throws Exception {
        String lead = template("Site Lead " + suffix, "[\"self\",\"dir\",\"appr.leave\"]", "[\"employee\"]");
        String biz = business("[\"hre\",\"" + lead + "\"]");
        assertThat(roleKeys(biz)).containsExactlyInAnyOrder("employee", "fm", "hre", "hrm", "owner", lead); // no Finance Executive: it wasn't chosen
        // nothing chosen: only the mandatory ones
        assertThat(roleKeys(business("[]"))).containsExactlyInAnyOrder("employee", "fm", "hrm", "owner");

        // a role is a copy of the template with its assignable roles linked inside the business
        assertThat(jdbc.queryForList("SELECT permission_key FROM role_permissions rp JOIN roles r ON r.id = rp.role_id WHERE r.business_id = ?::uuid AND r.key = ?", String.class, biz, lead))
                .containsExactlyInAnyOrder("self", "dir", "appr.leave");
        assertThat(jdbc.queryForList("""
            SELECT a.key FROM role_assignable_roles x JOIN roles r ON r.id = x.role_id JOIN roles a ON a.id = x.assignable_role_id
            WHERE r.business_id = ?::uuid AND r.key = 'hrm' ORDER BY a.key""", String.class, biz)).containsExactly("employee", "hre");

        // unknown or switched-off keys are refused and no business is created
        long before = jdbc.queryForObject("SELECT count(*) FROM businesses", Long.class);
        String code = "rt-bad-" + suffix;
        mvc.perform(write(post("/api/platform/businesses")).cookie(admin).content("{\"business\":{\"name\":\"Bad Roles " + suffix + "\",\"code\":\"" + code + "\"},\"roles\":[\"ghost\"]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Unknown or switched-off role: ghost."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses", Long.class)).isEqualTo(before);

        // the detail lists the roles with who holds them and which are mandatory
        JsonNode roles = body(mvc.perform(get("/api/platform/businesses/" + biz).cookie(admin)).andExpect(status().isOk())).at("/data/roles");
        assertThat(roles.size()).isEqualTo(6);
        for (JsonNode r : roles) {
            assertThat(r.get("employees").asLong()).isZero();
            assertThat(r.get("mandatory").asBoolean()).isEqualTo(List.of("owner", "hrm", "fm", "employee").contains(r.get("key").asText()));
        }
    }

    @Test
    void rolesCanBeAddedRemovedAndSyncedOnAnExistingBusiness() throws Exception {
        String lead = template("Desk Lead " + suffix, "[\"self\",\"dir\"]", "[]");
        String biz = business("[]");
        assertThat(roleKeys(biz)).doesNotContain("fe", lead);

        // add: Finance Executive joins, and Finance Manager (whose template lets it add one) is linked to it
        String added = body(mvc.perform(write(post("/api/platform/businesses/" + biz + "/roles")).cookie(admin).content("{\"templateKey\":\"fe\"}"))
                .andExpect(status().isCreated())).at("/data/id").asText();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM role_assignable_roles x JOIN roles r ON r.id = x.role_id WHERE r.business_id = ?::uuid AND r.key = 'fm' AND x.assignable_role_id = ?::uuid""",
                Integer.class, biz, added)).isEqualTo(1);
        mvc.perform(write(post("/api/platform/businesses/" + biz + "/roles")).cookie(admin).content("{\"templateKey\":\"fe\"}")).andExpect(status().isConflict());
        mvc.perform(write(post("/api/platform/businesses/" + biz + "/roles")).cookie(admin).content("{\"templateKey\":\"ghost\"}")).andExpect(status().isUnprocessableEntity());

        // remove: not a mandatory role, and not while anyone holds it
        mvc.perform(write(delete("/api/platform/businesses/" + biz + "/roles/" + added)).cookie(admin)).andExpect(status().isOk());
        assertThat(roleKeys(biz)).doesNotContain("fe");
        String mandatory = jdbc.queryForObject("SELECT id::text FROM roles WHERE business_id = ?::uuid AND key = 'hrm'", String.class, biz);
        mvc.perform(write(delete("/api/platform/businesses/" + biz + "/roles/" + mandatory)).cookie(admin)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("HR Manager is part of every business and can't be removed."));
        String leadId = body(mvc.perform(write(post("/api/platform/businesses/" + biz + "/roles")).cookie(admin).content("{\"templateKey\":\"" + lead + "\"}"))
                .andExpect(status().isCreated())).at("/data/id").asText();
        jdbc.update("INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at) "
                + "VALUES (gen_random_uuid(), ?::uuid, 'RC-900', 'Held Lead', ?, 'Lead', ?::uuid, current_date, 'ACTIVE', 'Asia/Kolkata', 'IN', now())", biz, "held." + suffix + "@example.com", leadId);
        mvc.perform(write(delete("/api/platform/businesses/" + biz + "/roles/" + leadId)).cookie(admin)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Move everyone")));

        // a template change reaches a business only when its role is synced
        mvc.perform(write(put("/api/platform/role-templates/" + lead)).cookie(admin)
                .content("{\"name\":\"Desk Lead " + suffix + "\",\"permissions\":[\"self\",\"dir\",\"att.all\"],\"assignable\":[\"employee\"],\"active\":true}")).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, leadId)).containsExactlyInAnyOrder("self", "dir");
        mvc.perform(write(post("/api/platform/businesses/" + biz + "/roles/" + leadId + "/sync")).cookie(admin)).andExpect(status().isOk());
        assertThat(jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?::uuid", String.class, leadId)).containsExactlyInAnyOrder("self", "dir", "att.all");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ?::uuid", Integer.class, leadId)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY at", String.class, leadId)).contains("business.role_add", "business.role_sync");
    }
}
