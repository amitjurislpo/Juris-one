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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hr, indian, american, otherBusiness;
    UUID indianId, americanId;
    String s;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        s = TestData.letters(8);
        TestData t = new TestData(jdbc).inBusiness("news-" + s);
        UUID empRole = t.role("employee", "self", "dir");
        t.employee("NH-" + s, "hr." + s + "@example.com", t.role("hrm", "self", "dir", "announcements.manage"));
        indianId = t.employee("NI-" + s, "in." + s + "@example.com", empRole);
        americanId = t.employee("NU-" + s, "us." + s + "@example.com", empRole);
        jdbc.update("UPDATE employees SET region = 'US' WHERE id = ?", americanId);
        TestData t2 = new TestData(jdbc).inBusiness("news2-" + s);
        t2.employee("MX-" + s, "x." + s + "@example.com", t2.role("employee", "self", "dir"));
        hr = signIn("hr." + s + "@example.com");
        indian = signIn("in." + s + "@example.com");
        american = signIn("us." + s + "@example.com");
        otherBusiness = signIn("x." + s + "@example.com");
    }

    String announce(String body) throws Exception {
        return json.readTree(mvc.perform(write(post("/api/announcements")).cookie(hr).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    int unread(UUID employee) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Announcement:%'", Integer.class, employee);
    }

    @Test
    void publishShowsToEmployeesAndNotifiesThem() throws Exception {
        String all = announce("{\"title\":\"Office closed Friday\",\"body\":\"Enjoy the long weekend.\",\"pinned\":false}");
        announce("{\"title\":\"Welcome\",\"body\":\"Hello team\",\"pinned\":true}");
        mvc.perform(get("/api/announcements").cookie(indian)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].title").value("Welcome")) // pinned first
                .andExpect(jsonPath("$.data[0].pinned").value(true)).andExpect(jsonPath("$.data[1].id").value(all));
        assertThat(unread(indianId)).isEqualTo(2);
        assertThat(unread(americanId)).isEqualTo(2);

        // edit in place (no new notification), then unpublish: gone for employees, still in the manager's list
        mvc.perform(write(put("/api/announcements/" + all)).cookie(hr).content("{\"title\":\"Office closed Friday 24th\",\"body\":\"Enjoy.\",\"audience\":\"ALL\"}"))
                .andExpect(status().isOk());
        assertThat(unread(indianId)).isEqualTo(2);
        mvc.perform(write(put("/api/announcements/" + all + "/status")).cookie(hr).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isOk());
        mvc.perform(write(put("/api/announcements/" + all + "/status")).cookie(hr).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/announcements?scope=all").cookie(hr)).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity = 'announcement' AND action LIKE 'announcement.%'", Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void audienceAndExpiry() throws Exception {
        announce("{\"title\":\"US holiday\",\"body\":\"Thanksgiving\",\"audience\":\"US\"}");
        announce("{\"title\":\"India holiday\",\"body\":\"Diwali\",\"audience\":\"IN\"}");
        String old = announce("{\"title\":\"Expiring\",\"body\":\"Soon gone\",\"expiresOn\":\"" + java.time.LocalDate.now().plusDays(3) + "\"}");
        mvc.perform(get("/api/announcements").cookie(american)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.title=='India holiday')]").isEmpty());
        mvc.perform(get("/api/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.title=='US holiday')]").isEmpty());
        assertThat(unread(indianId)).isEqualTo(2);
        assertThat(unread(americanId)).isEqualTo(2);
        // once the expiry date has passed it is no longer shown
        jdbc.update("UPDATE announcements SET expires_on = current_date - 1 WHERE id = ?::uuid", old);
        mvc.perform(get("/api/announcements").cookie(indian)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/announcements?scope=all").cookie(hr)).andExpect(jsonPath("$.data[?(@.id=='" + old + "')].active").value(false));
    }

    @Test
    void permissionsAndValidation() throws Exception {
        mvc.perform(write(post("/api/announcements")).cookie(indian).content("{\"title\":\"Hi there\",\"body\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/announcements?scope=all").cookie(indian)).andExpect(status().isForbidden());
        String id = announce("{\"title\":\"Valid one\",\"body\":\"ok\"}");
        mvc.perform(write(put("/api/announcements/" + id)).cookie(indian).content("{\"title\":\"Hacked\",\"body\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/announcements/" + id + "/status")).cookie(indian).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/announcements")).andExpect(status().isUnauthorized());

        mvc.perform(write(post("/api/announcements")).cookie(hr).content("{\"title\":\"ab\",\"body\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/announcements")).cookie(hr).content("{\"title\":\"Fine title\",\"body\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/announcements")).cookie(hr).content("{\"title\":\"Fine title\",\"body\":\"x\",\"audience\":\"MARS\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/announcements")).cookie(hr).content("{\"title\":\"Fine title\",\"body\":\"x\",\"expiresOn\":\"tomorrow\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/announcements")).cookie(hr).content("{\"title\":\"Fine title\",\"body\":\"x\",\"expiresOn\":\"2020-01-01\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/api/announcements/" + UUID.randomUUID())).cookie(hr).content("{\"title\":\"Fine title\",\"body\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test
    void aBusinessNeverSeesAnotherBusinesssAnnouncements() throws Exception {
        String id = announce("{\"title\":\"Private news\",\"body\":\"Only us\"}");
        mvc.perform(get("/api/announcements").cookie(otherBusiness)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = ? AND n.text LIKE 'Announcement:%'",
                Integer.class, "MX-" + s)).isZero();
        mvc.perform(write(put("/api/announcements/" + id + "/status")).cookie(otherBusiness).content("{\"status\":\"UNPUBLISHED\"}")).andExpect(status().isForbidden());
    }
}
