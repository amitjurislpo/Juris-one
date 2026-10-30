package com.jurislpo.jurisone.announcements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Announcements: HR posts them, active ones reach the right employees and ring the bell, each business is separate. */
class AnnouncementApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hrCookie, indian, american, otherBusiness;
    UUID indianId, americanId;
    String suffix;

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
        suffix = TestData.letters(8);
        TestData testData = new TestData(jdbc).inBusiness("news-" + suffix);
        UUID empRole = testData.role("employee", "self", "dir");
        testData.employee("NH-" + suffix, "hr." + suffix + "@example.com", testData.role("hrm", "self", "dir", "announcements.manage"));
        indianId = testData.employee("NI-" + suffix, "in." + suffix + "@example.com", empRole);
        americanId = testData.employee("NU-" + suffix, "us." + suffix + "@example.com", empRole);
        jdbc.update("UPDATE employees SET region = 'US' WHERE id = ?", americanId);
        TestData otherData = new TestData(jdbc).inBusiness("news2-" + suffix);
        otherData.employee("MX-" + suffix, "x." + suffix + "@example.com", otherData.role("employee", "self", "dir"));
        hrCookie = signIn("hr." + suffix + "@example.com");
        indian = signIn("in." + suffix + "@example.com");
        american = signIn("us." + suffix + "@example.com");
        otherBusiness = signIn("x." + suffix + "@example.com");
    }

    String announce(String body) throws Exception {
        return json.readTree(mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    int unread(UUID employee) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Announcement:%'", Integer.class, employee);
    }

    @Test
    void publishShowsToEmployeesAndNotifiesThem() throws Exception {
        String all = announce("{\"title\":\"Office closed Friday\",\"body\":\"Enjoy the long weekend.\",\"pinned\":false}");
        announce("{\"title\":\"Welcome\",\"body\":\"Hello team\",\"pinned\":true}");
        mockMvc.perform(get("/api/v1/announcements").cookie(indian)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("Welcome")) // pinned first
                .andExpect(jsonPath("$.data[0].pinned").value(true)).andExpect(jsonPath("$.data[1].id").value(all));
        assertThat(unread(indianId)).isEqualTo(2);
        assertThat(unread(americanId)).isEqualTo(2);

        // edit in place (no new notification), then unpublish: gone for employees, still in the manager's list
        mockMvc.perform(write(put("/api/v1/announcements/" + all)).cookie(hrCookie).content("{\"title\":\"Office closed Friday 24th\",\"body\":\"Enjoy.\",\"audience\":\"ALL\"}"))
                .andExpect(status().isOk());
        assertThat(unread(indianId)).isEqualTo(2);
        mockMvc.perform(write(put("/api/v1/announcements/" + all + "/status")).cookie(hrCookie).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isOk());
        mockMvc.perform(write(put("/api/v1/announcements/" + all + "/status")).cookie(hrCookie).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/announcements?scope=all").cookie(hrCookie)).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity = 'announcement' AND action LIKE 'announcement.%'", Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void audienceAndExpiry() throws Exception {
        announce("{\"title\":\"US holiday\",\"body\":\"Thanksgiving\",\"audience\":\"US\"}");
        announce("{\"title\":\"India holiday\",\"body\":\"Diwali\",\"audience\":\"IN\"}");
        String expiringId = announce("{\"title\":\"Expiring\",\"body\":\"Soon gone\",\"expiresOn\":\"" + java.time.LocalDate.now().plusDays(3) + "\"}");
        mockMvc.perform(get("/api/v1/announcements").cookie(american)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.title=='India holiday')]").isEmpty());
        mockMvc.perform(get("/api/v1/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.title=='US holiday')]").isEmpty());
        assertThat(unread(indianId)).isEqualTo(2);
        assertThat(unread(americanId)).isEqualTo(2);
        // once the expiry date has passed it is no longer shown
        jdbc.update("UPDATE announcements SET expires_on = current_date - 1 WHERE id = ?::uuid", expiringId);
        mockMvc.perform(get("/api/v1/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/announcements?scope=all").cookie(hrCookie)).andExpect(jsonPath("$.data[?(@.id=='" + expiringId + "')].active").value(false));
    }

    @Test
    void permissionsAndValidation() throws Exception {
        mockMvc.perform(write(post("/api/v1/announcements")).cookie(indian).content("{\"title\":\"Hi there\",\"body\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/announcements?scope=all").cookie(indian)).andExpect(status().isForbidden());
        String id = announce("{\"title\":\"Valid one\",\"body\":\"ok\"}");
        mockMvc.perform(write(put("/api/v1/announcements/" + id)).cookie(indian).content("{\"title\":\"Hacked\",\"body\":\"x\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/announcements/" + id + "/status")).cookie(indian).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/announcements")).andExpect(status().isUnauthorized());

        mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content("{\"title\":\"ab\",\"body\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content("{\"title\":\"Fine title\",\"body\":\"  \"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content("{\"title\":\"Fine title\",\"body\":\"x\",\"audience\":\"MARS\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content("{\"title\":\"Fine title\",\"body\":\"x\",\"expiresOn\":\"tomorrow\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/announcements")).cookie(hrCookie).content("{\"title\":\"Fine title\",\"body\":\"x\",\"expiresOn\":\"2020-01-01\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put("/api/v1/announcements/" + UUID.randomUUID())).cookie(hrCookie).content("{\"title\":\"Fine title\",\"body\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void aBusinessNeverSeesAnotherBusinesssAnnouncements() throws Exception {
        String id = announce("{\"title\":\"Private news\",\"body\":\"Only us\"}");
        mockMvc.perform(get("/api/v1/announcements").cookie(otherBusiness)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = ? AND n.text LIKE 'Announcement:%'",
                Integer.class, "MX-" + suffix)).isZero();
        mockMvc.perform(write(put("/api/v1/announcements/" + id + "/status")).cookie(otherBusiness).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isForbidden());
    }
}
