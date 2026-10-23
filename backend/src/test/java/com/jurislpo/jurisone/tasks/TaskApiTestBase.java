package com.jurislpo.jurisone.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * A fresh business per test with the default roles of the task plan: Owner ({@code tasks.manage}, {@code projects.create}, no
 * {@code self}), HR Manager ({@code projects.create}), Finance Manager and Employee (neither).
 */
abstract class TaskApiTestBase extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix;
    UUID ownerRole, hrRole, financeRole, employeeRole;

    record Person(UUID id, String email, Cookie cookie) {}

    @BeforeEach
    void business() {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("tasks-" + suffix);
        ownerRole = data.role("owner", "dir", "roles.manage", "business.manage", "tasks.manage", "projects.create");
        hrRole = data.role("hrm", "self", "dir", "people.view", "projects.create");
        financeRole = data.role("fm", "self", "dir", "people.view", "pay.view");
        employeeRole = data.role("employee", "self", "dir");
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    /** A signed-in employee of the test business. */
    Person person(UUID role, String name) throws Exception {
        String email = name + "." + suffix + "@example.com";
        UUID id = data.employee((name.substring(0, Math.min(3, name.length())) + "-" + suffix + TestData.letters(3)).toUpperCase(), email, role);
        return new Person(id, email, signIn(email));
    }

    void reportsTo(Person person, Person manager) {
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), person.id());
    }

    JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    JsonNode getData(Cookie cookie, String url) throws Exception {
        return read(mockMvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    JsonNode postCreated(Cookie cookie, String url, String body) throws Exception {
        return read(mockMvc.perform(write(post(url)).cookie(cookie).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
    }

    /** Creates a project and returns its id. */
    String project(Cookie cookie, String key, String visibility) throws Exception {
        return postCreated(cookie, "/api/projects", "{\"key\":\"" + key + "\",\"name\":\"Project " + key + " " + suffix + "\",\"visibility\":\"" + visibility + "\"}")
                .get("id").asText();
    }

    void addMember(Cookie cookie, String projectId, Person person, String role) throws Exception {
        mockMvc.perform(write(post("/api/projects/" + projectId + "/members")).cookie(cookie).content("{\"employeeId\":\"" + person.id() + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isCreated());
    }

    /** Creates a task ({@code fields} is the JSON body without braces) and returns {@code {id, key}}. */
    JsonNode task(Cookie cookie, String fields) throws Exception {
        return postCreated(cookie, "/api/tasks", "{" + fields + "}");
    }

    int version(Cookie cookie, String taskId) throws Exception {
        return getData(cookie, "/api/tasks/" + taskId).get("version").asInt();
    }

    /** The id of a step of a project (null = the business default set) by name. */
    String statusId(String projectId, String name) {
        return jdbc.queryForObject("SELECT id::text FROM task_statuses WHERE project_id IS NOT DISTINCT FROM ?::uuid AND name = ? AND business_id = ?", String.class,
                projectId, name, data.business());
    }

    int auditRows(String action, String entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?", Integer.class, action, entityId);
    }
}
