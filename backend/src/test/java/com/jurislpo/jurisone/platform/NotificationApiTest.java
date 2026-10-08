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

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie owner, employee;
    UUID employeeId;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9]", "y");
        TestData t = new TestData(jdbc).inBusiness("inbox-" + s);
        UUID ownerRole = t.role("owner", "dir", "roles.manage", "policies.manage", "business.manage");
        employeeId = t.employee("NE-" + s, "emp." + s + "@example.com", t.role("employee", "self", "dir"), PW);
        t.employee("NO-" + s, "owner." + s + "@example.com", ownerRole, PW);
        owner = signIn("owner." + s + "@example.com");
        employee = signIn("emp." + s + "@example.com");
    }

    UUID note(String text, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id, employee_id, text, link, created_at) VALUES (?, ?, ?, '/leave', ?)", id, employeeId, text,
                Timestamp.from(Instant.now().minusSeconds(minutesAgo * 60L)));
        return id;
    }

    JsonNode inbox(String q) throws Exception {
        return json.readTree(mvc.perform(get("/api/notifications" + q).cookie(employee)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/data");
    }

    @Test
    void unreadFirstAndEachOneIsReadOnItsOwn() throws Exception {
        UUID old = note("Leave approved", 60);
        UUID mid = note("Regularization approved", 30);
        note("Holiday added", 5);
        jdbc.update("UPDATE notifications SET read_at = now() WHERE id = ?", mid);

        JsonNode all = inbox("");
        assertThat(all.at("/unread").asInt()).isEqualTo(2);
        assertThat(all.at("/total").asInt()).isEqualTo(3);
        assertThat(all.at("/items/0/text").asText()).isEqualTo("Holiday added");
        assertThat(all.at("/items/1/text").asText()).isEqualTo("Leave approved");
        assertThat(all.at("/items/2/text").asText()).isEqualTo("Regularization approved"); // read ones last
        assertThat(inbox("?unread=true&limit=1").at("/pages").asInt()).isEqualTo(2);

        // opening the bell / shell doesn't mark anything read any more
        mvc.perform(get("/api/shell").cookie(employee)).andExpect(jsonPath("$.data.unread").value(2))
                .andExpect(jsonPath("$.data.notes[0].unread").value(true));
        mvc.perform(write(patch("/api/notifications/" + old)).cookie(employee).content("{\"read\":true}")).andExpect(status().isOk());
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(1);
        mvc.perform(write(patch("/api/notifications/" + old)).cookie(employee).content("{\"read\":false}")).andExpect(status().isOk());
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(2);
        // someone else's notification doesn't exist for you
        mvc.perform(write(patch("/api/notifications/" + old)).cookie(owner).content("{\"read\":true}")).andExpect(status().isNotFound());
        mvc.perform(write(patch("/api/notifications")).cookie(employee).content("{\"read\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(2));
        assertThat(inbox("").at("/unread").asInt()).isZero();
    }

    @Test
    void aPolicyNotificationStaysUntilThePolicyIsAccepted() throws Exception {
        String policy = json.readTree(mvc.perform(write(post("/api/policies")).cookie(owner).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind.\"}"))
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(write(put("/api/policies/" + policy + "/status")).cookie(owner).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        note("Leave approved", 1);

        JsonNode box = inbox(""); // reachable while the policy waits
        String sticky = null;
        for (JsonNode i : box.at("/items")) if (i.at("/sticky").asBoolean()) sticky = i.at("/id").asText();
        assertThat(sticky).isNotNull();
        mvc.perform(write(patch("/api/notifications/" + sticky)).cookie(employee).content("{\"read\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("This stays until you accept the policy."));
        mvc.perform(write(patch("/api/notifications")).cookie(employee).content("{\"read\":true}")).andExpect(jsonPath("$.data.count").value(1));
        assertThat(inbox("").at("/unread").asInt()).isEqualTo(1);

        // version 2 replaces the notification; accepting it clears it
        mvc.perform(write(put("/api/policies/" + policy)).cookie(owner).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind. Be honest.\"}"))
                .andExpect(status().isOk());
        mvc.perform(write(put("/api/policies/" + policy + "/status")).cookie(owner).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
        assertThat(inbox("?unread=true").at("/total").asInt()).isEqualTo(1);
        assertThat(inbox("?unread=true").at("/items/0/text").asText()).startsWith("Updated policy: Code of conduct");
        mvc.perform(write(post("/api/policies/" + policy + "/acknowledgements")).cookie(employee).content("{\"version\":2}")).andExpect(status().isCreated());
        assertThat(inbox("").at("/unread").asInt()).isZero();
    }
}
