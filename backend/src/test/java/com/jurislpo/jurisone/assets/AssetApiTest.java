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

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hr, employee, other, otherBusinessHr;
    UUID employeeId, otherId;
    String s;

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
        s = TestData.letters(8);
        TestData t = new TestData(jdbc).inBusiness("assets-" + s);
        UUID hrRole = t.role("hrm", "self", "dir", "people.view", "assets.manage");
        UUID empRole = t.role("employee", "self", "dir");
        t.employee("AH-" + s, "hr." + s + "@example.com", hrRole, PW);
        employeeId = t.employee("AE-" + s, "emp." + s + "@example.com", empRole, PW);
        otherId = t.employee("AX-" + s, "other." + s + "@example.com", empRole, PW);
        TestData t2 = new TestData(jdbc).inBusiness("assets2-" + s);
        t2.employee("BH-" + s, "hr2." + s + "@example.com", t2.role("hrm", "self", "dir", "assets.manage"), PW);
        hr = signIn("hr." + s + "@example.com");
        employee = signIn("emp." + s + "@example.com");
        other = signIn("other." + s + "@example.com");
        otherBusinessHr = signIn("hr2." + s + "@example.com");
    }

    String add(Cookie c, String tag) throws Exception {
        return json.readTree(mvc.perform(write(post("/api/assets")).cookie(c)
                        .content("{\"tag\":\"" + tag + "\",\"type\":\"LAPTOP\",\"make\":\"Dell\",\"model\":\"Latitude\",\"serial\":\"SN1\",\"purchaseDate\":\"2025-04-01\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    @Test
    void assignThenReturnKeepsTheHistoryAndShowsItToTheEmployee() throws Exception {
        String id = add(hr, "LT-" + s);
        mvc.perform(get("/api/assets/" + id).cookie(hr)).andExpect(jsonPath("$.data.status").value("AVAILABLE")).andExpect(jsonPath("$.data.purchaseDate").value("2025-04-01"));

        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(hr)
                        .content("{\"employeeId\":\"" + employeeId + "\",\"assignedOn\":\"2026-01-05\",\"notes\":\"With charger\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/assets/" + id).cookie(hr)).andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.holderId").value(employeeId.toString())).andExpect(jsonPath("$.data.history.length()").value(1));
        // already assigned
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(hr).content("{\"employeeId\":\"" + otherId + "\"}")).andExpect(status().isUnprocessableEntity());
        // the employee is told, and sees it under "mine"; someone else doesn't
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Asset assigned%'", Integer.class, employeeId)).isEqualTo(1);
        mvc.perform(get("/api/assets/mine").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.current.length()").value(1))
                .andExpect(jsonPath("$.data.current[0].tag").value("LT-" + s)).andExpect(jsonPath("$.data.past.length()").value(0));
        mvc.perform(get("/api/assets/mine").cookie(other)).andExpect(jsonPath("$.data.current.length()").value(0));
        // can't be deleted once it has history, or returned before it was handed out
        mvc.perform(write(patch("/api/assets/" + id + "/assignments/current")).cookie(hr).content("{\"condition\":\"GOOD\",\"returnedOn\":\"2026-01-01\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(write(patch("/api/assets/" + id + "/assignments/current")).cookie(hr).content("{\"returnedOn\":\"2026-02-01\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(patch("/api/assets/" + id + "/assignments/current")).cookie(hr).content("{\"condition\":\"DAMAGED\",\"returnedOn\":\"2026-02-01\",\"notes\":\"Cracked screen\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/assets/" + id).cookie(hr)).andExpect(jsonPath("$.data.status").value("IN_REPAIR"))
                .andExpect(jsonPath("$.data.history[0].returnCondition").value("DAMAGED")).andExpect(jsonPath("$.data.history[0].returnNotes").value("Cracked screen"));
        mvc.perform(get("/api/assets/mine").cookie(employee)).andExpect(jsonPath("$.data.current.length()").value(0)).andExpect(jsonPath("$.data.past.length()").value(1));
        mvc.perform(write(delete("/api/assets/" + id)).cookie(hr)).andExpect(status().isUnprocessableEntity());
        // repaired: available again, then assignable to someone else
        mvc.perform(write(put("/api/assets/" + id)).cookie(hr).content("{\"tag\":\"LT-" + s + "\",\"type\":\"LAPTOP\",\"status\":\"AVAILABLE\"}")).andExpect(status().isOk());
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(hr).content("{\"employeeId\":\"" + otherId + "\"}")).andExpect(status().isCreated());
        mvc.perform(write(put("/api/assets/" + id)).cookie(hr).content("{\"tag\":\"LT-" + s + "\",\"type\":\"LAPTOP\",\"status\":\"RETIRED\"}")).andExpect(status().isUnprocessableEntity());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity = 'asset' AND entity_id = ? AND action IN ('asset.assign', 'asset.return')", Integer.class, id))
                .isEqualTo(3);
    }

    @Test
    void onlyAssetManagersCanManage() throws Exception {
        String id = add(hr, "PH-" + s);
        mvc.perform(get("/api/assets").cookie(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets/" + id).cookie(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets/assignees").cookie(employee)).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/assets")).cookie(employee).content("{\"tag\":\"X\",\"type\":\"PHONE\"}")).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(employee).content("{\"employeeId\":\"" + employeeId + "\"}")).andExpect(status().isForbidden());
        mvc.perform(write(patch("/api/assets/" + id + "/assignments/current")).cookie(employee).content("{\"condition\":\"GOOD\"}")).andExpect(status().isForbidden());
        mvc.perform(write(delete("/api/assets/" + id)).cookie(employee)).andExpect(status().isForbidden());
        mvc.perform(get("/api/assets").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/assets/assignees").cookie(hr)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/api/assets/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void validationAndDuplicateTags() throws Exception {
        add(hr, "DUP-" + s);
        mvc.perform(write(post("/api/assets")).cookie(hr).content("{\"tag\":\"dup-" + s + "\",\"type\":\"LAPTOP\"}")).andExpect(status().isConflict());
        mvc.perform(write(post("/api/assets")).cookie(hr).content("{\"tag\":\"  \",\"type\":\"LAPTOP\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/assets")).cookie(hr).content("{\"tag\":\"T1\",\"type\":\"SPACESHIP\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/assets")).cookie(hr).content("{\"tag\":\"T1\",\"type\":\"PHONE\",\"purchaseDate\":\"01/04/2025\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/assets")).cookie(hr).content("{\"tag\":\"T1\",\"type\":\"PHONE\",\"status\":\"ASSIGNED\"}")).andExpect(status().isBadRequest());
        String id = add(hr, "OK-" + s);
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(hr).content("{\"employeeId\":\"not-a-uuid\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(hr).content("{\"employeeId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
        mvc.perform(write(patch("/api/assets/" + id + "/assignments/current")).cookie(hr).content("{\"condition\":\"GOOD\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/assets/" + UUID.randomUUID()).cookie(hr)).andExpect(status().isNotFound());
        // never assigned: can be deleted
        mvc.perform(write(delete("/api/assets/" + id)).cookie(hr)).andExpect(status().isOk());
    }

    @Test
    void aBusinessNeverSeesAnotherBusinesssAssets() throws Exception {
        String id = add(hr, "SAME-" + s);
        mvc.perform(get("/api/assets").cookie(otherBusinessHr)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/assets/" + id).cookie(otherBusinessHr)).andExpect(status().isNotFound());
        mvc.perform(write(post("/api/assets/" + id + "/assignments")).cookie(otherBusinessHr).content("{\"employeeId\":\"" + employeeId + "\"}")).andExpect(status().isNotFound());
        // the same tag is free in the other business
        add(otherBusinessHr, "SAME-" + s);
    }
}
