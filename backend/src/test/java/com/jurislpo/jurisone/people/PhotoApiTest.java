package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Employee profile photos: own upload, HR upload for someone in reach, the directory read and the payload field. */
class PhotoApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID hrmRole, empRole, finRole;
    String suffix;
    int seq = 0;

    static final byte[] PNG = png(2_000);
    static final byte[] JPEG = jpeg(3_000);

    static byte[] png(int n) {
        byte[] b = new byte[n];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    static byte[] jpeg(int n) {
        byte[] b = new byte[n];
        b[0] = (byte) 0xFF;
        b[1] = (byte) 0xD8;
        b[2] = (byte) 0xFF;
        return b;
    }

    @BeforeEach
    void setUp() {
        suffix = TestData.letters(6);
        data = new TestData(jdbc).inBusiness("photo-" + suffix);
        hrmRole = data.role("hrm", "self", "dir", "people.view", "people.add");
        empRole = data.role("employee", "self", "dir");
        finRole = data.role("fm", "self", "dir", "people.view", "struct.manage");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrmRole, empRole);
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    record Person(UUID id, Cookie cookie) {}

    Person person(TestData t, UUID role) throws Exception {
        String code = "P" + suffix + (++seq);
        UUID id = t.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mvc, code.toLowerCase() + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Person(id, new Cookie("jo_at", m.group(1)));
    }

    MockHttpServletRequestBuilder putOwn(Person p, String type, byte[] body) {
        return write(put("/api/profile/photo")).cookie(p.cookie()).contentType(type).content(body);
    }

    MockHttpServletRequestBuilder putFor(Person actor, UUID target, String type, byte[] body) {
        return write(put("/api/people/" + target + "/photo")).cookie(actor.cookie()).contentType(type).content(body);
    }

    @Test
    void anEmployeeSetsReadsAndRemovesTheirOwnPhoto() throws Exception {
        Person e = person(data, empRole), colleague = person(data, empRole);
        mvc.perform(get("/api/people/" + e.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isNotFound());
        mvc.perform(get("/api/profile").cookie(e.cookie())).andExpect(jsonPath("$.data.photo").doesNotExist());

        String res = mvc.perform(putOwn(e, "image/png", PNG)).andExpect(status().isOk()).andExpect(jsonPath("$.data.photo").isNumber())
                .andReturn().getResponse().getContentAsString();
        long version = Long.parseLong(res.replaceAll(".*\"photo\":(\\d+).*", "$1"));

        // anyone in the business reads it: bytes, type, a one-hour private cache and an ETag
        var read = mvc.perform(get("/api/people/" + e.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=3600"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + version + "\"")).andReturn().getResponse();
        assertThat(read.getContentAsByteArray()).isEqualTo(PNG);
        mvc.perform(get("/api/people/" + e.id() + "/photo").cookie(colleague.cookie()).header(HttpHeaders.IF_NONE_MATCH, "\"" + version + "\""))
                .andExpect(status().isNotModified());

        // the payloads carry the version
        mvc.perform(get("/api/profile").cookie(e.cookie())).andExpect(jsonPath("$.data.photo").value(version));
        mvc.perform(get("/api/me").cookie(e.cookie())).andExpect(jsonPath("$.data.user.photo").value(version));
        mvc.perform(get("/api/directory").cookie(colleague.cookie())).andExpect(jsonPath("$.data.rows[?(@.id == '" + e.id() + "')].photo").value(version));

        // replacing it changes the type; the audit holds no bytes
        mvc.perform(putOwn(e, "image/jpeg", JPEG)).andExpect(status().isOk());
        mvc.perform(get("/api/people/" + e.id() + "/photo").cookie(colleague.cookie())).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ?", Integer.class, e.id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ? ORDER BY at DESC LIMIT 1", String.class,
                e.id().toString())).contains("image/jpeg").contains("\"bytes\"");

        mvc.perform(write(delete("/api/profile/photo")).cookie(e.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/people/" + e.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isNotFound());
        mvc.perform(get("/api/profile").cookie(e.cookie())).andExpect(jsonPath("$.data.photo").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_remove' AND entity_id = ?", Integer.class, e.id().toString())).isEqualTo(1);
    }

    @Test
    void onlyRealImagesWithinTheLimitAreAccepted() throws Exception {
        Person e = person(data, empRole);
        mvc.perform(putOwn(e, "text/plain", PNG)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(putOwn(e, "image/gif", PNG)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(putOwn(e, "image/jpeg", PNG)).andExpect(status().isUnprocessableEntity()); // the bytes are a PNG
        mvc.perform(putOwn(e, "image/png", "not an image at all".getBytes())).andExpect(status().isUnprocessableEntity());
        mvc.perform(putOwn(e, "image/png", new byte[0])).andExpect(status().isUnprocessableEntity());
        mvc.perform(putOwn(e, "image/png", Arrays.copyOf(PNG, 400 * 1024 + 1))).andExpect(status().isPayloadTooLarge());
        mvc.perform(putOwn(e, "image/png", Arrays.copyOf(PNG, 400 * 1024))).andExpect(status().isOk()); // exactly the limit
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos WHERE employee_id = ?", Integer.class, e.id())).isEqualTo(1);
    }

    @Test
    void hrSetsAndRemovesAPhotoForSomeoneTheyMayEditAndNobodyElseDoes() throws Exception {
        Person hr = person(data, hrmRole), emp = person(data, empRole), other = person(data, empRole), fin = person(data, finRole);

        mvc.perform(putFor(hr, emp.id(), "image/png", PNG)).andExpect(status().isOk()).andExpect(jsonPath("$.data.photo").isNumber());
        mvc.perform(get("/api/people/" + emp.id() + "/photo").cookie(other.cookie())).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ?", UUID.class, emp.id().toString())).isEqualTo(hr.id());

        // a plain employee can't set a colleague's photo (no people.add), nor can HR reach a role outside their list
        mvc.perform(putFor(other, emp.id(), "image/png", PNG)).andExpect(status().isForbidden());
        mvc.perform(write(delete("/api/people/" + emp.id() + "/photo")).cookie(other.cookie())).andExpect(status().isForbidden());
        mvc.perform(putFor(hr, fin.id(), "image/png", PNG)).andExpect(status().isForbidden());
        // the same checks on the bytes apply
        mvc.perform(putFor(hr, other.id(), "image/jpeg", PNG)).andExpect(status().isUnprocessableEntity());
        // HR doesn't use this route for their own photo (that is /api/profile/photo)
        mvc.perform(putFor(hr, hr.id(), "image/png", PNG)).andExpect(status().isForbidden());
        // an exited employee's photo is left alone
        jdbc.update("UPDATE employees SET status = 'EXITED', exit_date = current_date WHERE id = ?", other.id());
        mvc.perform(putFor(hr, other.id(), "image/png", PNG)).andExpect(status().isForbidden());

        mvc.perform(write(delete("/api/people/" + emp.id() + "/photo")).cookie(hr.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/people/" + emp.id() + "/photo").cookie(hr.cookie())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_remove' AND entity_id = ? AND actor_id = ?", Integer.class,
                emp.id().toString(), hr.id())).isEqualTo(1);
    }

    @Test
    void anotherBusinessCanNeitherSeeNorChangeThePhoto() throws Exception {
        Person emp = person(data, empRole);
        mvc.perform(putOwn(emp, "image/png", PNG)).andExpect(status().isOk());

        TestData t2 = new TestData(jdbc).inBusiness("photo2-" + suffix);
        UUID hr2 = t2.role("hrm", "self", "dir", "people.view", "people.add");
        UUID emp2 = t2.role("employee", "self", "dir");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hr2, emp2);
        Person outsider = person(t2, emp2), hrOutside = person(t2, hr2);
        mvc.perform(get("/api/people/" + emp.id() + "/photo").cookie(outsider.cookie())).andExpect(status().isNotFound());
        mvc.perform(putFor(hrOutside, emp.id(), "image/png", PNG)).andExpect(status().isForbidden());
        mvc.perform(write(delete("/api/people/" + emp.id() + "/photo")).cookie(hrOutside.cookie())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos WHERE employee_id = ?", Integer.class, emp.id())).isEqualTo(1);
    }

    @Test
    void thePeopleProfileAndTeamPayloadsCarryThePhotoVersion() throws Exception {
        Person hr = person(data, hrmRole), emp = person(data, empRole);
        mvc.perform(putOwn(emp, "image/png", PNG)).andExpect(status().isOk());
        Long v = jdbc.queryForObject("SELECT photo_updated_at FROM employees WHERE id = ?", java.sql.Timestamp.class, emp.id()).getTime();
        String list = mvc.perform(get("/api/people").cookie(hr.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"photo\":" + v);
        String profile = mvc.perform(get("/api/people/" + emp.id()).cookie(hr.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(profile).contains("\"photo\":" + v);
        // My team: a manager sees their report's photo version
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", hr.id(), emp.id());
        mvc.perform(get("/api/team").cookie(hr.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].photo").value(v));
    }
}
