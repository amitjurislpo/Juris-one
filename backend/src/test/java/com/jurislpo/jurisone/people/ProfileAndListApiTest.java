package com.jurislpo.jurisone.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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

/** Employees list: never yourself, never the owner; exports leave the owner out; everyone edits a few details of their own. */
class ProfileAndListApiTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    String s;
    Cookie hr, employee, owner;

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
        TestData t = new TestData(jdbc).inBusiness("list-" + s);
        UUID hrRole = t.role("hrm", "self", "dir", "people.view", "people.add");
        UUID empRole = t.role("employee", "self", "dir");
        UUID ownerRole = t.role("owner", "dir", "roles.manage", "people.view", "people.add", "business.manage");
        t.employee("LH-" + s, "hr." + s + "@example.com", hrRole);
        t.employee("LX-" + s, "hr2." + s + "@example.com", hrRole);
        t.employee("LE-" + s, "emp." + s + "@example.com", empRole);
        t.employee("LO-" + s, "owner." + s + "@example.com", ownerRole);
        hr = signIn("hr." + s + "@example.com");
        employee = signIn("emp." + s + "@example.com");
        owner = signIn("owner." + s + "@example.com");
    }

    @Test
    void theListLeavesOutYourselfAndTheOwner() throws Exception {
        String hrList = mvc.perform(get("/api/people").cookie(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hrList).contains("LX-" + s, "LE-" + s).doesNotContain("LH-" + s, "LO-" + s);
        mvc.perform(get("/api/people").cookie(hr)).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.counts.cur").value(2));
        String ownerList = mvc.perform(get("/api/people").cookie(owner)).andReturn().getResponse().getContentAsString();
        assertThat(ownerList).contains("LH-" + s, "LX-" + s, "LE-" + s).doesNotContain("LO-" + s);
        // the export has everyone but the owner (including the person exporting)
        String csv = mvc.perform(get("/api/people/export").cookie(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("LH-" + s, "LX-" + s, "LE-" + s).doesNotContain("LO-" + s);
        // employees have no list at all
        mvc.perform(get("/api/people").cookie(employee)).andExpect(status().isForbidden());
    }

    @Test
    void everyoneEditsTheirOwnPersonalDetailsButNotEmailOrPhone() throws Exception {
        mvc.perform(get("/api/profile").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("LE-" + s))
                .andExpect(jsonPath("$.data.email").value("emp." + s + "@example.com"));
        mvc.perform(write(put("/api/profile")).cookie(employee).content("""
            {"fatherName":"Ravi Kumar","dateOfBirth":"1995-05-20","currentAddress":"5 MG Road, Pune","permanentAddress":"","emergencyName":"Asha",
             "emergencyRelation":"Mother","emergencyPhoneCountry":"+91","emergencyPhone":"98765"}""")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Emergency contact: India numbers have 10 digits."));
        mvc.perform(write(put("/api/profile")).cookie(employee).content("""
            {"fatherName":"Ravi Kumar","dateOfBirth":"1995-05-20","currentAddress":"5 MG Road, Pune","permanentAddress":"","emergencyName":"Asha",
             "emergencyRelation":"Mother","emergencyPhoneCountry":"+91","emergencyPhone":"98765 43210","email":"hacker@example.com","phone":"9999999999"}"""))
                .andExpect(status().isOk());
        mvc.perform(get("/api/profile").cookie(employee)).andExpect(jsonPath("$.data.fatherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.data.currentAddress").value("5 MG Road, Pune")).andExpect(jsonPath("$.data.emergencyPhone").value("9876543210"))
                .andExpect(jsonPath("$.data.email").value("emp." + s + "@example.com"));
        assertThat(jdbc.queryForObject("SELECT address_enc FROM employees WHERE code = ?", String.class, "LE-" + s)).doesNotContain("MG Road"); // encrypted
        // the owner has a profile page too
        mvc.perform(get("/api/profile").cookie(owner)).andExpect(status().isOk());
    }
}
