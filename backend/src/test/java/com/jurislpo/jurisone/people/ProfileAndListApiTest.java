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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    String suffix;
    Cookie hrCookie, employee, owner;

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
        TestData testData = new TestData(jdbc).inBusiness("list-" + suffix);
        UUID hrRole = testData.role("hrm", "self", "dir", "people.view", "people.add");
        UUID empRole = testData.role("employee", "self", "dir");
        UUID ownerRole = testData.role("owner", "dir", "roles.manage", "people.view", "people.add", "business.manage");
        testData.employee("LH-" + suffix, "hr." + suffix + "@example.com", hrRole);
        testData.employee("LX-" + suffix, "hr2." + suffix + "@example.com", hrRole);
        testData.employee("LE-" + suffix, "emp." + suffix + "@example.com", empRole);
        testData.employee("LO-" + suffix, "owner." + suffix + "@example.com", ownerRole);
        hrCookie = signIn("hr." + suffix + "@example.com");
        employee = signIn("emp." + suffix + "@example.com");
        owner = signIn("owner." + suffix + "@example.com");
    }

    @Test
    void theListLeavesOutYourselfAndTheOwner() throws Exception {
        String hrList = mockMvc.perform(get("/api/v1/people").cookie(hrCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hrList).contains("LX-" + suffix, "LE-" + suffix).doesNotContain("LH-" + suffix, "LO-" + suffix);
        mockMvc.perform(get("/api/v1/people").cookie(hrCookie)).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.counts.current").value(2));
        String ownerList = mockMvc.perform(get("/api/v1/people").cookie(owner)).andReturn().getResponse().getContentAsString();
        assertThat(ownerList).contains("LH-" + suffix, "LX-" + suffix, "LE-" + suffix).doesNotContain("LO-" + suffix);
        // the export has everyone but the owner (including the person exporting)
        String csv = mockMvc.perform(get("/api/v1/people/export").cookie(hrCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("LH-" + suffix, "LX-" + suffix, "LE-" + suffix).doesNotContain("LO-" + suffix);
        // employees have no list at all
        mockMvc.perform(get("/api/v1/people").cookie(employee)).andExpect(status().isForbidden());
    }

    @Test
    void everyoneEditsTheirOwnPersonalDetailsButNotEmailOrPhone() throws Exception {
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value("LE-" + suffix))
                .andExpect(jsonPath("$.data.email").value("emp." + suffix + "@example.com"));
        mockMvc.perform(write(put("/api/v1/profile")).cookie(employee).content("""
            {"fatherName":"Ravi Kumar","dateOfBirth":"1995-05-20","currentAddress":"5 MG Road, Pune","permanentAddress":"","emergencyName":"Asha",
             "emergencyRelation":"Mother","emergencyPhoneCountry":"+91","emergencyPhone":"98765"}""")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Emergency contact: India numbers have 10 digits."));
        mockMvc.perform(write(put("/api/v1/profile")).cookie(employee).content("""
            {"fatherName":"Ravi Kumar","dateOfBirth":"1995-05-20","currentAddress":"5 MG Road, Pune","permanentAddress":"","emergencyName":"Asha",
             "emergencyRelation":"Mother","emergencyPhoneCountry":"+91","emergencyPhone":"98765 43210","email":"hacker@example.com","phone":"9999999999"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(jsonPath("$.data.fatherName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.data.currentAddress").value("5 MG Road, Pune")).andExpect(jsonPath("$.data.emergencyPhone").value("9876543210"))
                .andExpect(jsonPath("$.data.email").value("emp." + suffix + "@example.com"));
        assertThat(jdbc.queryForObject("SELECT address_enc FROM employees WHERE code = ?", String.class, "LE-" + suffix)).doesNotContain("MG Road"); // encrypted
        // the owner has a profile page too
        mockMvc.perform(get("/api/v1/profile").cookie(owner)).andExpect(status().isOk());
    }
}
