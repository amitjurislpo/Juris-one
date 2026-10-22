package com.jurislpo.jurisone.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.sql.Timestamp;
import java.time.Instant;
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

/** Notifications stay until read one by one; a policy notification stays until the policy is accepted. */
class NotificationApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie owner, employee;
    UUID employeeId;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        String suffix = TestData.letters(8);
        TestData testData = new TestData(jdbc).inBusiness("inbox-" + suffix);
        UUID ownerRole = testData.role("owner", "dir", "roles.manage", "policies.manage", "business.manage");
        employeeId = testData.employee("NE-" + suffix, "emp." + suffix + "@example.com", testData.role("employee", "self", "dir"));
        testData.employee("NO-" + suffix, "owner." + suffix + "@example.com", ownerRole);
        owner = signIn("owner." + suffix + "@example.com");
        employee = signIn("emp." + suffix + "@example.com");
    }

    UUID note(String text, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, employee_id, text, link, created_at) VALUES (?, ?, ?, '/leave', ?)", id, employeeId, text,
                Timestamp.from(Instant.now().minusSeconds(minutesAgo * 60L)));
        return id;
    }

    JsonNode inbox(String query) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/notifications" + query).cookie(employee)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/data");
    }

    @Test
    void unreadFirstAndEachOneIsReadOnItsOwn() throws Exception {
        UUID oldId = note("Leave approved", 60);
        UUID middleId = note("Regularization approved", 30);
        note("Holiday added", 5);
        jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", middleId);

        JsonNode all = inbox("");
        assertThat(all.at("/unread").asInt()).isEqualTo(2);
        assertThat(all.at("/total").asInt()).isEqualTo(3);
        assertThat(all.at("/items/0/text").asText()).isEqualTo("Holiday added");
        assertThat(all.at("/items/1/text").asText()).isEqualTo("Leave approved");
        assertThat(all.at("/items/2/text").asText()).isEqualTo("Regularization approved"); // read ones last
        assertThat(inbox("?unread=true&limit=1").at("/pages").asInt()).isEqualTo(2);

        // opening the bell / shell doesn't mark anything read any more
        mockMvc.perform(get("/api/shell").cookie(employee)).andExpect(jsonPath("$.data.unread").value(2))
                .andExpect(jsonPath("$.data.notes[0].unread").value(true));
        mockMvc.perform(write(patch("/api/notifications/" + oldId)).cookie(employee).content("{\"read\":true}")).andExpect(status().isOk());
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(1);
        mockMvc.perform(write(patch("/api/notifications/" + oldId)).cookie(employee).content("{\"read\":false}")).andExpect(status().isOk());
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(2);
        // someone else's notification doesn't exist for you
        mockMvc.perform(write(patch("/api/notifications/" + oldId)).cookie(owner).content("{\"read\":true}")).andExpect(status().isNotFound());
        mockMvc.perform(write(patch("/api/notifications")).cookie(employee).content("{\"read\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));
        assertThat(inbox("").at("/unread").asInt()).isZero();
    }

    @Test
    void aPolicyNotificationStaysUntilThePolicyIsAccepted() throws Exception {
        String policy = json.readTree(mockMvc.perform(write(post("/api/policies")).cookie(owner).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind.\"}"))
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(write(put("/api/policies/" + policy + "/status")).cookie(owner).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        note("Leave approved", 1);

        JsonNode box = inbox(""); // reachable while the policy waits
        String sticky = null;
        for (JsonNode item : box.at("/items")) if (item.at("/sticky").asBoolean()) sticky = item.at("/id").asText();
        assertThat(sticky).isNotNull();
        mockMvc.perform(write(patch("/api/notifications/" + sticky)).cookie(employee).content("{\"read\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("This stays until you accept the policy."));
        mockMvc.perform(write(patch("/api/notifications")).cookie(employee).content("{\"read\":true}")).andExpect(jsonPath("$.data.count").value(1));
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(1);

        // version 2 replaces the notification; accepting it clears it
        mockMvc.perform(write(put("/api/policies/" + policy)).cookie(owner).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind. Be honest.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(write(put("/api/policies/" + policy + "/status")).cookie(owner).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        assertThat(inbox("?unread=true").at("/total").asInt()).isEqualTo(1);
        assertThat(inbox("?unread=true").at("/items/0/text").asText()).startsWith("Updated policy: Code of conduct");
        mockMvc.perform(write(post("/api/policies/" + policy + "/acknowledgements")).cookie(employee).content("{\"version\":2}")).andExpect(status().isCreated());
        assertThat(inbox("").at("/unread").asInt()).isZero();
    }
}
