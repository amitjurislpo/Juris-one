package com.jurislpo.jurisone.tasks;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** Tasks are free for every business, and read-only once the subscription has ended (the global rule). */
class TaskReadOnlyTest extends TaskApiTestBase {

    @Test
    void afterTheSubscriptionEndsTasksCanBeReadButNotChanged() throws Exception {
        Person hr = person(hrRole, "hr");
        String projectId = project(hr.cookie(), "RO", "PRIVATE");
        String id = task(hr.cookie(), "\"projectId\":\"" + projectId + "\",\"title\":\"Before the end\"").get("id").asText();
        jdbc.update("UPDATE business_subscriptions SET starts_on = current_date - 400, ends_on = current_date - 1 WHERE business_id = ?", data.business());

        mockMvc.perform(get("/api/v1/tasks/mine").cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/tasks/" + id).cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/projects/" + projectId + "/board").cookie(hr.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/tasks")).cookie(hr.cookie()).content("{\"title\":\"After the end\"}"))
                .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
        mockMvc.perform(write(patch("/api/v1/tasks/" + id)).cookie(hr.cookie()).content("{\"version\":0,\"title\":\"Changed\"}")).andExpect(status().isPaymentRequired());
        mockMvc.perform(write(post("/api/v1/projects")).cookie(hr.cookie()).content("{\"key\":\"NO\",\"name\":\"No\"}")).andExpect(status().isPaymentRequired());
    }
}
