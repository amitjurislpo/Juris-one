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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID hrmRole, empRole, finRole;
    String suffix;
    int sequence = 0;

    static final byte[] PNG = png(2_000);
    static final byte[] JPEG = jpeg(3_000);

    static byte[] png(int size) {
        byte[] bytes = new byte[size];
        byte[] magic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(magic, 0, bytes, 0, magic.length);
        return bytes;
    }

    static byte[] jpeg(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
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

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005");
    }

    record Person(UUID id, Cookie cookie) {}

    Person person(TestData testData, UUID role) throws Exception {
        String code = "P" + suffix + (++sequence);
        UUID id = testData.employee(code, code.toLowerCase() + "@example.com", role);
        String set = SignIn.session(mockMvc, code.toLowerCase() + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, new Cookie("jo_at", matcher.group(1)));
    }

    MockHttpServletRequestBuilder putOwn(Person person, String type, byte[] body) {
        return write(put("/api/v1/profile/photo")).cookie(person.cookie()).contentType(type).content(body);
    }

    MockHttpServletRequestBuilder putFor(Person actor, UUID target, String type, byte[] body) {
        return write(put("/api/v1/people/" + target + "/photo")).cookie(actor.cookie()).contentType(type).content(body);
    }

    @Test
    void anEmployeeSetsReadsAndRemovesTheirOwnPhoto() throws Exception {
        Person employee = person(data, empRole), colleague = person(data, empRole);
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/profile").cookie(employee.cookie())).andExpect(jsonPath("$.data.photo").doesNotExist());

        String response = mockMvc.perform(putOwn(employee, "image/png", PNG)).andExpect(status().isOk()).andExpect(jsonPath("$.data.photo").isNumber())
                .andReturn().getResponse().getContentAsString();
        long version = Long.parseLong(response.replaceAll(".*\"photo\":(\\d+).*", "$1"));

        // anyone in the business reads it: bytes, type, a one-hour private cache and an ETag
        var read = mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=3600"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"" + version + "\"")).andReturn().getResponse();
        assertThat(read.getContentAsByteArray()).isEqualTo(PNG);
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(colleague.cookie()).header(HttpHeaders.IF_NONE_MATCH, "\"" + version + "\""))
                .andExpect(status().isNotModified());

        // the payloads carry the version
        mockMvc.perform(get("/api/v1/profile").cookie(employee.cookie())).andExpect(jsonPath("$.data.photo").value(version));
        mockMvc.perform(get("/api/v1/me").cookie(employee.cookie())).andExpect(jsonPath("$.data.user.photo").value(version));
        mockMvc.perform(get("/api/v1/directory").cookie(colleague.cookie())).andExpect(jsonPath("$.data.rows[?(@.id == '" + employee.id() + "')].photo").value(version));

        // replacing it changes the type; the audit holds no bytes
        mockMvc.perform(putOwn(employee, "image/jpeg", JPEG)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(colleague.cookie())).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ?", Integer.class, employee.id().toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ? ORDER BY at DESC LIMIT 1", String.class,
                employee.id().toString())).contains("image/jpeg").contains("\"bytes\"");

        mockMvc.perform(write(delete("/api/v1/profile/photo")).cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(colleague.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/profile").cookie(employee.cookie())).andExpect(jsonPath("$.data.photo").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_remove' AND entity_id = ?", Integer.class, employee.id().toString())).isEqualTo(1);
    }

    @Test
    void onlyRealImagesWithinTheLimitAreAccepted() throws Exception {
        Person employee = person(data, empRole);
        mockMvc.perform(putOwn(employee, "text/plain", PNG)).andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(putOwn(employee, "image/gif", PNG)).andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(putOwn(employee, "image/jpeg", PNG)).andExpect(status().isUnprocessableEntity()); // the bytes are a PNG
        mockMvc.perform(putOwn(employee, "image/png", "not an image at all".getBytes())).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(putOwn(employee, "image/png", new byte[0])).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(putOwn(employee, "image/png", Arrays.copyOf(PNG, 400 * 1024 + 1))).andExpect(status().isPayloadTooLarge());
        mockMvc.perform(putOwn(employee, "image/png", Arrays.copyOf(PNG, 400 * 1024))).andExpect(status().isOk()); // exactly the limit
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(1);
    }

    @Test
    void hrSetsAndRemovesAPhotoForSomeoneTheyMayEditAndNobodyElseDoes() throws Exception {
        Person hrPerson = person(data, hrmRole), employee = person(data, empRole), other = person(data, empRole), finance = person(data, finRole);

        mockMvc.perform(putFor(hrPerson, employee.id(), "image/png", PNG)).andExpect(status().isOk()).andExpect(jsonPath("$.data.photo").isNumber());
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(other.cookie())).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE action = 'employee.photo_set' AND entity_id = ?", UUID.class, employee.id().toString())).isEqualTo(hrPerson.id());

        // a plain employee can't set a colleague's photo (no people.add), nor can HR reach a role outside their list
        mockMvc.perform(putFor(other, employee.id(), "image/png", PNG)).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/v1/people/" + employee.id() + "/photo")).cookie(other.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(putFor(hrPerson, finance.id(), "image/png", PNG)).andExpect(status().isForbidden());
        // the same checks on the bytes apply
        mockMvc.perform(putFor(hrPerson, other.id(), "image/jpeg", PNG)).andExpect(status().isUnprocessableEntity());
        // HR doesn't use this route for their own photo (that is /api/v1/profile/photo)
        mockMvc.perform(putFor(hrPerson, hrPerson.id(), "image/png", PNG)).andExpect(status().isForbidden());
        // an exited employee's photo is left alone
        jdbc.update("UPDATE employees SET status = 'EXITED', exit_date = current_date WHERE id = ?", other.id());
        mockMvc.perform(putFor(hrPerson, other.id(), "image/png", PNG)).andExpect(status().isForbidden());

        mockMvc.perform(write(delete("/api/v1/people/" + employee.id() + "/photo")).cookie(hrPerson.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(hrPerson.cookie())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.photo_remove' AND entity_id = ? AND actor_id = ?", Integer.class,
                employee.id().toString(), hrPerson.id())).isEqualTo(1);
    }

    @Test
    void anotherBusinessCanNeitherSeeNorChangeThePhoto() throws Exception {
        Person employee = person(data, empRole);
        mockMvc.perform(putOwn(employee, "image/png", PNG)).andExpect(status().isOk());

        TestData testData = new TestData(jdbc).inBusiness("photo2-" + suffix);
        UUID secondHrRole = testData.role("hrm", "self", "dir", "people.view", "people.add");
        UUID emp2 = testData.role("employee", "self", "dir");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", secondHrRole, emp2);
        Person outsider = person(testData, emp2), hrOutside = person(testData, secondHrRole);
        mockMvc.perform(get("/api/v1/people/" + employee.id() + "/photo").cookie(outsider.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(putFor(hrOutside, employee.id(), "image/png", PNG)).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/v1/people/" + employee.id() + "/photo")).cookie(hrOutside.cookie())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_photos WHERE employee_id = ?", Integer.class, employee.id())).isEqualTo(1);
    }

    @Test
    void thePeopleProfileAndTeamPayloadsCarryThePhotoVersion() throws Exception {
        Person hrPerson = person(data, hrmRole), employee = person(data, empRole);
        mockMvc.perform(putOwn(employee, "image/png", PNG)).andExpect(status().isOk());
        Long version = jdbc.queryForObject("SELECT photo_updated_at FROM employees WHERE id = ?", java.sql.Timestamp.class, employee.id()).getTime();
        String list = mockMvc.perform(get("/api/v1/people").cookie(hrPerson.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("\"photo\":" + version);
        String profile = mockMvc.perform(get("/api/v1/people/" + employee.id()).cookie(hrPerson.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(profile).contains("\"photo\":" + version);
        // My team: a manager sees their report's photo version
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", hrPerson.id(), employee.id());
        mockMvc.perform(get("/api/v1/team").cookie(hrPerson.cookie())).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[0].photo").value(version));
    }
}
