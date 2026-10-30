package com.jurislpo.jurisone.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** The asset register: HR manages it, everyone sees their own assets, each business sees only its own. */
class AssetApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hrCookie, employee, other, otherBusinessHr;
    UUID employeeId, otherId;
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
        TestData testData = new TestData(jdbc).inBusiness("assets-" + suffix);
        UUID hrRole = testData.role("hrm", "self", "dir", "people.view", "assets.manage");
        UUID empRole = testData.role("employee", "self", "dir");
        testData.employee("AH-" + suffix, "hr." + suffix + "@example.com", hrRole);
        employeeId = testData.employee("AE-" + suffix, "emp." + suffix + "@example.com", empRole);
        otherId = testData.employee("AX-" + suffix, "other." + suffix + "@example.com", empRole);
        TestData otherData = new TestData(jdbc).inBusiness("assets2-" + suffix);
        otherData.employee("BH-" + suffix, "hr2." + suffix + "@example.com", otherData.role("hrm", "self", "dir", "assets.manage"));
        hrCookie = signIn("hr." + suffix + "@example.com");
        employee = signIn("emp." + suffix + "@example.com");
        other = signIn("other." + suffix + "@example.com");
        otherBusinessHr = signIn("hr2." + suffix + "@example.com");
    }

    String add(Cookie cookie, String tag) throws Exception {
        return json.readTree(mockMvc.perform(write(post("/api/v1/assets")).cookie(cookie)
                        .content("{\"tag\":\"" + tag + "\",\"type\":\"LAPTOP\",\"make\":\"Dell\",\"model\":\"Latitude\",\"serial\":\"SN1\",\"purchaseDate\":\"2025-04-01\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Test
    void assignThenReturnKeepsTheHistoryAndShowsItToTheEmployee() throws Exception {
        String id = add(hrCookie, "LT-" + suffix);
        mockMvc.perform(get("/api/v1/assets/" + id).cookie(hrCookie)).andExpect(jsonPath("$.data.status").value("AVAILABLE")).andExpect(jsonPath("$.data.purchaseDate").value("2025-04-01"));

        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(hrCookie)
                        .content("{\"employeeId\":\"" + employeeId + "\",\"assignedOn\":\"2026-01-05\",\"notes\":\"With charger\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/assets/" + id).cookie(hrCookie)).andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.holderId").value(employeeId.toString())).andExpect(jsonPath("$.data.history.length()").value(1));
        // already assigned
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(hrCookie).content("{\"employeeId\":\"" + otherId + "\"}")).andExpect(status().isUnprocessableEntity());
        // the employee is told, and sees it under "mine"; someone else doesn't
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Asset assigned%'", Integer.class, employeeId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/assets/mine").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.current.length()").value(1))
                .andExpect(jsonPath("$.data.current[0].tag").value("LT-" + suffix)).andExpect(jsonPath("$.data.past.length()").value(0));
        mockMvc.perform(get("/api/v1/assets/mine").cookie(other)).andExpect(jsonPath("$.data.current.length()").value(0));
        // can't be deleted once it has history, or returned before it was handed out
        mockMvc.perform(write(patch("/api/v1/assets/" + id + "/assignments/current")).cookie(hrCookie).content("{\"condition\":\"GOOD\",\"returnedOn\":\"2026-01-01\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(patch("/api/v1/assets/" + id + "/assignments/current")).cookie(hrCookie).content("{\"returnedOn\":\"2026-02-01\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(patch("/api/v1/assets/" + id + "/assignments/current")).cookie(hrCookie).content("{\"condition\":\"DAMAGED\",\"returnedOn\":\"2026-02-01\",\"notes\":\"Cracked screen\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/assets/" + id).cookie(hrCookie)).andExpect(jsonPath("$.data.status").value("IN_REPAIR"))
                .andExpect(jsonPath("$.data.history[0].returnCondition").value("DAMAGED")).andExpect(jsonPath("$.data.history[0].returnNotes").value("Cracked screen"));
        mockMvc.perform(get("/api/v1/assets/mine").cookie(employee)).andExpect(jsonPath("$.data.current.length()").value(0)).andExpect(jsonPath("$.data.past.length()").value(1));
        mockMvc.perform(write(delete("/api/v1/assets/" + id)).cookie(hrCookie)).andExpect(status().isUnprocessableEntity());
        // repaired: available again, then assignable to someone else
        mockMvc.perform(write(put("/api/v1/assets/" + id)).cookie(hrCookie).content("{\"tag\":\"LT-" + suffix + "\",\"type\":\"LAPTOP\",\"status\":\"AVAILABLE\"}")).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(hrCookie).content("{\"employeeId\":\"" + otherId + "\"}")).andExpect(status().isCreated());
        mockMvc.perform(write(put("/api/v1/assets/" + id)).cookie(hrCookie).content("{\"tag\":\"LT-" + suffix + "\",\"type\":\"LAPTOP\",\"status\":\"RETIRED\"}")).andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity = 'asset' AND entity_id = ? AND action IN ('asset.assign', 'asset.return')", Integer.class, id))
                .isEqualTo(3);
    }

    @Test
    void onlyAssetManagersCanManage() throws Exception {
        String id = add(hrCookie, "PH-" + suffix);
        mockMvc.perform(get("/api/v1/assets").cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/assets/" + id).cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/assets/assignees").cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/assets")).cookie(employee).content("{\"tag\":\"X\",\"type\":\"PHONE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(employee).content("{\"employeeId\":\"" + employeeId + "\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(patch("/api/v1/assets/" + id + "/assignments/current")).cookie(employee).content("{\"condition\":\"GOOD\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(delete("/api/v1/assets/" + id)).cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/assets").cookie(hrCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/assets/assignees").cookie(hrCookie)).andExpect(jsonPath("$.data.length()").value(3));
        mockMvc.perform(get("/api/v1/assets/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void validationAndDuplicateTags() throws Exception {
        add(hrCookie, "DUP-" + suffix);
        mockMvc.perform(write(post("/api/v1/assets")).cookie(hrCookie).content("{\"tag\":\"dup-" + suffix + "\",\"type\":\"LAPTOP\"}")).andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/assets")).cookie(hrCookie).content("{\"tag\":\"  \",\"type\":\"LAPTOP\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/assets")).cookie(hrCookie).content("{\"tag\":\"T1\",\"type\":\"SPACESHIP\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/assets")).cookie(hrCookie).content("{\"tag\":\"T1\",\"type\":\"PHONE\",\"purchaseDate\":\"01/04/2025\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/assets")).cookie(hrCookie).content("{\"tag\":\"T1\",\"type\":\"PHONE\",\"status\":\"ASSIGNED\"}")).andExpect(status().isBadRequest());
        String id = add(hrCookie, "OK-" + suffix);
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(hrCookie).content("{\"employeeId\":\"not-a-uuid\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(hrCookie).content("{\"employeeId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
        mockMvc.perform(write(patch("/api/v1/assets/" + id + "/assignments/current")).cookie(hrCookie).content("{\"condition\":\"GOOD\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/assets/" + UUID.randomUUID()).cookie(hrCookie)).andExpect(status().isNotFound());
        // never assigned: can be deleted
        mockMvc.perform(write(delete("/api/v1/assets/" + id)).cookie(hrCookie)).andExpect(status().isOk());
    }

    @Test
    void aBusinessNeverSeesAnotherBusinesssAssets() throws Exception {
        String id = add(hrCookie, "SAME-" + suffix);
        mockMvc.perform(get("/api/v1/assets").cookie(otherBusinessHr)).andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/assets/" + id).cookie(otherBusinessHr)).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/v1/assets/" + id + "/assignments")).cookie(otherBusinessHr).content("{\"employeeId\":\"" + employeeId + "\"}")).andExpect(status().isNotFound());
        // the same tag is free in the other business
        add(otherBusinessHr, "SAME-" + suffix);
    }
}
